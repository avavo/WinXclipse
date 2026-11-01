#include <aaudio/AAudio.h>
#include <jni.h>

#define WAIT_COMPLETION_TIMEOUT 100 * 1000000L

#include <pthread.h> // For threading (mutex, condition variables)
#include <stdint.h>  // For standard integer types like uint8_t
#include <time.h>

#include <malloc.h>      // For malloc/calloc
#include <android/log.h> // For logging

#define LOG_TAG "AlsaClientJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include <unistd.h> // For usleep()

#include <string.h> // For memcpy

// ===================================================================
//
//  PACER (VIRTUAL SINK) IMPLEMENTATION
//
// ===================================================================

/**
 * This struct holds all the state for our simulated audio device (the "pacer").
 * It manages a ring buffer and a consumer thread to create back-pressure.
 */
#include "audio_pacer.h"



enum Format {U8, S16LE, S16BE, FLOATLE, FLOATBE};

static aaudio_format_t toAAudioFormat(int format) {
    switch (format) {
        case FLOATLE:
        case FLOATBE:
            return AAUDIO_FORMAT_PCM_FLOAT;
        case U8:
            return AAUDIO_FORMAT_UNSPECIFIED;
        case S16LE:
        case S16BE:
        default:
            return AAUDIO_FORMAT_PCM_I16;
    }
}

/**
 * Helper function to calculate the size of a single audio frame in bytes.
 * This is needed to correctly calculate the total buffer size.
 */
static int get_bytes_per_frame(int format, int channelCount) {
    int bytes_per_sample = 0;
    switch (format) {
        case U8:
            bytes_per_sample = 1;
            break;
        case S16LE:
        case S16BE:
            bytes_per_sample = 2;
            break;
        case FLOATLE:
        case FLOATBE:
            bytes_per_sample = 4;
            break;
    }
    return bytes_per_sample * channelCount;
}

/**
 * This is the main function for our consumer thread. It runs in the background.
 * Its only job is to "consume" data from the ring buffer at a rate that
 * matches the audio sample rate, simulating a real audio device.
 */
/**
 * MODIFICATION: This is the new, more precise consumer thread function.
 * It uses a monotonic clock to stay in sync and avoid timing drift.
 */
#include <sched.h>

static AAudioStream *aaudioCreate(int32_t format, int8_t channelCount, int32_t sampleRate, int32_t bufferSize) {
    aaudio_result_t result;
    AAudioStreamBuilder *builder;
    AAudioStream *stream;

    result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return NULL;

    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, toAAudioFormat(format));
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);

    result = AAudioStreamBuilder_openStream(builder, &stream);
    if (result != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        return NULL;
    }

    AAudioStream_setBufferSizeInFrames(stream, bufferSize);

    result = AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) return NULL;

    return stream;
}

static int aaudioWrite(AAudioStream *aaudioStream, void *buffer, int numFrames) {
    aaudio_result_t framesWritten = AAudioStream_write(aaudioStream, buffer, numFrames, WAIT_COMPLETION_TIMEOUT);
    return framesWritten;
}

static void aaudioStart(AAudioStream *aaudioStream) {
    AAudioStream_requestStart(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_STARTING, NULL, WAIT_COMPLETION_TIMEOUT);
}

static void aaudioStop(AAudioStream *aaudioStream) {
    AAudioStream_requestStop(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_STOPPING, NULL, WAIT_COMPLETION_TIMEOUT);
}

static void aaudioPause(AAudioStream *aaudioStream) {
    AAudioStream_requestPause(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_PAUSING, NULL, WAIT_COMPLETION_TIMEOUT);
}

