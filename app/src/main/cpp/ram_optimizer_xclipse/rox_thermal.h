#ifndef ROPT_THERMAL_H
#define ROPT_THERMAL_H

/* ─────────────────────────────────────────────
 * rox_thermal.h
 * Pressão térmica (Adição 10)
 * ───────────────────────────────────────────── */

typedef enum {
    ROPT_THERMAL_NORMAL = 0,
    ROPT_THERMAL_WARM   = 1,
    ROPT_THERMAL_HOT    = 2,
    ROPT_THERMAL_CRITICAL = 3
} rox_thermal_level_t;

/* Inicializa detecção de zona térmica */
void rox_thermal_init(void);

/* Retorna temperatura em °C */
int rox_thermal_get_temp(void);

/* Classifica nível térmico */
rox_thermal_level_t rox_thermal_get_level(void);

#endif /* ROPT_THERMAL_H */
