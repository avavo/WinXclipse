#ifndef NRAMV_GTT_REACT_H
#define NRAMV_GTT_REACT_H

#include <stdint.h>

#include "nramv.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ─────────────────────────────────────────────
 * nramv_gtt_react.h
 *
 * Layer de reação à pressão GTT/VRAM da Xclipse 920/940.
 *
 * Trabalha no lado da CPU para compensar pressão da GPU
 * via liberação de RAM do sistema.
 * ───────────────────────────────────────────── */

/* Nível de pressão GTT (contrato binário estável) */
typedef enum {
    NRAMV_GTT_NORMAL     = 0,
    NRAMV_GTT_LIGHT      = 1,
    NRAMV_GTT_MEDIUM     = 2,
    NRAMV_GTT_AGGRESSIVE = 3,
    NRAMV_GTT_CRITICAL   = 4,

    NRAMV_GTT_LEVEL_COUNT
} nramv_gtt_level_t;

/* Estado atual da GPU + RAM */
typedef struct {
    int       is_xclipse;          /* 1 se Xclipse detectada */

    long long gtt_used_bytes;
    long long gtt_total_bytes;
    long long vram_used_bytes;
    long long vram_total_bytes;

    int       gtt_pressure_pct;
    int       vram_pressure_pct;

    int       gtt_available;
    int       gtt_sysfs_error;

    int       gpu_utilization_pct;
    int       cu_utilization_pct;

    long long total_kernel_pages;

    long long cur_freq_khz;
    long long max_freq_khz;

    int32_t   _pad0;               /* estabilidade ABI */
} nramv_gtt_state_t;

/* Diagnóstico completo */
typedef struct {
    nramv_gtt_state_t gtt;
    nramv_stats_t     ram;
    nramv_gtt_level_t level;

    /* margem de risco entre RAM disponível e pressão GTT */
    long long gtt_pressure_margin_kb;
} nramv_gtt_diag_t;

/* Callback expandido (evita acoplamento frágil futuro) */
typedef struct {
    nramv_gtt_level_t level;
    const nramv_gtt_state_t *gtt;
} nramv_gtt_event_t;

typedef void (*nramv_gtt_callback_t)(
    const nramv_gtt_event_t *event,
    void *userdata
);

/* ─────────────────────────────────────────────
 * API pública
 * ───────────────────────────────────────────── */

/* Lê estado da GPU via sysfs (sem root).
 * Se não for Xclipse: is_xclipse = 0 e retorna NRAMV_OK.
 */
int nramv_gtt_read(nramv_gtt_state_t *out);

/* Classifica nível de pressão (puro, sem I/O) */
nramv_gtt_level_t nramv_gtt_classify(const nramv_gtt_state_t *state);

/* Executa reação para nível específico */
int nramv_gtt_react_level(nramv_gtt_level_t level);

/* Pipeline completo: read → classify → react */
int nramv_gtt_react(void);

/* Diagnóstico completo GPU + RAM */
int nramv_gtt_diagnose(nramv_gtt_diag_t *out);

/* Callback de eventos de reação */
void nramv_gtt_set_callback(nramv_gtt_callback_t cb, void *userdata);

#ifdef __cplusplus
}
#endif

#endif /* NRAMV_GTT_REACT_H */
