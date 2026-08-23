#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>

#include "rox.h"
#include "rox_gtt_react.h"
#include "rox_ringbuf.h"

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

/* ─────────────────────────────────────────────
 * rox_ringbuf.c
 * Ringbuffer de telemetria com serialização local.
 *
 * A API pode ser chamada fora do g_state_lock do core, então este módulo
 * precisa proteger init/destroy/read/push por conta própria. Sim, mais um
 * mutex. A alternativa era usar ponteiro mmap depois de munmap, que é um
 * jeito excelente de transformar logs em acidentes arqueológicos.
 * ───────────────────────────────────────────── */

static int              g_buf_fd = -1;
static rox_ringbuf_t *g_buf    = NULL;
static pthread_mutex_t  g_ringbuf_lock = PTHREAD_MUTEX_INITIALIZER;

static int64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000LL +
           (int64_t)(ts.tv_nsec / 1000000LL);
}

#if defined(__ANDROID__) || defined(__linux__)
#include <sys/syscall.h>

static inline int rox_memfd_create(const char *name, unsigned int flags)
{
#ifdef __NR_memfd_create
    return (int)syscall(__NR_memfd_create, name, flags);
#else
    (void)name;
    (void)flags;
    return -1;
#endif
}
#else
static inline int rox_memfd_create(const char *name, unsigned int flags)
{
    (void)name;
    (void)flags;
    return -1;
}
#endif

static uint32_t clamp_u32_from_ulong(unsigned long v)
{
    return (v > 0xFFFFFFFFUL) ? 0xFFFFFFFFu : (uint32_t)v;
}

int rox_ringbuf_init(void)
{
    int fd;
    void *map;

    pthread_mutex_lock(&g_ringbuf_lock);

    if (g_buf) {
        pthread_mutex_unlock(&g_ringbuf_lock);
        return ROPT_OK;
    }

    fd = rox_memfd_create("rox_events", MFD_CLOEXEC);
    if (fd < 0) {
        pthread_mutex_unlock(&g_ringbuf_lock);
        return ROPT_ERR_NODEV;
    }

    if (ftruncate(fd, (off_t)ROPT_RINGBUF_SIZE) != 0) {
        close(fd);
        pthread_mutex_unlock(&g_ringbuf_lock);
        return ROPT_ERR_NODEV;
    }

    map = mmap(NULL,
               ROPT_RINGBUF_SIZE,
               PROT_READ | PROT_WRITE,
               MAP_SHARED,
               fd,
               0);

    if (map == MAP_FAILED) {
        close(fd);
        pthread_mutex_unlock(&g_ringbuf_lock);
        return ROPT_ERR_NODEV;
    }

    memset(map, 0, ROPT_RINGBUF_SIZE);

    g_buf = (rox_ringbuf_t *)map;
    g_buf_fd = fd;

    g_buf->magic      = ROPT_RINGBUF_MAGIC;
    g_buf->version    = ROPT_RINGBUF_VERSION;
    g_buf->slot_count = ROPT_RINGBUF_SLOTS;
    g_buf->slot_size  = sizeof(rox_event_t);
    atomic_store_explicit(&g_buf->write_seq, 0, memory_order_release);

    pthread_mutex_unlock(&g_ringbuf_lock);
    return ROPT_OK;
}

void rox_ringbuf_push(rox_event_kind_t kind,
                        rox_profile_t profile,
                        rox_gtt_level_t gtt_level,
                        int psi_level,
                        unsigned long ram_before_kb,
                        unsigned long ram_after_kb,
                        int gtt_pressure_pct)
{
    uint32_t events;
    uint32_t slot_idx;
    rox_event_t *slot;

    pthread_mutex_lock(&g_ringbuf_lock);

    if (!g_buf) {
        pthread_mutex_unlock(&g_ringbuf_lock);
        return;
    }

    events = atomic_load_explicit(&g_buf->write_seq, memory_order_relaxed);
    slot_idx = events % ROPT_RINGBUF_SLOTS;
    slot = &g_buf->slots[slot_idx];

    slot->timestamp_ms     = now_ms();
    slot->kind             = (uint8_t)kind;
    slot->profile_applied  = (uint8_t)profile;
    slot->gtt_level        = (uint8_t)gtt_level;
    slot->_pad_level       = 0;
    slot->psi_level        = (psi_level >= 0 && psi_level <= 3)
                             ? (uint8_t)psi_level : 0xFFu;
    slot->ram_before_kb    = clamp_u32_from_ulong(ram_before_kb);
    slot->ram_after_kb     = clamp_u32_from_ulong(ram_after_kb);
    slot->gtt_pressure_pct = (gtt_pressure_pct >= 0 && gtt_pressure_pct <= 100)
                             ? (uint32_t)gtt_pressure_pct : 0xFFFFFFFFu;
    slot->_pad = 0;

    atomic_store_explicit(&g_buf->write_seq, events + 1, memory_order_release);

    pthread_mutex_unlock(&g_ringbuf_lock);
}

int rox_ringbuf_read(rox_event_t *out, int count)
{
    uint32_t events;
    uint32_t available;
    uint32_t start;
    int n;

    if (!out || count <= 0)
        return 0;

    if (count > ROPT_RINGBUF_SLOTS)
        count = ROPT_RINGBUF_SLOTS;

    pthread_mutex_lock(&g_ringbuf_lock);

    if (!g_buf) {
        pthread_mutex_unlock(&g_ringbuf_lock);
        return 0;
    }

    events = atomic_load_explicit(&g_buf->write_seq, memory_order_acquire);
    if (events == 0) {
        pthread_mutex_unlock(&g_ringbuf_lock);
        return 0;
    }

    available = (events < ROPT_RINGBUF_SLOTS)
              ? events
              : ROPT_RINGBUF_SLOTS;

    n = (count < (int)available) ? count : (int)available;
    start = (events + ROPT_RINGBUF_SLOTS - (uint32_t)n) %
            ROPT_RINGBUF_SLOTS;

    for (int i = 0; i < n; i++) {
        uint32_t idx = (start + (uint32_t)i) % ROPT_RINGBUF_SLOTS;
        out[i] = g_buf->slots[idx];
    }

    pthread_mutex_unlock(&g_ringbuf_lock);
    return n;
}

uint32_t rox_ringbuf_write_seq(void)
{
    uint32_t seq = 0;

    pthread_mutex_lock(&g_ringbuf_lock);
    if (g_buf)
        seq = atomic_load_explicit(&g_buf->write_seq, memory_order_relaxed);
    pthread_mutex_unlock(&g_ringbuf_lock);

    return seq;
}

int rox_ringbuf_fd(void)
{
    int fd;

    pthread_mutex_lock(&g_ringbuf_lock);
    fd = g_buf_fd;
    pthread_mutex_unlock(&g_ringbuf_lock);

    return fd;
}

void rox_ringbuf_destroy(void)
{
    pthread_mutex_lock(&g_ringbuf_lock);

    if (g_buf) {
        munmap(g_buf, ROPT_RINGBUF_SIZE);
        g_buf = NULL;
    }

    if (g_buf_fd >= 0) {
        close(g_buf_fd);
        g_buf_fd = -1;
    }

    pthread_mutex_unlock(&g_ringbuf_lock);
}
