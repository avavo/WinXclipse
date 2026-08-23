#ifndef ROPT_RINGBUF_H
#define ROPT_RINGBUF_H

#include <stdint.h>
#include <stdatomic.h>
#include "rox.h"
#include "rox_gtt_react.h"

#ifdef __cplusplus
extern "C" {
#endif

#define ROPT_RINGBUF_SLOTS 64

typedef enum {
    ROPT_EVT_FLUSH        = 0,
    ROPT_EVT_PROFILE      = 1,
    ROPT_EVT_GTT_REACT    = 2,
    ROPT_EVT_PSI_REACT    = 3,
    ROPT_EVT_PSI_TREND    = 4,
    ROPT_EVT_MAJFLT_SKIP  = 5,
} rox_event_kind_t;

typedef struct {
    int64_t  timestamp_ms;
    uint8_t  kind;
    uint8_t  profile_applied;

    uint8_t  gtt_level;         /* 0–4, valor direto de rox_gtt_level_t */
    uint8_t  _pad_level;        /* reservado para uso futuro */

    uint8_t  psi_level;        /* 0–3, 0xFF se inválido */

    uint32_t ram_before_kb;
    uint32_t ram_after_kb;

    uint32_t gtt_pressure_pct;
    uint32_t _pad;
} rox_event_t;

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t slot_count;
    uint32_t slot_size;
    _Atomic uint32_t write_seq;
    uint32_t _reserved[3];
    rox_event_t slots[ROPT_RINGBUF_SLOTS];
} rox_ringbuf_t;

#define ROPT_RINGBUF_MAGIC   0x4E524D56u
#define ROPT_RINGBUF_VERSION 1u
#define ROPT_RINGBUF_SIZE    sizeof(rox_ringbuf_t)

/* Sentinela para ram_before_kb / ram_after_kb quando não há medição */
#define ROPT_RAM_UNMEASURED  0xFFFFFFFFu

int rox_ringbuf_init(void);
void rox_ringbuf_destroy(void);
int rox_ringbuf_fd(void);

void rox_ringbuf_push(
    rox_event_kind_t kind,
    rox_profile_t profile,
    rox_gtt_level_t gtt_level,
    int psi_level,
    unsigned long ram_before_kb,
    unsigned long ram_after_kb,
    int gtt_pressure_pct
);

int rox_ringbuf_read(rox_event_t *out, int count);
uint32_t rox_ringbuf_write_seq(void);

#ifdef __cplusplus
}
#endif

#endif
