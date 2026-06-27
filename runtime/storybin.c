/* storybin.c - see storybin.h. Decodes the .storybin container with the
 * spine_runtime reader and the bwa codec. */

#include "storybin.h"

#include "spine_runtime.h"

#include <stdlib.h>
#include <string.h>

/* "STB1" */
#define STB_MAGIC0 0x53
#define STB_MAGIC1 0x54
#define STB_MAGIC2 0x42
#define STB_MAGIC3 0x31
#define STB_VERSION 1u

story_t *storybin_load(const uint8_t *data, int size)
{
    if (!data || size < 4 + 2 + 2 + 4 + 2 + 2 + 4) return NULL;

    /* verify the trailing crc32 over everything before it. */
    uint32_t stored = (uint32_t)data[size - 4] << 24 | (uint32_t)data[size - 3] << 16
                    | (uint32_t)data[size - 2] << 8  | (uint32_t)data[size - 1];
    if (spine_crc32(data, (size_t)size - 4) != stored) return NULL;

    spine_reader r;
    spine_reader_init(&r, data, (size_t)size);
    if (spine_read_uint8(&r) != STB_MAGIC0 || spine_read_uint8(&r) != STB_MAGIC1
        || spine_read_uint8(&r) != STB_MAGIC2 || spine_read_uint8(&r) != STB_MAGIC3) return NULL;
    if (spine_read_uint16(&r) != STB_VERSION) return NULL;

    story_t *s = (story_t *)calloc(1, sizeof(story_t));
    if (!s) return NULL;

    s->start = spine_read_uint16(&r);

    /* embedded image bank: decode from a sub-view, then skip past it. */
    uint32_t bankLen = spine_read_uint32(&r);
    if (!spine_reader_ok(&r) || bankLen > spine_reader_remaining(&r)) goto fail;
    if (!bwa_bank_read_bytes(r.buf + r.pos, (int)bankLen, &s->images)) goto fail;
    r.pos += bankLen;

    /* string pool */
    s->nstrings = spine_read_uint16(&r);
    if (!spine_reader_ok(&r)) goto fail;
    if (s->nstrings) {
        s->strings = (char **)calloc(s->nstrings, sizeof(char *));
        if (!s->strings) goto fail;
    }
    for (uint16_t i = 0; i < s->nstrings; i++) {
        uint16_t len = spine_read_uint16(&r);
        if (!spine_reader_ok(&r) || len > spine_reader_remaining(&r)) goto fail;
        char *str = (char *)malloc((size_t)len + 1);
        if (!str) goto fail;
        spine_read_bytes(&r, str, len);
        str[len] = '\0';
        s->strings[i] = str;
    }

    /* nodes */
    s->nnodes = spine_read_uint16(&r);
    if (!spine_reader_ok(&r)) goto fail;
    if (s->nnodes) {
        s->nodes = (story_node *)calloc(s->nnodes, sizeof(story_node));
        if (!s->nodes) goto fail;
    }
    for (uint16_t i = 0; i < s->nnodes; i++) {
        story_node *n = &s->nodes[i];
        n->text = n->image = n->next = n->reason = n->reward = STORY_NONE;
        n->kind = spine_read_uint8(&r);
        n->nfx  = spine_read_uint8(&r);
        if (!spine_reader_ok(&r)) goto fail;
        if (n->nfx) {
            n->fx = (story_fx *)calloc(n->nfx, sizeof(story_fx));
            if (!n->fx) goto fail;
            for (uint8_t k = 0; k < n->nfx; k++) {
                n->fx[k].code  = spine_read_uint16(&r);
                n->fx[k].param = spine_read_int32(&r);
            }
        }
        switch (n->kind) {
        case STORY_BEAT:
            n->text  = spine_read_uint16(&r);
            n->image = spine_read_uint16(&r);
            n->next  = spine_read_uint16(&r);
            break;
        case STORY_CHOICE:
            n->text = spine_read_uint16(&r);
            n->next = spine_read_uint16(&r);
            n->ndec = spine_read_uint8(&r);
            if (!spine_reader_ok(&r)) goto fail;
            if (n->ndec) {
                n->decisions = (story_decision *)calloc(n->ndec, sizeof(story_decision));
                if (!n->decisions) goto fail;
                for (uint8_t k = 0; k < n->ndec; k++) {
                    n->decisions[k].label = spine_read_uint16(&r);
                    n->decisions[k].to    = spine_read_uint16(&r);
                }
            }
            break;
        case STORY_OUTCOME:
            n->result = spine_read_uint8(&r);
            n->reason = spine_read_uint16(&r);
            n->reward = spine_read_uint16(&r);
            break;
        default:
            goto fail; /* unknown node kind */
        }
        if (!spine_reader_ok(&r)) goto fail;
    }

    return s;

fail:
    storybin_free(s);
    return NULL;
}

void storybin_free(story_t *s)
{
    if (!s) return;
    if (s->strings) {
        for (uint16_t i = 0; i < s->nstrings; i++) free(s->strings[i]);
        free(s->strings);
    }
    if (s->nodes) {
        for (uint16_t i = 0; i < s->nnodes; i++) {
            free(s->nodes[i].fx);
            free(s->nodes[i].decisions);
        }
        free(s->nodes);
    }
    bwa_bank_free(&s->images);
    free(s);
}

const char *story_string(const story_t *s, uint16_t idx)
{
    if (idx == STORY_NONE || idx >= s->nstrings || !s->strings) return "";
    return s->strings[idx];
}

const bwa_anim *story_image(const story_t *s, uint16_t idx)
{
    if (idx == STORY_NONE || idx >= s->images.count) return NULL;
    return &s->images.anims[idx];
}
