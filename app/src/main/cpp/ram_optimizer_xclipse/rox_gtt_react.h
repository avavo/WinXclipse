#ifndef ROPT_GTT_REACT_H
#define ROPT_GTT_REACT_H

#include <stdint.h>

#include "rox.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ─────────────────────────────────────────────
 * rox_gtt_react.h
 *
 * Layer de reação à pressão GTT/VRAM da Xclipse 920/940.
 *
 * Trabalha no lado da CPU para compensar pressão da GPU
 * via liberação de RAM do sistema.
 * ───────────────────────────────────────────── */

/* Nível de pressão GTT (contrato binário estável) */
typedef enum {
    ROPT_GTT_NORMAL     = 0,
    ROPT_GTT_LIGHT      = 1,
    ROPT_GTT_MEDIUM     = 2,
    ROPT_GTT_AGGRESSIVE = 3,
    ROPT_GTT_CRITICAL   = 4,

    ROPT_GTT_LEVEL_COUNT
} rox_gtt_level_t;

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
} rox_gtt_state_t;

/* Diagnóstico completo */
typedef struct {
    rox_gtt_state_t gtt;
    rox_stats_t     ram;
    rox_gtt_level_t level;

    /* margem de risco entre RAM disponível e pressão GTT */
    long long gtt_pressure_margin_kb;
} rox_gtt_diag_t;

/* Callback expandido (evita acoplamento frágil futuro) */
typedef struct {
    rox_gtt_level_t level;
    const rox_gtt_state_t *gtt;
} rox_gtt_event_t;

typedef void (*rox_gtt_callback_t)(
    const rox_gtt_event_t *event,
    void *userdata
);

/* ─────────────────────────────────────────────
 * API pública
 * ───────────────────────────────────────────── */

/* Lê estado da GPU via sysfs (sem root).
 * Se não for Xclipse: is_xclipse = 0 e retorna ROPT_OK.
 */
int rox_gtt_read(rox_gtt_state_t *out);

/* Classifica nível de pressão (puro, sem I/O) */
rox_gtt_level_t rox_gtt_classify(const rox_gtt_state_t *state);

/* Executa reação para nível específico */
int rox_gtt_react_level(rox_gtt_level_t level);

/* Variante para chamadores que já aplicaram mallopt/heap compact para este
 * evento (ex.: rox_flush): aplica só as ações de kernel/evento/callback. */
int rox_gtt_react_level_ex(rox_gtt_level_t level,
                           const rox_gtt_state_t *state,
                           int apply_heap_actions);

/* Pipeline completo: read → classify → react */
int rox_gtt_react(void);

/* Diagnóstico completo GPU + RAM */
int rox_gtt_diagnose(rox_gtt_diag_t *out);

/* Callback de eventos de reação */
void rox_gtt_set_callback(rox_gtt_callback_t cb, void *userdata);

/* Restaura vm.vfs_cache_pressure imediatamente se uma excursão GTT o
 * deixou elevado e o restore agendado ainda não rodou. Chamado pelo
 * shutdown: sem isso, a sessão morrendo na janela do timer deixava o
 * tuning do kernel perturbado até o reboot. */
void rox_gtt_force_cache_pressure_restore(void);

#ifdef __cplusplus
}
#endif

#endif /* ROPT_GTT_REACT_H */
