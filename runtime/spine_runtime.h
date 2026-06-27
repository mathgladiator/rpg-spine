/* ============================================================================
 * spine_runtime.h - byte primitives for the spine save bytecode.
 * ============================================================================
 *
 * Hand-written, schema-agnostic, and meant to be read in one sitting. The
 * generated spine.gen.c is built ENTIRELY on top of these primitives; this file
 * is where all byte twiddling and bounds checking lives, so it is the one place
 * that has to be correct.
 *
 * All multi-byte integers are big-endian (network order) on the wire, so a save
 * written on any platform reads back identically on any other. See
 * documents/SAVE_FORMAT.md.
 *
 * Strict C99: fixed-width types, bool, no compiler extensions.
 * ========================================================================== */

#ifndef SPINE_RUNTIME_H
#define SPINE_RUNTIME_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- op codes (mirror documents/SAVE_FORMAT.md) --------------------------- */

enum {
    OP_END         = 0x00,
    OP_ENTER_ARRAY = 0x01,
    OP_NEXT        = 0x02,
    OP_EXIT_ARRAY  = 0x03,

    OP_BOOL   = 0x10,
    OP_INT8   = 0x11,
    OP_UINT8  = 0x12,
    OP_INT16  = 0x13,
    OP_UINT16 = 0x14,
    OP_INT32  = 0x15,
    OP_UINT32 = 0x16,
    OP_INT64  = 0x17,
    OP_UINT64 = 0x18,
    OP_BYTES  = 0x19
};

/* the container magic "SPN1" and the current format version. */
#define SPINE_MAGIC0 0x53 /* 'S' */
#define SPINE_MAGIC1 0x50 /* 'P' */
#define SPINE_MAGIC2 0x4E /* 'N' */
#define SPINE_MAGIC3 0x31 /* '1' */
#define SPINE_FORMAT_VERSION 1u

/* ---- reader --------------------------------------------------------------- *
 * A cursor over a fixed, caller-owned byte buffer. Every read is bounds
 * checked: a read that would run past the end sets `error` and returns 0, and
 * once `error` is set it stays set (so callers can check once at the end). */

typedef struct spine_reader {
    const uint8_t *buf;
    size_t         size;
    size_t         pos;
    bool           error;
} spine_reader;

void spine_reader_init(spine_reader *r, const uint8_t *buf, size_t size);
bool spine_reader_ok(const spine_reader *r);   /* !error */
size_t spine_reader_remaining(const spine_reader *r);

uint8_t  spine_read_op_code(spine_reader *r);  /* == read_uint8, named for intent */

bool     spine_read_bool(spine_reader *r);
int8_t   spine_read_int8(spine_reader *r);
uint8_t  spine_read_uint8(spine_reader *r);
int16_t  spine_read_int16(spine_reader *r);
uint16_t spine_read_uint16(spine_reader *r);
int32_t  spine_read_int32(spine_reader *r);
uint32_t spine_read_uint32(spine_reader *r);
int64_t  spine_read_int64(spine_reader *r);
uint64_t spine_read_uint64(spine_reader *r);

/* Read `n` raw bytes into `out` (out may be NULL only when n == 0). On overrun
 * sets error and copies nothing. */
void spine_read_bytes(spine_reader *r, void *out, size_t n);

/* Skip the value that follows a scalar op of `op` (used to ignore an unknown
 * field code). Sets error on an unknown scalar op or an overrun. */
void spine_skip_value(spine_reader *r, uint8_t op);

/* ---- writer --------------------------------------------------------------- *
 * A growable, heap-backed byte buffer. Allocation failure sets `error`; once
 * set, further writes are no-ops, so callers check once at the end. */

typedef struct spine_writer {
    uint8_t *buf;
    size_t   size;
    size_t   cap;
    bool     error;
} spine_writer;

void spine_writer_init(spine_writer *w);
bool spine_writer_ok(const spine_writer *w);
void spine_writer_free(spine_writer *w);       /* free the buffer (on error path) */

/* Detach the finished buffer: returns malloc'd bytes (caller frees with free()),
 * writes the length to *out_size, and leaves the writer empty. Returns NULL if
 * the writer is in error. */
uint8_t *spine_writer_take(spine_writer *w, int *out_size);

void spine_write_op_code(spine_writer *w, uint8_t op);

void spine_write_bool(spine_writer *w, bool v);
void spine_write_int8(spine_writer *w, int8_t v);
void spine_write_uint8(spine_writer *w, uint8_t v);
void spine_write_int16(spine_writer *w, int16_t v);
void spine_write_uint16(spine_writer *w, uint16_t v);
void spine_write_int32(spine_writer *w, int32_t v);
void spine_write_uint32(spine_writer *w, uint32_t v);
void spine_write_int64(spine_writer *w, int64_t v);
void spine_write_uint64(spine_writer *w, uint64_t v);
void spine_write_bytes(spine_writer *w, const void *data, size_t n);

/* ---- integrity ------------------------------------------------------------ */

uint32_t spine_crc32(const uint8_t *data, size_t n);

#ifdef __cplusplus
}
#endif

#endif /* SPINE_RUNTIME_H */
