/* bwa.c - see bwa.h. Decodes the .bwa image bank using the spine_runtime reader. */

#include "bwa.h"

#include <stdlib.h>

bool bwa_decode_pixels(const uint8_t *data, size_t len, uint8_t *out, uint32_t bits)
{
    size_t i = 0;
    uint32_t p = 0;
    while (i < len) {
        uint8_t b = data[i++];
        if (b & 0x80) {                         /* detail: 7 mono pixels, MSB first */
            for (int k = 0; k < 7; k++) {
                if (p >= bits) return false;
                out[p++] = (uint8_t)((b >> (6 - k)) & 1);
            }
        } else if ((b & 0x60) == 0x60) {        /* long run (count stored as count-1) */
            if (i >= len) return false;
            uint8_t c = (uint8_t)((b >> 3) & 3);
            uint32_t n = ((((uint32_t)(b & 7)) << 8) | data[i++]) + 1;
            while (n--) {
                if (p >= bits) return false;
                out[p++] = c;
            }
        } else {                                /* short run (count stored as count-1) */
            uint8_t c = (uint8_t)((b >> 5) & 3);
            uint32_t n = (uint32_t)(b & 0x1F) + 1;
            while (n--) {
                if (p >= bits) return false;
                out[p++] = c;
            }
        }
    }
    return p == bits;
}

/* the per-anim header checksum: sum of every field byte, masked to 8 bits.
 * Mirrors mg.assets.BwAnimBank.checksum exactly. */
static uint8_t bwa_checksum(uint16_t type, uint16_t cells, uint16_t ft, uint8_t loops,
                            uint32_t bits, uint16_t w, uint16_t h)
{
    unsigned s = 0;
    s += (type >> 8) & 0xFF;  s += type & 0xFF;
    s += (cells >> 8) & 0xFF; s += cells & 0xFF;
    s += (ft >> 8) & 0xFF;    s += ft & 0xFF;
    s += loops & 0xFF;
    s += (bits >> 24) & 0xFF; s += (bits >> 16) & 0xFF; s += (bits >> 8) & 0xFF; s += bits & 0xFF;
    s += (w >> 8) & 0xFF;     s += w & 0xFF;
    s += (h >> 8) & 0xFF;     s += h & 0xFF;
    return (uint8_t)(s & 0xFF);
}

bool bwa_bank_read(spine_reader *r, bwa_bank *out)
{
    out->count = 0;
    out->anims = NULL;

    uint8_t magic = spine_read_uint8(r);
    if (!spine_reader_ok(r) || magic != BWA_MAGIC) return false;
    uint16_t count = spine_read_uint16(r);
    if (!spine_reader_ok(r)) return false;

    bwa_anim *anims = count ? (bwa_anim *)calloc(count, sizeof(bwa_anim)) : NULL;
    if (count && !anims) return false;

    uint16_t i;
    for (i = 0; i < count; i++) {
        bwa_anim *a = &anims[i];
        a->type          = spine_read_uint16(r);
        a->cells         = spine_read_uint16(r);
        a->frame_time_ms = spine_read_uint16(r);
        a->loops         = spine_read_uint8(r);
        uint32_t bits    = spine_read_uint32(r);
        a->width         = spine_read_uint16(r);
        a->height        = spine_read_uint16(r);
        uint8_t  cs      = spine_read_uint8(r);
        uint32_t byteCount = spine_read_uint32(r);
        if (!spine_reader_ok(r)) break;
        if (bits != (uint32_t)a->width * (uint32_t)a->height) break;
        if (cs != bwa_checksum(a->type, a->cells, a->frame_time_ms, a->loops, bits, a->width, a->height)) break;
        if (byteCount > spine_reader_remaining(r)) break;

        const uint8_t *data = r->buf + r->pos;
        r->pos += byteCount;
        a->pixels = bits ? (uint8_t *)malloc(bits) : NULL;
        if (bits && !a->pixels) break;
        if (!bwa_decode_pixels(data, byteCount, a->pixels, bits)) break;
    }

    if (i != count) {                  /* failure: free everything decoded so far */
        for (uint16_t j = 0; j <= i && j < count; j++) free(anims[j].pixels);
        free(anims);
        return false;
    }
    out->count = count;
    out->anims = anims;
    return true;
}

bool bwa_bank_read_bytes(const uint8_t *data, int size, bwa_bank *out)
{
    spine_reader r;
    spine_reader_init(&r, data, (size_t)size);
    return bwa_bank_read(&r, out);
}

void bwa_bank_free(bwa_bank *b)
{
    if (!b || !b->anims) return;
    for (uint16_t i = 0; i < b->count; i++) free(b->anims[i].pixels);
    free(b->anims);
    b->anims = NULL;
    b->count = 0;
}

uint16_t bwa_anim_cell_width(const bwa_anim *a)
{
    return a->cells > 0 ? (uint16_t)(a->width / a->cells) : a->width;
}
