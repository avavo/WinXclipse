#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <stdint.h>

#include "rox.h"
#include "rox_ringbuf.h"
#include "rox_debug.h"

/* ─────────────────────────────────────────────
 * rox_debug.c (corrigido)
 * Observabilidade / diagnóstico — consumidor do ringbuffer.
 * ───────────────────────────────────────────── */

/* ── Tabelas de nomes (definições únicas — extern em rox_debug.h) ── */

const char * const ROPT_EVT_KIND_NAMES[] = {
    "FLUSH",        /* 0 */
    "PROFILE",      /* 1 */
    "GTT_REACT",    /* 2 */
    "PSI_REACT",    /* 3 */
    "PSI_TREND",    /* 4 */
    "MAJFLT_SKIP",  /* 5 */
};

const char * const ROPT_PROFILE_NAMES[] = {
    "LIGHT",        /* 0 */
    "MEDIUM",       /* 1 */
    "AGGRESSIVE",   /* 2 */
};

const char * const ROPT_GTT_LEVEL_NAMES[] = {
    "NORMAL",       /* 0 */
    "LIGHT",        /* 1 */
    "MEDIUM",       /* 2 */
    "AGGRESSIVE",   /* 3 */
    "CRITICAL",     /* 4 */
};

/* ── Android log ────────────────────────────── */
#if defined(__ANDROID__)
#include <android/log.h>
#define ROPT_LOG_TAG "rox_debug"
#define rox_logcat(fmt, ...) \
    __android_log_print(ANDROID_LOG_DEBUG, ROPT_LOG_TAG, fmt, ##__VA_ARGS__)
#else
#define rox_logcat(fmt, ...) \
    fprintf(stderr, "[rox_debug] " fmt "\n", ##__VA_ARGS__)
#endif

/* Sentinel de "não medido" — definido em rox_ringbuf.h */

/* ── helpers ─────────────────────────────────── */
static const char *evt_kind_name(uint8_t kind)
{
    if (kind < ROPT_EVT_KIND_COUNT)
        return ROPT_EVT_KIND_NAMES[kind];
    return "UNKNOWN";
}

static const char *profile_name(uint8_t profile)
{
    if (profile < ROPT_PROFILE_COUNT)
        return ROPT_PROFILE_NAMES[profile];
    return "?";
}

static const char *gtt_level_name(uint8_t level)
{
    if (level < ROPT_GTT_LEVEL_COUNT)
        return ROPT_GTT_LEVEL_NAMES[level];
    return "?";
}

/* ── formatter único (corrige duplicação lógica) ─ */
typedef struct {
    char psi[8];
    char gtt_pct[12];
    char ram[48];
} rox_event_fmt_t;

static void format_event(const rox_event_t *e, rox_event_fmt_t *f)
{
    if (e->psi_level == 0xFF)
        snprintf(f->psi, sizeof(f->psi), "--");
    else
        snprintf(f->psi, sizeof(f->psi), "%u", e->psi_level);

    if (e->gtt_pressure_pct == 0xFFFFFFFFu) /* sentinela "inválido" do ringbuf */
        snprintf(f->gtt_pct, sizeof(f->gtt_pct), "--");
    else
        snprintf(f->gtt_pct, sizeof(f->gtt_pct), "%u%%", e->gtt_pressure_pct);

    if (e->ram_before_kb == ROPT_RAM_UNMEASURED ||
        e->ram_after_kb == ROPT_RAM_UNMEASURED)
    {
        snprintf(f->ram, sizeof(f->ram), "--");
    }
    else if (e->ram_after_kb == 0)
    {
        snprintf(f->ram, sizeof(f->ram), "%u KB→?", e->ram_before_kb);
    }
    else if (e->ram_before_kb == 0)
    {
        snprintf(f->ram, sizeof(f->ram), "?→%u KB", e->ram_after_kb);
    }
    else
    {
        long freed = (long)e->ram_after_kb - (long)e->ram_before_kb;
        snprintf(f->ram, sizeof(f->ram),
                 "%u→%u (%+ld KB)",
                 e->ram_before_kb,
                 e->ram_after_kb,
                 freed);
    }
}

/* ─────────────────────────────────────────────
 * total events
 * ───────────────────────────────────────────── */
uint32_t rox_debug_total_events(void)
{
    return rox_ringbuf_write_seq();
}

/* ─────────────────────────────────────────────
 * dump FILE*
 * ───────────────────────────────────────────── */
int rox_debug_dump(FILE *fp, int count)
{
    rox_event_t events[ROPT_RINGBUF_SLOTS];
    rox_event_fmt_t fmt;
    int n, i;
    int64_t base_ts;

    if (!fp)
        return 0;

    if (rox_ringbuf_fd() < 0) {
        fprintf(fp, "[rox_debug] ringbuffer nao inicializado\n");
        return 0;
    }

    if (count <= 0 || count > ROPT_RINGBUF_SLOTS)
        count = ROPT_RINGBUF_SLOTS;

    n = rox_ringbuf_read(events, count);
    if (n == 0) {
        fprintf(fp, "[rox_debug] ringbuffer vazio\n");
        return 0;
    }

    /* base temporal corrigida: menor timestamp real */
    base_ts = events[0].timestamp_ms;
    for (i = 1; i < n; i++) {
        if (events[i].timestamp_ms < base_ts)
            base_ts = events[i].timestamp_ms;
    }

    fprintf(fp,
        "[rox_debug] ultimos %d eventos\n"
        "delta_ms   kind         profile     gtt   psi   ram\n"
        "------------------------------------------------------------\n",
        n
    );

    for (i = 0; i < n; i++) {
        const rox_event_t *e = &events[i];
        int64_t delta = e->timestamp_ms - base_ts;

        format_event(e, &fmt);

        fprintf(fp,
            "[+%06lldms] %-12s %-11s %-5s %-4s %s\n",
            (long long)delta,
            evt_kind_name(e->kind),
            profile_name(e->profile_applied),
            gtt_level_name(e->gtt_level),
            fmt.psi,
            fmt.ram
        );
    }

    return n;
}

/* ─────────────────────────────────────────────
 * dump logcat
 * ───────────────────────────────────────────── */
int rox_debug_dump_logcat(int count)
{
    rox_event_t events[ROPT_RINGBUF_SLOTS];
    rox_event_fmt_t fmt;
    int n, i;
    int64_t base_ts;

    if (rox_ringbuf_fd() < 0) {
        rox_logcat("ringbuffer nao inicializado");
        return 0;
    }

    if (count <= 0 || count > ROPT_RINGBUF_SLOTS)
        count = ROPT_RINGBUF_SLOTS;

    n = rox_ringbuf_read(events, count);
    if (n == 0) {
        rox_logcat("ringbuffer vazio");
        return 0;
    }

    base_ts = events[0].timestamp_ms;
    for (i = 1; i < n; i++) {
        if (events[i].timestamp_ms < base_ts)
            base_ts = events[i].timestamp_ms;
    }

    rox_logcat("dump %d eventos:", n);

    for (i = 0; i < n; i++) {
        const rox_event_t *e = &events[i];
        int64_t delta = e->timestamp_ms - base_ts;

        format_event(e, &fmt);

        rox_logcat("[+%lldms] %s profile=%s gtt=%s psi=%s ram=%s",
            (long long)delta,
            evt_kind_name(e->kind),
            profile_name(e->profile_applied),
            gtt_level_name(e->gtt_level),
            fmt.psi,
            fmt.ram
        );
    }

    return n;
}

/* ─────────────────────────────────────────────
 * snapshot
 * ───────────────────────────────────────────── */
int rox_debug_snapshot(rox_debug_snapshot_t *out)
{
    rox_event_t events[ROPT_RINGBUF_SLOTS];
    int n, i;
    int flush_with_measurement = 0;

    if (!out)
        return ROPT_ERR_PARAM;

    memset(out, 0, sizeof(*out));

    if (rox_ringbuf_fd() < 0)
        return ROPT_ERR_INIT;

    n = rox_ringbuf_read(events, ROPT_RINGBUF_SLOTS);
    if (n == 0)
        return ROPT_OK;

    out->first_ts_ms = events[0].timestamp_ms;
    out->last_ts_ms  = events[n - 1].timestamp_ms;
    out->total_events = n;

    for (i = 0; i < n; i++) {
        const rox_event_t *e = &events[i];

        switch ((rox_event_kind_t)e->kind) {
        case ROPT_EVT_FLUSH:
            out->flush_count++;

            if (e->ram_before_kb != ROPT_RAM_UNMEASURED &&
                e->ram_after_kb != ROPT_RAM_UNMEASURED)
            {
                long freed = (long)e->ram_after_kb - (long)e->ram_before_kb;
                out->total_ram_freed_kb += freed;
                flush_with_measurement++;
            }
            break;

        case ROPT_EVT_PSI_REACT:
            out->psi_react_count++;
            break;

        case ROPT_EVT_PSI_TREND:
            out->psi_trend_count++;
            break;

        case ROPT_EVT_GTT_REACT:
            out->gtt_react_count++;
            break;

        case ROPT_EVT_MAJFLT_SKIP:
            out->majflt_skip_count++;
            break;

        default:
            break;
        }
    }

    if (flush_with_measurement > 0)
        out->avg_ram_freed_kb =
            out->total_ram_freed_kb / flush_with_measurement;

    return ROPT_OK;
}
