/* ============================================================================
 * bwa.h - reusable decoder for the .bwa black/white/transparent image bank.
 * ============================================================================
 *
 * The .bwa format (documents/BWA_FORMAT.md) is the project's one image codec: a
 * compact, Playdate-friendly bank of one or more animations, each a horizontal
 * strip of equal frames, pixels run-length/detail coded one self-describing byte
 * at a time. A still image is a one-cell animation.
 *
 * This decoder is deliberately standalone and reusable: it depends only on the
 * spine_runtime reader (big-endian + bounds checks) and the C standard library,
 * so it can decode a bank embedded in a .storybin, a standalone .bwa file, or
 * any future container. Encoding stays in Java (mg.assets.BwCodec / BwAnimBank).
 * ========================================================================== */

#ifndef BWA_H
#define BWA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "spine_runtime.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BWA_MAGIC 0x42

/* tri-state pixel values (the project-wide Mono palette). */
enum { BWA_WHITE = 0, BWA_BLACK = 1, BWA_TRANSPARENT = 2 };

/* one animation: playback metadata + a decoded tri-state pixel strip. */
typedef struct bwa_anim {
    uint16_t type;          /* AnimType role code (stable) */
    uint16_t cells;         /* equal frames packed across the strip */
    uint16_t frame_time_ms; /* 0 = a still */
    uint16_t loops;         /* 0 = loop forever */
    uint16_t width;         /* full strip width */
    uint16_t height;
    uint8_t *pixels;        /* row-major, width*height bytes (0/1/2); NULL if empty */
} bwa_anim;

/* a bank of animations. */
typedef struct bwa_bank {
    uint16_t  count;
    bwa_anim *anims;
} bwa_bank;

/* Decode the single-byte RLE/detail pixel stream into exactly `bits` pixels
 * (values 0/1/2). Returns false on a malformed or over/under-running stream. */
bool bwa_decode_pixels(const uint8_t *data, size_t len, uint8_t *out, uint32_t bits);

/* Read a whole bank from a reader, advancing it past the bank. Validates the
 * magic, every bits==width*height, the per-anim checksum, and that each stream
 * decodes to exactly its pixel count. On failure returns false and frees any
 * partial allocation (the bank is left empty). */
bool bwa_bank_read(spine_reader *r, bwa_bank *out);

/* Convenience: read a bank from a standalone byte buffer. */
bool bwa_bank_read_bytes(const uint8_t *data, int size, bwa_bank *out);

void bwa_bank_free(bwa_bank *b);

/* width of a single frame (width/cells, or width when cells is 0). */
uint16_t bwa_anim_cell_width(const bwa_anim *a);

#ifdef __cplusplus
}
#endif

#endif /* BWA_H */
