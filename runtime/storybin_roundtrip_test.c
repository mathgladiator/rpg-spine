/* storybin_roundtrip_test.c - decode a compiled .storybin and assert its graph,
 * effects, and packed images survived. Usage: storybin_roundtrip_test <file>
 * (driven by runtime/test.sh against demo/assets/stories/vault.storybin). */

#include "storybin.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_fail = 0;
#define CHECK(cond) do { \
    if (!(cond)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); g_fail++; } \
} while (0)

static uint8_t *read_file(const char *path, int *size)
{
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *buf = (uint8_t *)malloc((size_t)n);
    if (buf && fread(buf, 1, (size_t)n, f) != (size_t)n) { free(buf); buf = NULL; }
    fclose(f);
    if (buf) *size = (int)n;
    return buf;
}

int main(int argc, char **argv)
{
    if (argc < 2) { printf("usage: %s <file.storybin>\n", argv[0]); return 2; }
    int size = 0;
    uint8_t *buf = read_file(argv[1], &size);
    if (!buf) { printf("could not read %s\n", argv[1]); return 2; }

    story_t *s = storybin_load(buf, size);
    CHECK(s != NULL);
    if (s) {
        /* vault.story node order: open0 pedestal1 worn2 warned3 dead4 leave5 */
        CHECK(s->start == 0);
        CHECK(s->nnodes == 6);

        /* open: beat with a packed image + intro text, leading to pedestal */
        story_node *open = &s->nodes[0];
        CHECK(open->kind == STORY_BEAT);
        CHECK(open->image != STORY_NONE);
        CHECK(strstr(story_string(s, open->text), "seal") != NULL);
        CHECK(open->next == 1);

        /* pedestal: a 3-way choice */
        CHECK(s->nodes[1].kind == STORY_CHOICE);
        CHECK(s->nodes[1].ndec == 3);

        /* worn: two on-enter effects with stable codes + params */
        story_node *worn = &s->nodes[2];
        CHECK(worn->kind == STORY_BEAT);
        CHECK(worn->nfx == 2);
        if (worn->nfx == 2) {
            CHECK(worn->fx[0].code == 1 && worn->fx[0].param == 1);  /* give_crown(1) */
            CHECK(worn->fx[1].code == 2 && worn->fx[1].param == 0);  /* mark_greedy(0) */
        }

        /* dead: a die outcome that runs kill_player(3) */
        story_node *dead = &s->nodes[4];
        CHECK(dead->kind == STORY_OUTCOME);
        CHECK(dead->result == STORY_DIE);
        CHECK(dead->nfx == 1 && dead->fx[0].code == 3);

        /* leave: a survive outcome with a reward image + resolve_vault(4) */
        story_node *leave = &s->nodes[5];
        CHECK(leave->kind == STORY_OUTCOME);
        CHECK(leave->result == STORY_SURVIVE);
        CHECK(leave->reward != STORY_NONE);
        CHECK(leave->nfx == 1 && leave->fx[0].code == 4);

        /* two full-screen images packed via the bwa codec */
        CHECK(s->images.count == 2);
        const bwa_anim *img = story_image(s, open->image);
        CHECK(img != NULL);
        if (img) { CHECK(img->width == 400); CHECK(img->height == 240); CHECK(img->pixels != NULL); }

        storybin_free(s);
    }

    /* corruption is rejected */
    buf[10] ^= 0xFF;
    CHECK(storybin_load(buf, size) == NULL);
    free(buf);

    if (g_fail == 0) { printf("storybin roundtrip: all tests passed\n"); return 0; }
    printf("storybin roundtrip: %d check(s) FAILED\n", g_fail);
    return 1;
}
