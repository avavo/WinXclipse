#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <unistd.h>
#include <dirent.h>

#include "rox_thermal.h"

/* ─────────────────────────────────────────────
 * rox_thermal.c
 * Leitura de temperatura via sysfs
 * ───────────────────────────────────────────── */

static char g_thermal_path[512] = "";
static atomic_int g_thermal_init_done = 0;
/* FIX Bug 5: flag separada para sinalizar que o path já foi gravado.
 * g_thermal_init_done=1 significa "está inicializando ou já iniciou";
 * g_thermal_path_ready=1 significa "o path está disponível para leitura".
 * Leitores em get_temp() veem o path completo ou "" (sem leitura parcial). */
static atomic_int g_thermal_path_ready = 0;

static int is_preferred_zone(const char *name)
{
    return (strstr(name, "cpu") ||
            strstr(name, "pkg") ||
            strstr(name, "soc"));
}

void rox_thermal_init(void)
{
    /* Garante execução única mesmo sob init concorrente.
     * CAS de 0→1: apenas a thread vencedora entra no bloco. */
    int expected = 0;
    if (!atomic_compare_exchange_strong(&g_thermal_init_done, &expected, 1))
        return;

    DIR *dir = opendir("/sys/class/thermal");
    if (!dir)
        return;

    struct dirent *e;
    char path[512];
    char type[64];
    char fallback_path[512];
    int fallback_temp = -1;

    fallback_path[0] = '\0';

    while ((e = readdir(dir))) {

        if (strncmp(e->d_name, "thermal_zone", 12) != 0)
            continue;

        snprintf(path, sizeof(path),
                 "/sys/class/thermal/%s/type",
                 e->d_name);

        FILE *f = fopen(path, "r");
        if (!f)
            continue;

        if (!fgets(type, sizeof(type), f)) {
            fclose(f);
            continue;
        }

        fclose(f);

        type[strcspn(type, "\n")] = 0;

        if (is_preferred_zone(type)) {
            snprintf(g_thermal_path, sizeof(g_thermal_path),
                     "/sys/class/thermal/%s/temp",
                     e->d_name);
            break;
        }

        /* Sem zona preferida (cpu/pkg/soc), não pegar a primeira da ordem
         * de readdir — em Exynos costuma ser bateria/wifi/charger, que
         * nunca atinge o limiar HOT e deixava o guard térmico cego.
         * Amostra cada candidata uma vez e guarda a mais quente. */
        if (fallback_path[0] == '\0' || fallback_temp < 0 ||
            g_thermal_path[0] == '\0') {
            snprintf(path, sizeof(path),
                     "/sys/class/thermal/%s/temp",
                     e->d_name);

            f = fopen(path, "r");
            if (f) {
                int t = -1;
                if (fscanf(f, "%d", &t) == 1 && t > fallback_temp) {
                    fallback_temp = t;
                    snprintf(fallback_path, sizeof(fallback_path),
                             "/sys/class/thermal/%s/temp",
                             e->d_name);
                }
                fclose(f);
            }
        }
    }

    closedir(dir);

    if (g_thermal_path[0] == '\0' && fallback_path[0] != '\0')
        snprintf(g_thermal_path, sizeof(g_thermal_path), "%s", fallback_path);

    /* FIX Bug 5: publica o path com release fence para garantir que
     * qualquer thread que observe path_ready=1 veja o path completo. */
    atomic_store_explicit(&g_thermal_path_ready, 1, memory_order_release);
}

int rox_thermal_get_temp(void)
{
    /* FIX Bug 5: aguarda path_ready antes de ler g_thermal_path,
     * evitando a janela de corrida entre init (que escreve o path)
     * e get_temp (que lê). Se init ainda não terminou, retorna -1
     * sem tentar abrir um path potencialmente incompleto. */
    if (!atomic_load_explicit(&g_thermal_path_ready, memory_order_acquire))
        return -1;

    if (g_thermal_path[0] == '\0')
        return -1;

    FILE *f = fopen(g_thermal_path, "r");
    if (!f)
        return -1;

    int temp = -1;
    if (fscanf(f, "%d", &temp) != 1) {
        fclose(f);
        return -1;
    }

    fclose(f);

    if (temp > 1000)
        temp /= 1000;

    return temp;
}

rox_thermal_level_t rox_thermal_get_level(void)
{
    int t = rox_thermal_get_temp();
    if (t < 0)
        return ROPT_THERMAL_NORMAL;

    if (t >= 85)
        return ROPT_THERMAL_CRITICAL;
    if (t >= 75)
        return ROPT_THERMAL_HOT;
    if (t >= 65)
        return ROPT_THERMAL_WARM;

    return ROPT_THERMAL_NORMAL;
}