static void aaudioFlush(AAudioStream *aaudioStream) {
    AAudioStream_requestFlush(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_FLUSHING, NULL, WAIT_COMPLETION_TIMEOUT);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedCreate(JNIEnv *env, jobject obj, jint format,
                                                             jbyte channelCount, jint sampleRate, jint bufferSize) {
    int frame_size = get_bytes_per_frame(format, channelCount);
    if (channelCount <= 0 || channelCount > 8 || sampleRate <= 0 || sampleRate > 768000 || frame_size <= 0
            || bufferSize <= 0 || (uint64_t)bufferSize * frame_size > 64u * 1024u * 1024u) return 0;
    // 1. Allocate memory for the context struct. We use calloc to ensure all fields are zero-initialized.
    PacerContext *ctx = (PacerContext*)calloc(1, sizeof(PacerContext));
    if (!ctx) {
        LOGI("Failed to allocate memory for PacerContext");
        return 0; // Return 0 (null) on failure.
    }

    // 2. Calculate and store the audio properties passed in from Java.
    ctx->frame_size_bytes = frame_size;
    ctx->capacity_bytes = (size_t)bufferSize * frame_size;
    ctx->sample_rate = sampleRate;

    // 3. Allocate the actual memory for our ring buffer.
    ctx->buffer = (uint8_t*)malloc(ctx->capacity_bytes);
    if (!ctx->buffer) {
        LOGI("Failed to allocate memory for pacer ring buffer");
        free(ctx); // Important: Clean up the context struct we already allocated.
        return 0;
    }

    // 4. Initialize the threading components. These are essential for thread safety.
    if (pthread_mutex_init(&ctx->mutex, NULL) != 0) { free(ctx->buffer); free(ctx); return 0; }
    if (pthread_cond_init(&ctx->cond_not_full, NULL) != 0) {
        pthread_mutex_destroy(&ctx->mutex); free(ctx->buffer); free(ctx); return 0;
    }
    if (pthread_cond_init(&ctx->cond_not_empty, NULL) != 0) {
        pthread_cond_destroy(&ctx->cond_not_full); pthread_mutex_destroy(&ctx->mutex);
        free(ctx->buffer); free(ctx); return 0;
    }

    // 5. The 'running' state is already 0 from calloc, indicating it's initially stopped.
    // ctx->running = 0;

    LOGI("Pacer context created successfully. Buffer size: %zu bytes.", ctx->capacity_bytes);

    // 6. Return the memory address of our new struct, cast to a jlong so Java can store it.
    return (jlong)ctx;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_create(JNIEnv *env, jobject obj, jint format,
                                                    jbyte channelCount, jint sampleRate, jint bufferSize) {
    return (jlong)aaudioCreate(format, channelCount, sampleRate, bufferSize);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedWrite(JNIEnv *env, jobject obj, jlong streamPtr, jobject buffer,
                                                            jint numFrames) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    if (!ctx || !buffer || numFrames < 0) return -1;
    if (numFrames == 0) return 0;

    void *data = (*env)->GetDirectBufferAddress(env, buffer);
    uint64_t requested = (uint64_t)numFrames * ctx->frame_size_bytes;
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    // An oversized write can never fit and previously waited forever.
    if (!data || capacity < 0 || requested > (uint64_t)capacity || requested > ctx->capacity_bytes) return -1;
    size_t bytes_to_write = (size_t)requested;

    pthread_mutex_lock(&ctx->mutex);

    while ((ctx->capacity_bytes - ctx->available_bytes) < bytes_to_write && ctx->running == 1) {
        pthread_cond_wait(&ctx->cond_not_full, &ctx->mutex);
    }

    if (ctx->running != 1) {
        pthread_mutex_unlock(&ctx->mutex);
        return -1;
    }

    size_t remaining_capacity = ctx->capacity_bytes - ctx->write_pos_bytes;

    if (remaining_capacity >= bytes_to_write) {
        memcpy(ctx->buffer + ctx->write_pos_bytes, data, bytes_to_write);
        ctx->write_pos_bytes = (ctx->write_pos_bytes + bytes_to_write) % ctx->capacity_bytes;
    } else {
        memcpy(ctx->buffer + ctx->write_pos_bytes, data, remaining_capacity);
        memcpy(ctx->buffer, (uint8_t*)data + remaining_capacity, bytes_to_write - remaining_capacity);
        ctx->write_pos_bytes = bytes_to_write - remaining_capacity; // Already modulo by logic
    }

    ctx->available_bytes += bytes_to_write;

    pthread_cond_signal(&ctx->cond_not_empty);
    pthread_mutex_unlock(&ctx->mutex);

    return numFrames;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_write(JNIEnv *env, jobject obj, jlong streamPtr, jobject buffer,
                                                   jint numFrames) {
    AAudioStream *aaudioStream = (AAudioStream*)streamPtr;
    if (aaudioStream) {
        return aaudioWrite(aaudioStream, (*env)->GetDirectBufferAddress(env, buffer), numFrames);
    }
    else return -1;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedStart(JNIEnv *env, jobject obj, jlong streamPtr) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    if (!ctx) return;

    // Lock the mutex to safely change the state.
    pthread_mutex_lock(&ctx->mutex);

    // Only start the thread if it's currently stopped (state 0).
    if (ctx->running == 0) {
        ctx->running = 1; // Set state to "running".
        if (pthread_create(&ctx->consumer_thread, NULL, pacer_consumer_thread_func, ctx) == 0)
            ctx->thread_created = 1;
        else ctx->running = 0;
    }
    else if (ctx->running == 2) { // If it was paused...
        ctx->running = 1; //...just set the state back to "running".
        // The consumer thread is still alive but waiting, so we signal it to wake up.
        pthread_cond_broadcast(&ctx->cond_not_empty);
    }

    // Unlock the mutex.
    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_start(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) {
        return AAudioStream_requestStart(aaudioStream);
    }
    return -1; // Or some other error code indicating null pointer
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedFlush(JNIEnv *env, jobject obj, jlong streamPtr) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    if (!ctx) return;

    // Lock the mutex to safely reset the buffer state.
    pthread_mutex_lock(&ctx->mutex);

    // Reset all buffer pointers and counters. This effectively discards all data.
    ctx->read_pos_bytes = 0;
    ctx->write_pos_bytes = 0;
    ctx->available_bytes = 0;

    // Since the buffer is now empty, we signal any waiting writer thread that there is space.
    pthread_cond_broadcast(&ctx->cond_not_full);

    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_flush(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) aaudioFlush(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedPause(JNIEnv *env, jobject obj, jlong streamPtr) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    // Safety check: We can only pause if the stream is currently running.
    if (!ctx) return;

    // Lock the mutex to safely modify the state.
    pthread_mutex_lock(&ctx->mutex);

    // Set the state to "paused".
    if (ctx->running == 1) ctx->running = 2;

    // It's crucial to wake up any threads that might be waiting.
    // If simulatedWrite is waiting for space, or the consumer is waiting for data,
    // they need to wake up to see that the state has changed to "paused".
    pthread_cond_broadcast(&ctx->cond_not_full);
    pthread_cond_broadcast(&ctx->cond_not_empty);

    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_pause(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) aaudioPause(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedStop(JNIEnv *env, jobject obj, jlong streamPtr) {
    // "Stopping" the pacer is functionally the same as "pausing" it.
    // The consumer thread remains alive but idle, ready to be started again later.
    // The real cleanup and thread termination will happen in simulatedClose.
    // So, we can simply call the pause function.
    Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedPause(env, obj, streamPtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_stop(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) aaudioStop(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedClose(JNIEnv *env, jobject obj, jlong streamPtr) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    if (!ctx) return;

    // --- Begin graceful shutdown of the consumer thread ---

    // 1. Lock the mutex to safely change the state.
    pthread_mutex_lock(&ctx->mutex);

    // 3. Set the state to "stopped". This is the signal for the consumer thread's loop to exit.
    ctx->running = 0;

    // 4. Wake up the consumer thread in case it's waiting on a condition. It needs to
    //    wake up to see that ctx->running is now 0 and exit its loop.
    pthread_cond_broadcast(&ctx->cond_not_empty);
    pthread_cond_broadcast(&ctx->cond_not_full);

    // 5. Unlock the mutex so the consumer thread can acquire it to finish its loop.
    pthread_mutex_unlock(&ctx->mutex);

    // --- Wait for thread and clean up resources ---

    // 6. Wait for the consumer thread to finish executing completely. This is a crucial
    //    blocking call that prevents us from freeing memory while it's still in use.
    if (ctx->thread_created) {
        pthread_join(ctx->consumer_thread, NULL);
    }

    // 7. Now that the thread is gone, we can safely destroy the threading tools.
    pthread_mutex_destroy(&ctx->mutex);
    pthread_cond_destroy(&ctx->cond_not_full);
    pthread_cond_destroy(&ctx->cond_not_empty);

    // 8. Finally, free all the memory we allocated in simulatedCreate.
    free(ctx->buffer); // Free the ring buffer memory.
    free(ctx);         // Free the context struct itself.

    LOGI("Pacer context destroyed successfully.");
}


JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_close(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) AAudioStream_close(aaudioStream);
}