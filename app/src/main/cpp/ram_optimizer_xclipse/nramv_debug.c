#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <stdint.h>

#include "nramv.h"
#include "nramv_ringbuf.h"
#include "nramv_debug.h"

/* ─────────────────────────────────────────────
 * nramv_debug.c (corrigido)
 * Observabilidade / diagnóstico — consumidor do ringbuffer.
 * ───────────────────────────────────────────── */

/* ── Tabelas de nomes (definições únicas — extern em nramv_debug.h) ── */

const char * const NRAMV_EVT_KIND_NAMES[] = {
    "FLUSH",        /* 0 */
    "PROFILE",      /* 1 */
    "GTT_REACT",    /* 2 */
    "PSI_REACT",    /* 3 */
    "PSI_TREND",    /* 4 */
    "MAJFLT_SKIP",  /* 5 */
};

const char * const NRAMV_PROFILE_NAMES[] = {
    "LIGHT",        /* 0 */
    "MEDIUM",       /* 1 */
    "AGGRESSIVE",   /* 2 */
};

const char * const NRAMV_GTT_LEVEL_NAMES[] = {
    "NORMAL",       /* 0 */
    "LIGHT",        /* 1 */
    "MEDIUM",       /* 2 */
    "AGGRESSIVE",   /* 3 */
    "CRITICAL",     /* 4 */
};

