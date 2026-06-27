/* bwa_test.c - unit tests for the reusable C bwa decoder, against hand-built
 * streams (the Java encoder is cross-checked end-to-end by the storybin test).
 * Build: gcc -std=c99 -Wall -Wextra bwa.c spine_runtime.c bwa_test.c -o t && ./t */

#include "bwa.h"

#include <stdio.h>
#include <string.h>

static int g_fail = 0;
#define CHECK(cond) do { \
    if (!(cond)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); g_fail++; } \
} while (0)

/* short run: 0ccnnnnn, count stored as count-1. 5 black = (1<<5)|4 = 0x24. */
static void test_short_run(void)
{
    uint8_t data[] = { 0x24 };
    uint8_t out[5] = {0};
    CHECK(bwa_decode_pixels(data, sizeof(data), out, 5));
    for (int i = 0; i < 5; i++) CHECK(out[i] == BWA_BLACK);
}

/* detail chunk: 1xxxxxxx, 7 mono pixels MSB first. 0xD5 = 1 1010101. */
static void test_detail(void)
{
    uint8_t data[] = { 0xD5 };
    uint8_t out[7] = {0};
    uint8_t want[7] = { BWA_BLACK, BWA_WHITE, BWA_BLACK, BWA_WHITE, BWA_BLACK, BWA_WHITE, BWA_BLACK };
    CHECK(bwa_decode_pixels(data, sizeof(data), out, 7));
    CHECK(memcmp(out, want, 7) == 0);
}

/* long run: 011ccHHH + LLLLLLLL, count-1. 100 white = 0x60, 0x63. */
static void test_long_run(void)
{
    uint8_t data[] = { 0x60, 0x63 };
    uint8_t out[100];
    CHECK(bwa_decode_pixels(data, sizeof(data), out, 100));
    for (int i = 0; i < 100; i++) CHECK(out[i] == BWA_WHITE);
}

/* a stream that decodes to the wrong count is rejected. */
static void test_underrun(void)
{
    uint8_t data[] = { 0x24 };       /* 5 pixels */
    uint8_t out[7];
    CHECK(!bwa_decode_pixels(data, sizeof(data), out, 7));  /* asked for 7 */
}

/* a full 1-anim bank: a 7x1 image encoded as one detail chunk (0xD5). */
static void test_bank(void)
{
    uint8_t bank[] = {
        0x42,             /* magic */
        0x00, 0x01,       /* count = 1 */
        0x00, 0x32,       /* type = 50 (STORY_SCENE) */
        0x00, 0x01,       /* cells = 1 */
        0x00, 0x00,       /* frameTime = 0 */
        0x00,             /* loops = 0 */
        0x00, 0x00, 0x00, 0x07, /* bits = 7 */
        0x00, 0x07,       /* width = 7 */
        0x00, 0x01,       /* height = 1 */
        0x42,             /* checksum (sum of fields & 0xFF == 0x42) */
        0x00, 0x00, 0x00, 0x01, /* byteCount = 1 */
        0xD5              /* the detail chunk */
    };
    bwa_bank b;
    CHECK(bwa_bank_read_bytes(bank, (int)sizeof(bank), &b));
    CHECK(b.count == 1);
    if (b.count == 1) {
        CHECK(b.anims[0].type == 50);
        CHECK(b.anims[0].width == 7);
        CHECK(b.anims[0].height == 1);
        CHECK(bwa_anim_cell_width(&b.anims[0]) == 7);
        uint8_t want[7] = { 1, 0, 1, 0, 1, 0, 1 };
        CHECK(memcmp(b.anims[0].pixels, want, 7) == 0);
    }
    bwa_bank_free(&b);

    /* a corrupted checksum byte is rejected. */
    bank[18] ^= 0xFF;
    bwa_bank b2;
    CHECK(!bwa_bank_read_bytes(bank, (int)sizeof(bank), &b2));
}

int main(void)
{
    test_short_run();
    test_detail();
    test_long_run();
    test_underrun();
    test_bank();
    if (g_fail == 0) { printf("bwa: all tests passed\n"); return 0; }
    printf("bwa: %d check(s) FAILED\n", g_fail);
    return 1;
}
