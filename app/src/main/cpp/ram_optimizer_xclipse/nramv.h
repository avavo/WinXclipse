#ifndef NRAMV_H
#define NRAMV_H

#ifdef __cplusplus
extern "C" {
#endif

#ifndef NRAMV_API
#if defined(__GNUC__)
#define NRAMV_API __attribute__((visibility("default")))
#else
#define NRAMV_API
#endif
#endif

/* Versão */
#define NRAMV_VERSION_MAJOR 1
#define NRAMV_VERSION_MINOR 4
#define NRAMV_VERSION_PATCH 2

/* Códigos de retorno */
#define NRAMV_OK         0
#define NRAMV_ERR_PERM  -1
#define NRAMV_ERR_NODEV -2
#define NRAMV_ERR_INIT  -3
#define NRAMV_ERR_PARAM -4

/* Perfis de memória */
typedef enum {
    NRAMV_PROFILE_LIGHT = 0,
    NRAMV_PROFILE_MEDIUM = 1,
    NRAMV_PROFILE_AGGRESSIVE = 2,
    NRAMV_PROFILE_MAX_VALID = 2
} nramv_profile_t;

/* Snapshot de RAM */
typedef struct {
    unsigned long total_kb;
    unsigned long free_kb;
    unsigned long available_kb;
    unsigned long cached_kb;
    int fragmentation; /* 0–100 */
} nramv_stats_t;

/* Snapshot somente-leitura da decisao interna.
 * Nenhuma acao de limpeza, mallopt, madvise ou escrita em /proc e executada. */
typedef struct {
    nramv_stats_t ram;

    int psi_available;
    float psi_some_avg10;
    float psi_some_avg60;
    float psi_full_avg10;
    float psi_full_avg60;
    int psi_level;

    int gtt_is_xclipse;
    int gtt_available;
    int gtt_sysfs_error;
    int gtt_level;
    int gtt_pressure_pct;
    int vram_pressure_pct;
    int gpu_utilization_pct;
    long long gtt_pressure_margin_kb;

    int thermal_temp_c;
    int thermal_level;
    int feedback_threshold_adjustment;

    nramv_profile_t suggested_profile;
    int would_act;
} nramv_diagnostic_t;

/* API */
NRAMV_API int nramv_init(void);
NRAMV_API void nramv_shutdown(void);
NRAMV_API int nramv_apply_profile(nramv_profile_t profile);
NRAMV_API int nramv_flush(void);
NRAMV_API int nramv_get_stats(nramv_stats_t *out);
NRAMV_API int nramv_diagnose(nramv_diagnostic_t *out);
NRAMV_API const char *nramv_version(void);

#ifdef __cplusplus
}
#endif

#endif
