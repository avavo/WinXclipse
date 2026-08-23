#ifndef NRAMV_INTERNAL_H
#define NRAMV_INTERNAL_H

/* ─────────────────────────────────────────────
 * nramv_internal.h
 *
 * Interface interna REAL do NRAMV.
 *
 * Regras:
 *  - Não expor heurísticas como API pública indireta
 *  - Não congelar structs que ainda mudam com frequência
 *  - Separar "snapshot" de "estado interno"
 * ───────────────────────────────────────────── */

#include "nramv.h"

/* ═══════════════════════════════════════════════
 * VM / MEMORY CORE
 * ═══════════════════════════════════════════════ */

int        nramv_read_meminfo(nramv_stats_t *out);
int        nramv_read_fragmentation(void);
int        nramv_apply_vm_profile(nramv_profile_t profile);

long long  nramv_smaps_rss_kb(void);
long long  nramv_self_vmswap_kb(void);
long long  nramv_self_majflt(void);
long long  nramv_smaps_lazyfree_kb(void);
long long  nramv_cgroup_limit_kb(void);
long long  nramv_self_vsz_kb(void);

/* ═══════════════════════════════════════════════
 * HEAP CONTROL LAYER
 * ═══════════════════════════════════════════════ */

int nramv_heap_trim(void);

/* fluxo principal de pressão */
int nramv_heap_madvise(void);
int nramv_heap_madvise_free(void);

/* orquestração por perfil */
int nramv_heap_compact(nramv_profile_t profile);

/* ═══════════════════════════════════════════════
 * MALLOPT / GLIBC TUNING
 * ═══════════════════════════════════════════════ */

int nramv_mallopt_apply(nramv_profile_t profile);
int nramv_mallopt_apply_perturb(void);
int nramv_mallopt_reset(void);

/* ═══════════════════════════════════════════════
 * SGPU LAYER (Xclipse / devfreq abstraction)
 * ═══════════════════════════════════════════════ */

/*
 * Tipo concreto mantido aqui — nramv_sgpu.c expõe campos estáveis
 * suficientes para que nramv_compat.c e nramv_core.c operem sem
 * depender dos internos do driver.
 */
typedef struct {
    long long gtt_used_bytes;
    long long gtt_total_bytes;
    long long vram_used_bytes;
    int       gtt_pressure;   /* 0–100 */
} nramv_sgpu_mem_t;

int nramv_sgpu_read_mem(nramv_sgpu_mem_t *out);
int nramv_sgpu_purge(nramv_profile_t profile);

/* ═══════════════════════════════════════════════
 * PRESSURE (PSI / kernel signals)
 * ═══════════════════════════════════════════════ */

typedef struct {
    float some_avg10;
    float some_avg60;
    float full_avg10;
    float full_avg60;
    int   available;
} nramv_psi_t;

int nramv_psi_read(nramv_psi_t *out);
int nramv_psi_level(const nramv_psi_t *psi);

/* camada de decisão */
int nramv_pressure_react(void);

/* ═══════════════════════════════════════════════
 * FEEDBACK LOOP (NÃO ACESSO DIRETO EXTERNO)
 * ═══════════════════════════════════════════════ */

int nramv_feedback_get_ineffective_count(void);

#endif /* NRAMV_INTERNAL_H */
