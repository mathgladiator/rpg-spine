/* ============================================================================
 * storybin.h - decode a compiled .storybin into a story graph.
 * ============================================================================
 *
 * A .storybin (documents/STORYBIN_FORMAT.md) is a whole compiled .story plus all
 * its images in one file: integer node/edge/effect records, an interned string
 * pool, and an embedded .bwa image bank. This decoder turns the bytes into a
 * story_t the story system walks; images arrive already decoded to pixel buffers
 * via the reusable bwa codec.
 *
 * Effects are stored by their stable dispatch code; the runtime applies them with
 * the generated story_effect(doc, code, param) (spine_effects.gen.c).
 *
 * Depends only on spine_runtime + bwa, so it is portable to every backend.
 * ========================================================================== */

#ifndef STORYBIN_H
#define STORYBIN_H

#include <stdbool.h>
#include <stdint.h>

#include "bwa.h"

#ifdef __cplusplus
extern "C" {
#endif

/* sentinel for "no string / no image / no target". */
#define STORY_NONE 0xFFFF

enum { STORY_BEAT = 0, STORY_CHOICE = 1, STORY_OUTCOME = 2 };
enum { STORY_SURVIVE = 0, STORY_DIE = 1 };

/* one on-enter effect: a stable dispatch code + parameter. */
typedef struct story_fx {
    uint16_t code;
    int32_t  param;
} story_fx;

/* one choice decision: a label string + target node (STORY_NONE = fall through). */
typedef struct story_decision {
    uint16_t label;
    uint16_t to;
} story_decision;

/* a node. Fields not used by a node's kind are STORY_NONE / 0. */
typedef struct story_node {
    uint8_t   kind;          /* STORY_BEAT / STORY_CHOICE / STORY_OUTCOME */
    uint8_t   nfx;
    story_fx *fx;            /* nfx on-enter effects, applied in order */

    uint16_t text;           /* beat/choice: string index */
    uint16_t image;          /* beat: bank anim index */
    uint16_t next;           /* beat/choice: forward / fall-through node index */

    uint8_t         ndec;    /* choice: decision count */
    story_decision *decisions;

    uint8_t  result;         /* outcome: STORY_SURVIVE / STORY_DIE */
    uint16_t reason;         /* outcome: string index */
    uint16_t reward;         /* outcome: bank anim index */
} story_node;

typedef struct story_t {
    uint16_t   start;        /* start node index (STORY_NONE if none) */
    bwa_bank   images;       /* embedded image bank, decoded */
    uint16_t   nstrings;
    char     **strings;      /* NUL-terminated UTF-8 */
    uint16_t   nnodes;
    story_node *nodes;
} story_t;

/* Decode a .storybin. Returns NULL on any error (bad magic/version/crc, truncation,
 * malformed bank). The caller owns the result and frees it with storybin_free. */
story_t *storybin_load(const uint8_t *data, int size);
void     storybin_free(story_t *s);

/* the interned string at idx, or "" for STORY_NONE / out of range. */
const char *story_string(const story_t *s, uint16_t idx);
/* the bank image at idx, or NULL for STORY_NONE / out of range. */
const bwa_anim *story_image(const story_t *s, uint16_t idx);

#ifdef __cplusplus
}
#endif

#endif /* STORYBIN_H */
