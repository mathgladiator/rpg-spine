/* spine_runtime_test.c - unit tests for the hand-written runtime primitives.
 * Build: gcc -std=c99 -Wall -Wextra spine_runtime.c spine_runtime_test.c -o t && ./t */

#include "spine_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_fail = 0;
#define CHECK(cond) do { \
    if (!(cond)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); g_fail++; } \
} while (0)

/* Every scalar primitive round-trips through writer -> reader, big-endian. */
static void test_scalar_roundtrip(void)
{
    spine_writer w; spine_writer_init(&w);
    spine_write_bool(&w, true);
    spine_write_int8(&w, -42);
    spine_write_uint8(&w, 200);
    spine_write_int16(&w, -12345);
    spine_write_uint16(&w, 54321);
    spine_write_int32(&w, -2000000000);
    spine_write_uint32(&w, 4000000000u);
    spine_write_int64(&w, -9000000000000000000LL);
    spine_write_uint64(&w, 18000000000000000000ULL);
    CHECK(spine_writer_ok(&w));

    int size = 0;
    uint8_t *buf = spine_writer_take(&w, &size);
    CHECK(buf != NULL);
    CHECK(size == 1 + 1 + 1 + 2 + 2 + 4 + 4 + 8 + 8);

    spine_reader r; spine_reader_init(&r, buf, (size_t)size);
    CHECK(spine_read_bool(&r) == true);
    CHECK(spine_read_int8(&r) == -42);
    CHECK(spine_read_uint8(&r) == 200);
    CHECK(spine_read_int16(&r) == -12345);
    CHECK(spine_read_uint16(&r) == 54321);
    CHECK(spine_read_int32(&r) == -2000000000);
    CHECK(spine_read_uint32(&r) == 4000000000u);
    CHECK(spine_read_int64(&r) == -9000000000000000000LL);
    CHECK(spine_read_uint64(&r) == 18000000000000000000ULL);
    CHECK(spine_reader_ok(&r));
    CHECK(spine_reader_remaining(&r) == 0);
    free(buf);
}

/* Big-endian byte order is exact on the wire. */
static void test_big_endian(void)
{
    spine_writer w; spine_writer_init(&w);
    spine_write_uint32(&w, 0x01020304u);
    int size = 0;
    uint8_t *buf = spine_writer_take(&w, &size);
    CHECK(size == 4);
    CHECK(buf[0] == 0x01 && buf[1] == 0x02 && buf[2] == 0x03 && buf[3] == 0x04);
    free(buf);
}

/* Reading past the end sets error and returns 0, and error is sticky. */
static void test_reader_overrun(void)
{
    uint8_t data[1] = { 0xAB };
    spine_reader r; spine_reader_init(&r, data, 1);
    CHECK(spine_read_uint8(&r) == 0xAB);
    CHECK(spine_reader_ok(&r));
    CHECK(spine_read_uint32(&r) == 0);   /* only 0 bytes left */
    CHECK(!spine_reader_ok(&r));
    CHECK(spine_read_uint8(&r) == 0);    /* sticky */
    CHECK(!spine_reader_ok(&r));
}

/* bytes blobs round-trip. */
static void test_bytes(void)
{
    const char *msg = "hello spine";
    size_t n = strlen(msg);
    spine_writer w; spine_writer_init(&w);
    spine_write_bytes(&w, msg, n);
    int size = 0;
    uint8_t *buf = spine_writer_take(&w, &size);
    CHECK((size_t)size == n);
    char out[32] = {0};
    spine_reader r; spine_reader_init(&r, buf, (size_t)size);
    spine_read_bytes(&r, out, n);
    CHECK(spine_reader_ok(&r));
    CHECK(memcmp(out, msg, n) == 0);
    free(buf);
}

/* skip_value advances exactly the width implied by the op. */
static void test_skip_value(void)
{
    spine_writer w; spine_writer_init(&w);
    spine_write_int32(&w, 777);
    spine_write_int16(&w, 9);
    int size = 0;
    uint8_t *buf = spine_writer_take(&w, &size);
    spine_reader r; spine_reader_init(&r, buf, (size_t)size);
    spine_skip_value(&r, OP_INT32);          /* skip the 4-byte value */
    CHECK(spine_reader_ok(&r));
    CHECK(spine_read_int16(&r) == 9);        /* land exactly on the next value */
    CHECK(spine_reader_ok(&r));
    free(buf);
}

/* crc32 matches the known IEEE check value for "123456789". */
static void test_crc32(void)
{
    const char *s = "123456789";
    CHECK(spine_crc32((const uint8_t *)s, strlen(s)) == 0xCBF43926u);
    CHECK(spine_crc32((const uint8_t *)"", 0) == 0x00000000u);
}

int main(void)
{
    test_scalar_roundtrip();
    test_big_endian();
    test_reader_overrun();
    test_bytes();
    test_skip_value();
    test_crc32();
    if (g_fail == 0) { printf("spine_runtime: all tests passed\n"); return 0; }
    printf("spine_runtime: %d check(s) FAILED\n", g_fail);
    return 1;
}