/* ── Android log ────────────────────────────── */
#if defined(__ANDROID__)
#include <android/log.h>
#define NRAMV_LOG_TAG "nramv_debug"
#define nramv_logcat(fmt, ...) \
    __android_log_print(ANDROID_LOG_DEBUG, NRAMV_LOG_TAG, fmt, ##__VA_ARGS__)
#else
#define nramv_logcat(fmt, ...) \
    fprintf(stderr, "[nramv_debug] " fmt "\n", ##__VA_ARGS__)
#endif

/* Sentinel de "não medido" — definido em nramv_ringbuf.h */

/* ── helpers ─────────────────────────────────── */
static const char *evt_kind_name(uint8_t kind)
{
    if (kind < NRAMV_EVT_KIND_COUNT)
        return NRAMV_EVT_KIND_NAMES[kind];
    return "UNKNOWN";
}

static const char *profile_name(uint8_t profile)
{
    if (profile < NRAMV_PROFILE_COUNT)
        return NRAMV_PROFILE_NAMES[profile];
    return "?";
}

static const char *gtt_level_name(uint8_t level)
{
    if (level < NRAMV_GTT_LEVEL_COUNT)
        return NRAMV_GTT_LEVEL_NAMES[level];
    return "?";
}

/* ── formatter único (corrige duplicação lógica) ─ */
typedef struct {
    char psi[8];
    char gtt_pct[12];
    char ram[48];
} nramv_event_fmt_t;

static void format_event(const nramv_event_t *e, nramv_event_fmt_t *f)
{
    if (e->psi_level == 0xFF)
        snprintf(f->psi, sizeof(f->psi), "--");
    else
        snprintf(f->psi, sizeof(f->psi), "%u", e->psi_level);

    if (e->gtt_pressure_pct == 0xFFu)
        snprintf(f->gtt_pct, sizeof(f->gtt_pct), "--");
    else
        snprintf(f->gtt_pct, sizeof(f->gtt_pct), "%u%%", e->gtt_pressure_pct);

    if (e->ram_before_kb == NRAMV_RAM_UNMEASURED ||
        e->ram_after_kb == NRAMV_RAM_UNMEASURED)
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
uint32_t nramv_debug_total_events(void)
{
    return nramv_ringbuf_write_seq();
}

/* ─────────────────────────────────────────────
 * dump FILE*
 * ───────────────────────────────────────────── */
int nramv_debug_dump(FILE *fp, int count)
{
    nramv_event_t events[NRAMV_RINGBUF_SLOTS];
    nramv_event_fmt_t fmt;
    int n, i;
    int64_t base_ts;

    if (!fp)
        return 0;

    if (nramv_ringbuf_fd() < 0) {
        fprintf(fp, "[nramv_debug] ringbuffer nao inicializado\n");
        return 0;
    }

    if (count <= 0 || count > NRAMV_RINGBUF_SLOTS)
        count = NRAMV_RINGBUF_SLOTS;

    n = nramv_ringbuf_read(events, count);
    if (n == 0) {
        fprintf(fp, "[nramv_debug] ringbuffer vazio\n");
        return 0;
    }

    /* base temporal corrigida: menor timestamp real */
    base_ts = events[0].timestamp_ms;
    for (i = 1; i < n; i++) {
        if (events[i].timestamp_ms < base_ts)
            base_ts = events[i].timestamp_ms;
    }

    fprintf(fp,
        "[nramv_debug] ultimos %d eventos\n"
        "delta_ms   kind         profile     gtt   psi   ram\n"
        "------------------------------------------------------------\n",
        n
    );

    for (i = 0; i < n; i++) {
        const nramv_event_t *e = &events[i];
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
int nramv_debug_dump_logcat(int count)
{
    nramv_event_t events[NRAMV_RINGBUF_SLOTS];
    nramv_event_fmt_t fmt;
    int n, i;
    int64_t base_ts;

    if (nramv_ringbuf_fd() < 0) {
        nramv_logcat("ringbuffer nao inicializado");
        return 0;
    }

    if (count <= 0 || count > NRAMV_RINGBUF_SLOTS)
        count = NRAMV_RINGBUF_SLOTS;

    n = nramv_ringbuf_read(events, count);
    if (n == 0) {
        nramv_logcat("ringbuffer vazio");
        return 0;
    }

    base_ts = events[0].timestamp_ms;
    for (i = 1; i < n; i++) {
        if (events[i].timestamp_ms < base_ts)
            base_ts = events[i].timestamp_ms;
    }

    nramv_logcat("dump %d eventos:", n);

    for (i = 0; i < n; i++) {
        const nramv_event_t *e = &events[i];
        int64_t delta = e->timestamp_ms - base_ts;

        format_event(e, &fmt);

        nramv_logcat("[+%lldms] %s profile=%s gtt=%s psi=%s ram=%s",
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
int nramv_debug_snapshot(nramv_debug_snapshot_t *out)
{
    nramv_event_t events[NRAMV_RINGBUF_SLOTS];
    int n, i;
    int flush_with_measurement = 0;

    if (!out)
        return NRAMV_ERR_PARAM;

    memset(out, 0, sizeof(*out));

    if (nramv_ringbuf_fd() < 0)
        return NRAMV_ERR_INIT;

    n = nramv_ringbuf_read(events, NRAMV_RINGBUF_SLOTS);
    if (n == 0)
        return NRAMV_OK;

    out->first_ts_ms = events[0].timestamp_ms;
    out->last_ts_ms  = events[n - 1].timestamp_ms;
    out->total_events = n;

    for (i = 0; i < n; i++) {
        const nramv_event_t *e = &events[i];

        switch ((nramv_event_kind_t)e->kind) {
        case NRAMV_EVT_FLUSH:
            out->flush_count++;

            if (e->ram_before_kb != NRAMV_RAM_UNMEASURED &&
                e->ram_after_kb != NRAMV_RAM_UNMEASURED)
            {
                long freed = (long)e->ram_after_kb - (long)e->ram_before_kb;
                out->total_ram_freed_kb += freed;
                flush_with_measurement++;
            }
            break;

        case NRAMV_EVT_PSI_REACT:
            out->psi_react_count++;
            break;

        case NRAMV_EVT_PSI_TREND:
            out->psi_trend_count++;
            break;

        case NRAMV_EVT_GTT_REACT:
            out->gtt_react_count++;
            break;

        case NRAMV_EVT_MAJFLT_SKIP:
            out->majflt_skip_count++;
            break;

        default:
            break;
        }
    }

    if (flush_with_measurement > 0)
        out->avg_ram_freed_kb =
            out->total_ram_freed_kb / flush_with_measurement;

    return NRAMV_OK;
}
