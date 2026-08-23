#ifndef NRAMV_RINGBUF_H
#define NRAMV_RINGBUF_H

#include <stdint.h>
#include <stdatomic.h>
#include "nramv.h"
#include "nramv_gtt_react.h"

#ifdef __cplusplus
extern "C" {
#endif

#define NRAMV_RINGBUF_SLOTS 64

typedef enum {
    NRAMV_EVT_FLUSH        = 0,
    NRAMV_EVT_PROFILE      = 1,
    NRAMV_EVT_GTT_REACT    = 2,
    NRAMV_EVT_PSI_REACT    = 3,
    NRAMV_EVT_PSI_TREND    = 4,
    NRAMV_EVT_MAJFLT_SKIP  = 5,
} nramv_event_kind_t;

typedef struct {
    int64_t  timestamp_ms;
    uint8_t  kind;
    uint8_t  profile_applied;

    uint8_t  gtt_level;         /* 0–4, valor direto de nramv_gtt_level_t */
    uint8_t  _pad_level;        /* reservado para uso futuro */

    uint8_t  psi_level;        /* 0–3, 0xFF se inválido */

    uint32_t ram_before_kb;
    uint32_t ram_after_kb;

    uint32_t gtt_pressure_pct;
    uint32_t _pad;
} nramv_event_t;

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t slot_count;
    uint32_t slot_size;
    _Atomic uint32_t write_seq;
    uint32_t _reserved[3];
    nramv_event_t slots[NRAMV_RINGBUF_SLOTS];
} nramv_ringbuf_t;

#define NRAMV_RINGBUF_MAGIC   0x4E524D56u
#define NRAMV_RINGBUF_VERSION 1u
#define NRAMV_RINGBUF_SIZE    sizeof(nramv_ringbuf_t)

/* Sentinela para ram_before_kb / ram_after_kb quando não há medição */
#define NRAMV_RAM_UNMEASURED  0xFFFFFFFFu

int nramv_ringbuf_init(void);
void nramv_ringbuf_destroy(void);
int nramv_ringbuf_fd(void);

void nramv_ringbuf_push(
    nramv_event_kind_t kind,
    nramv_profile_t profile,
    nramv_gtt_level_t gtt_level,
    int psi_level,
    unsigned long ram_before_kb,
    unsigned long ram_after_kb,
    int gtt_pressure_pct
);

int nramv_ringbuf_read(nramv_event_t *out, int count);
uint32_t nramv_ringbuf_write_seq(void);

#ifdef __cplusplus
}
#endif

#endif
