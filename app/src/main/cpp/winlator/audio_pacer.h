#ifndef WINX_AUDIO_PACER_H
#define WINX_AUDIO_PACER_H

typedef struct {
    // --- Ring Buffer ---
    uint8_t *buffer;          // The pointer to the actual memory for our audio buffer.
    size_t capacity_bytes;    // The total size of the buffer in bytes.
    size_t write_pos_bytes;   // The position where the next write should start.
    size_t read_pos_bytes;    // The position where the consumer thread should read from.
    size_t available_bytes;   // How many bytes of data are currently in the buffer.

    // --- Audio Properties ---
    int sample_rate;          // The sample rate (e.g., 44100), needed for the consumer thread's timing.
    int frame_size_bytes;     // The size of a single audio frame in bytes (e.g., 4 bytes for 16-bit stereo).

    // --- Threading and State Management ---
    pthread_t consumer_thread; // The handle to our background consumer thread.
    pthread_mutex_t mutex;     // The lock to make our buffer thread-safe.
    pthread_cond_t cond_not_full;  // A condition variable to signal when the buffer has space.
    pthread_cond_t cond_not_empty; // A condition variable to signal when the buffer has data.
    int running;              // Guarded by mutex: 0=stopped, 1=running, 2=paused.
    int thread_created;
} PacerContext;

void *pacer_consumer_thread_func(void *arg) {
    PacerContext *ctx = (PacerContext*)arg;

    /* No SCHED_FIFO here: this thread only drains a ring buffer on a
     * 10 ms monotonic schedule, and real-time priority would let it preempt
     * the render/X11 threads on the Exynos big cores. */

    LOGI("Pacer consumer thread started with high-precision timing.");


    struct timespec next_wakeup_time;
    clock_gettime(CLOCK_MONOTONIC, &next_wakeup_time);

    for (;;) {
        pthread_mutex_lock(&ctx->mutex);

        while (ctx->running != 0 && (ctx->running == 2 || ctx->available_bytes == 0)) {
            pthread_cond_wait(&ctx->cond_not_empty, &ctx->mutex);
            // Pauses and underruns do not accumulate time to "catch up" later.
            clock_gettime(CLOCK_MONOTONIC, &next_wakeup_time);
        }

        if (ctx->running == 0) {
            pthread_mutex_unlock(&ctx->mutex);
            break;
        }

        // Consume at most 10 ms of queued audio.
        size_t consume_chunk_frames = ctx->sample_rate / 100;
        size_t consume_chunk_bytes = consume_chunk_frames * ctx->frame_size_bytes;
        if (consume_chunk_bytes == 0) consume_chunk_bytes = ctx->frame_size_bytes; // Consume at least one frame
        if (consume_chunk_bytes > ctx->available_bytes) {
            consume_chunk_bytes = ctx->available_bytes;
        }

        ctx->read_pos_bytes = (ctx->read_pos_bytes + consume_chunk_bytes) % ctx->capacity_bytes;
        ctx->available_bytes -= consume_chunk_bytes;

        // Calculate the duration of the consumed audio chunk in nanoseconds
        long duration_ns = (long)((uint64_t)consume_chunk_bytes * 1000000000u
                / ctx->frame_size_bytes / ctx->sample_rate);

        // Schedule the next wakeup time
        next_wakeup_time.tv_nsec += duration_ns;
        if (next_wakeup_time.tv_nsec >= 1000000000) {
            next_wakeup_time.tv_sec++;
            next_wakeup_time.tv_nsec -= 1000000000;
        }

        pthread_cond_broadcast(&ctx->cond_not_full);
        pthread_mutex_unlock(&ctx->mutex);

        // Sleep until the calculated next wakeup time
        struct timespec current_time;
        clock_gettime(CLOCK_MONOTONIC, &current_time);

        if ((current_time.tv_sec > next_wakeup_time.tv_sec) ||
            (current_time.tv_sec == next_wakeup_time.tv_sec && current_time.tv_nsec > next_wakeup_time.tv_nsec)) {
            // We're late; immediately schedule next wakeup from now
            next_wakeup_time = current_time;
        }

        long sleep_ns = (next_wakeup_time.tv_sec - current_time.tv_sec) * 1000000000L + (next_wakeup_time.tv_nsec - current_time.tv_nsec);

        if (sleep_ns > 0) {
            struct timespec sleep_duration = {0, sleep_ns};
            nanosleep(&sleep_duration, NULL);
        }
    }

    LOGI("Pacer consumer thread exiting.");
    return NULL;
}

#endif
