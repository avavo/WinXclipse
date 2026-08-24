#ifndef ROPT_INTERNAL_H
#define ROPT_INTERNAL_H

/* ─────────────────────────────────────────────
 * rox_internal.h
 *
 * Interface interna REAL do RamOpt.
 *
 * Regras:
 *  - Não expor heurísticas como API pública indireta
 *  - Não congelar structs que ainda mudam com frequência
 *  - Separar "snapshot" de "estado interno"
 * ───────────────────────────────────────────── */

#include "rox.h"

/* ═══════════════════════════════════════════════
 * VM / MEMORY CORE
 * ═══════════════════════════════════════════════ */

int        rox_read_meminfo(rox_stats_t *out);
int        rox_read_fragmentation(void);
int        rox_apply_vm_profile(rox_profile_t profile);

long long  rox_smaps_rss_kb(void);
long long  rox_self_vmswap_kb(void);
long long  rox_self_majflt(void);
long long  rox_smaps_lazyfree_kb(void);
long long  rox_cgroup_limit_kb(void);
long long  rox_self_vsz_kb(void);

/* ═══════════════════════════════════════════════
 * HEAP CONTROL LAYER
 * ═══════════════════════════════════════════════ */

int rox_heap_trim(void);

/* fluxo principal de pressão */
int rox_heap_madvise(void);
int rox_heap_madvise_free(void);

/* orquestração por perfil */
int rox_heap_compact(rox_profile_t profile);

/* Estado do ciclo de vida do core (1 = inicializado, 0 = não/shutdown).
 * Camadas de reação chamadas por caminhos externos (compat/emulador)
 * usam isto para não rodar sweeps fora da sessão. */
int rox_core_is_initialized(void);

/* ═══════════════════════════════════════════════
 * MALLOPT / GLIBC TUNING
 * ═══════════════════════════════════════════════ */

int rox_mallopt_apply(rox_profile_t profile);
int rox_mallopt_apply_perturb(void);
int rox_mallopt_reset(void);

/* ═══════════════════════════════════════════════
 * SGPU LAYER (Xclipse / devfreq abstraction)
 * ═══════════════════════════════════════════════ */

/*
 * Tipo concreto mantido aqui — rox_sgpu.c expõe campos estáveis
 * suficientes para que rox_compat.c e rox_core.c operem sem
 * depender dos internos do driver.
 */
typedef struct {
    long long gtt_used_bytes;
    long long gtt_total_bytes;
    long long vram_used_bytes;
    int       gtt_pressure;   /* 0–100 */
} rox_sgpu_mem_t;

int rox_sgpu_read_mem(rox_sgpu_mem_t *out);
int rox_sgpu_purge(rox_profile_t profile);

/* ═══════════════════════════════════════════════
 * PRESSURE (PSI / kernel signals)
 * ═══════════════════════════════════════════════ */

typedef struct {
    float some_avg10;
    float some_avg60;
    float full_avg10;
    float full_avg60;
    int   available;
} rox_psi_t;

int rox_psi_read(rox_psi_t *out);
int rox_psi_level(const rox_psi_t *psi);

/* camada de decisão */
int rox_pressure_react(void);

/* ═══════════════════════════════════════════════
 * FEEDBACK LOOP (NÃO ACESSO DIRETO EXTERNO)
 * ═══════════════════════════════════════════════ */

int rox_feedback_get_ineffective_count(void);

#endif /* ROPT_INTERNAL_H */
