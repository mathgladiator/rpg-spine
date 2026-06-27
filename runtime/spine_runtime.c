/* spine_runtime.c - see spine_runtime.h. Big-endian byte primitives, a bounds-
 * checked reader, a growable writer, and crc32. Schema-agnostic. */

#include "spine_runtime.h"

#include <stdlib.h>
#include <string.h>

/* ============================================================================
 * reader
 * ========================================================================== */

void spine_reader_init(spine_reader *r, const uint8_t *buf, size_t size)
{
    r->buf   = buf;
    r->size  = size;
    r->pos   = 0;
    r->error = false;
}

bool spine_reader_ok(const spine_reader *r) { return !r->error; }

size_t spine_reader_remaining(const spine_reader *r)
{
    return r->pos <= r->size ? r->size - r->pos : 0;
}

/* Returns true and advances if `n` bytes are available; else sets error. */
static bool reader_have(spine_reader *r, size_t n)
{
    if (r->error) return false;
    if (n > r->size - r->pos) { r->error = true; return false; }
    return true;
}

uint8_t spine_read_uint8(spine_reader *r)
{
    if (!reader_have(r, 1)) return 0;
    return r->buf[r->pos++];
}

uint8_t spine_read_op_code(spine_reader *r) { return spine_read_uint8(r); }

int8_t spine_read_int8(spine_reader *r) { return (int8_t)spine_read_uint8(r); }

bool spine_read_bool(spine_reader *r) { return spine_read_uint8(r) != 0; }

uint16_t spine_read_uint16(spine_reader *r)
{
    if (!reader_have(r, 2)) return 0;
    uint16_t v = (uint16_t)((uint16_t)r->buf[r->pos] << 8 | (uint16_t)r->buf[r->pos + 1]);
    r->pos += 2;
    return v;
}

int16_t spine_read_int16(spine_reader *r) { return (int16_t)spine_read_uint16(r); }

uint32_t spine_read_uint32(spine_reader *r)
{
    if (!reader_have(r, 4)) return 0;
    uint32_t v = (uint32_t)r->buf[r->pos] << 24 | (uint32_t)r->buf[r->pos + 1] << 16 |
                 (uint32_t)r->buf[r->pos + 2] << 8 | (uint32_t)r->buf[r->pos + 3];
    r->pos += 4;
    return v;
}

int32_t spine_read_int32(spine_reader *r) { return (int32_t)spine_read_uint32(r); }

uint64_t spine_read_uint64(spine_reader *r)
{
    if (!reader_have(r, 8)) return 0;
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v = v << 8 | (uint64_t)r->buf[r->pos + (size_t)i];
    r->pos += 8;
    return v;
}

int64_t spine_read_int64(spine_reader *r) { return (int64_t)spine_read_uint64(r); }

void spine_read_bytes(spine_reader *r, void *out, size_t n)
{
    if (n == 0) return;
    if (!reader_have(r, n)) return;
    memcpy(out, r->buf + r->pos, n);
    r->pos += n;
}

void spine_skip_value(spine_reader *r, uint8_t op)
{
    switch (op) {
    case OP_BOOL: case OP_INT8: case OP_UINT8:   (void)spine_read_uint8(r);  break;
    case OP_INT16: case OP_UINT16:               (void)spine_read_uint16(r); break;
    case OP_INT32: case OP_UINT32:               (void)spine_read_uint32(r); break;
    case OP_INT64: case OP_UINT64:               (void)spine_read_uint64(r); break;
    case OP_BYTES: {
        uint32_t n = spine_read_uint32(r);
        if (reader_have(r, n)) r->pos += n;
        break;
    }
    default: r->error = true; break; /* not a scalar value op */
    }
}

/* ============================================================================
 * writer
 * ========================================================================== */

void spine_writer_init(spine_writer *w)
{
    w->buf   = NULL;
    w->size  = 0;
    w->cap   = 0;
    w->error = false;
}

bool spine_writer_ok(const spine_writer *w) { return !w->error; }

void spine_writer_free(spine_writer *w)
{
    free(w->buf);
    w->buf = NULL;
    w->size = w->cap = 0;
}

/* Ensure room for `n` more bytes, doubling capacity. Sets error on OOM. */
static bool writer_reserve(spine_writer *w, size_t n)
{
    if (w->error) return false;
    if (n <= w->cap - w->size) return true;
    size_t need = w->size + n;
    size_t cap  = w->cap ? w->cap : 64;
    while (cap < need) cap *= 2;
    uint8_t *p = (uint8_t *)realloc(w->buf, cap);
    if (!p) { w->error = true; return false; }
    w->buf = p;
    w->cap = cap;
    return true;
}

uint8_t *spine_writer_take(spine_writer *w, int *out_size)
{
    if (w->error) { spine_writer_free(w); if (out_size) *out_size = 0; return NULL; }
    uint8_t *p = w->buf;
    if (out_size) *out_size = (int)w->size;
    w->buf = NULL;
    w->size = w->cap = 0;
    return p;
}

void spine_write_uint8(spine_writer *w, uint8_t v)
{
    if (!writer_reserve(w, 1)) return;
    w->buf[w->size++] = v;
}

void spine_write_op_code(spine_writer *w, uint8_t op) { spine_write_uint8(w, op); }
void spine_write_int8(spine_writer *w, int8_t v) { spine_write_uint8(w, (uint8_t)v); }
void spine_write_bool(spine_writer *w, bool v) { spine_write_uint8(w, v ? 1 : 0); }

void spine_write_uint16(spine_writer *w, uint16_t v)
{
    if (!writer_reserve(w, 2)) return;
    w->buf[w->size++] = (uint8_t)(v >> 8);
    w->buf[w->size++] = (uint8_t)(v);
}

void spine_write_int16(spine_writer *w, int16_t v) { spine_write_uint16(w, (uint16_t)v); }

void spine_write_uint32(spine_writer *w, uint32_t v)
{
    if (!writer_reserve(w, 4)) return;
    w->buf[w->size++] = (uint8_t)(v >> 24);
    w->buf[w->size++] = (uint8_t)(v >> 16);
    w->buf[w->size++] = (uint8_t)(v >> 8);
    w->buf[w->size++] = (uint8_t)(v);
}

void spine_write_int32(spine_writer *w, int32_t v) { spine_write_uint32(w, (uint32_t)v); }

void spine_write_uint64(spine_writer *w, uint64_t v)
{
    if (!writer_reserve(w, 8)) return;
    for (int i = 7; i >= 0; --i) w->buf[w->size++] = (uint8_t)(v >> (i * 8));
}

void spine_write_int64(spine_writer *w, int64_t v) { spine_write_uint64(w, (uint64_t)v); }

void spine_write_bytes(spine_writer *w, const void *data, size_t n)
{
    if (n == 0) return;
    if (!writer_reserve(w, n)) return;
    memcpy(w->buf + w->size, data, n);
    w->size += n;
}

/* ============================================================================
 * crc32 (IEEE 802.3, table-free reflected algorithm)
 * ========================================================================== */

uint32_t spine_crc32(const uint8_t *data, size_t n)
{
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; ++i) {
        crc ^= data[i];
        for (int k = 0; k < 8; ++k)
            crc = (crc >> 1) ^ (0xEDB88320u & (uint32_t)(-(int32_t)(crc & 1)));
    }
    return crc ^ 0xFFFFFFFFu;
}
