#ifndef NRAMV_THERMAL_H
#define NRAMV_THERMAL_H

/* ─────────────────────────────────────────────
 * nramv_thermal.h
 * Pressão térmica (Adição 10)
 * ───────────────────────────────────────────── */

typedef enum {
    NRAMV_THERMAL_NORMAL = 0,
    NRAMV_THERMAL_WARM   = 1,
    NRAMV_THERMAL_HOT    = 2,
    NRAMV_THERMAL_CRITICAL = 3
} nramv_thermal_level_t;

/* Inicializa detecção de zona térmica */
void nramv_thermal_init(void);

/* Retorna temperatura em °C */
int nramv_thermal_get_temp(void);

/* Classifica nível térmico */
nramv_thermal_level_t nramv_thermal_get_level(void);

#endif /* NRAMV_THERMAL_H */
