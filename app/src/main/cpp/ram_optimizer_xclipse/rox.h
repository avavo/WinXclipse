#ifndef ROPT_H
#define ROPT_H

#ifdef __cplusplus
extern "C" {
#endif

#ifndef ROPT_API
#if defined(__GNUC__)
#define ROPT_API __attribute__((visibility("default")))
#else
#define ROPT_API
#endif
#endif

/* Versão */
#define ROPT_VERSION_MAJOR 1
#define ROPT_VERSION_MINOR 4
#define ROPT_VERSION_PATCH 2

/* Códigos de retorno */
#define ROPT_OK         0
#define ROPT_ERR_PERM  -1
#define ROPT_ERR_NODEV -2
#define ROPT_ERR_INIT  -3
#define ROPT_ERR_PARAM -4

/* Perfis de memória */
typedef enum {
    ROPT_PROFILE_LIGHT = 0,
    ROPT_PROFILE_MEDIUM = 1,
    ROPT_PROFILE_AGGRESSIVE = 2,
    ROPT_PROFILE_MAX_VALID = 2
} rox_profile_t;

/* Snapshot de RAM */
typedef struct {
    unsigned long total_kb;
    unsigned long free_kb;
    unsigned long available_kb;
    unsigned long cached_kb;
    int fragmentation; /* 0–100 */
} rox_stats_t;

/* Snapshot somente-leitura da decisao interna.
 * Nenhuma acao de limpeza, mallopt, madvise ou escrita em /proc e executada. */
typedef struct {
    rox_stats_t ram;

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

    rox_profile_t suggested_profile;
    int would_act;
} rox_diagnostic_t;

/* API */
ROPT_API int rox_init(void);
ROPT_API void rox_shutdown(void);
ROPT_API int rox_apply_profile(rox_profile_t profile);
ROPT_API int rox_flush(void);
ROPT_API int rox_get_stats(rox_stats_t *out);
ROPT_API int rox_diagnose(rox_diagnostic_t *out);
ROPT_API const char *rox_version(void);

#ifdef __cplusplus
}
#endif

#endif
