/* spine_gen_roundtrip_test.c - exercises GENERATED load/save against the demo
 * schema. Not standalone: the codegen output dir must be on the include path and
 * its spine.gen.c + spine_runtime.c compiled in. Driven by runtime/roundtrip.sh. */

#include "spine.gen.h"
#include "spine_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_fail = 0;
#define CHECK(cond) do { \
    if (!(cond)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); g_fail++; } \
} while (0)

int main(void)
{
    /* Build a document with a couple of party members and a score. */
    SPINE *doc = spine_new();
    CHECK(doc != NULL);
    doc->score = -123456789012345LL;
    doc->party_count = 2;
    doc->party = (Character *)calloc(2, sizeof(Character));
    doc->party[0].xp = 100; doc->party[0].hp = 30; doc->party[0].max_hp = 30;
    doc->party[0].mp = 5;   doc->party[0].max_mp = 5;
    doc->party[1].xp = 250; doc->party[1].hp = 18; doc->party[1].max_hp = 42;
    doc->party[1].mp = 12;  doc->party[1].max_mp = 16;

    /* Save it. */
    int size = 0;
    uint8_t *blob = spine_save(doc, &size);
    CHECK(blob != NULL);
    CHECK(size > 11);
    /* magic + version up front. */
    CHECK(blob[0] == 'S' && blob[1] == 'P' && blob[2] == 'N' && blob[3] == '1');
    CHECK(blob[4] == 0x00 && blob[5] == 0x01); /* format version 1, big-endian */

    /* Load it back and compare every field. */
    SPINE *back = spine_load(blob, size);
    CHECK(back != NULL);
    if (back) {
        CHECK(back->score == doc->score);
        CHECK(back->party_count == 2);
        for (uint32_t i = 0; i < 2; ++i) {
            CHECK(back->party[i].xp == doc->party[i].xp);
            CHECK(back->party[i].hp == doc->party[i].hp);
            CHECK(back->party[i].max_hp == doc->party[i].max_hp);
            CHECK(back->party[i].mp == doc->party[i].mp);
            CHECK(back->party[i].max_mp == doc->party[i].max_mp);
        }
    }

    /* Corruption is rejected (flip a payload byte -> crc mismatch -> NULL). */
    uint8_t *bad = (uint8_t *)malloc((size_t)size);
    memcpy(bad, blob, (size_t)size);
    bad[8] ^= 0xFF;
    CHECK(spine_load(bad, size) == NULL);

    /* A truncated blob is rejected, not a crash. */
    CHECK(spine_load(blob, size - 1) == NULL);
    CHECK(spine_load(blob, 3) == NULL);

    /* An empty document round-trips too. */
    SPINE *empty = spine_new();
    int esize = 0;
    uint8_t *eblob = spine_save(empty, &esize);
    CHECK(eblob != NULL);
    SPINE *eback = spine_load(eblob, esize);
    CHECK(eback != NULL);
    if (eback) { CHECK(eback->party_count == 0); CHECK(eback->score == 0); }

    free(bad);
    free(blob);
    free(eblob);
    spine_free(doc);
    spine_free(back);
    spine_free(empty);
    spine_free(eback);

    if (g_fail == 0) { printf("spine_gen roundtrip: all tests passed\n"); return 0; }
    printf("spine_gen roundtrip: %d check(s) FAILED\n", g_fail);
    return 1;
}
