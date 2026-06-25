# `.bwa` — the black/white/transparent animation bank

A single, compact, Playdate-friendly file that holds **every** animation a
monster or item owns (battle stance, damage, attack, the four dungeon idle/walk
orientations, item icons, item usage). A still image is just a one-cell
animation. Implemented by `mg.assets.BwAnimBank` (container), `mg.assets.AnimType`
(the role codes), and `mg.assets.BwCodec` (the pixel codec).

## Why

Each frame is black / white / **transparent** (the project-wide `Mono` palette).
Hand-drawn 1-bit art is mostly flat fills with bursts of fine detail, so a
single-byte run-length scheme compresses it hard while staying trivial for the
eventual C ingest on the Playdate — one byte at a time, no tables.

## Binary layout (big-endian)

```
u8   magic            = 0x42
u16  animationCount
animationCount × {
  u16  type            AnimType code (stable; unknown codes still round-trip)
  u16  cells           frames packed into the strip
  u16  frameTime       ms per frame (0 = a still)
  u8   loops           0 = loop forever
  u32  bits            == width * height   (integrity check)
  u16  width           full strip width  (cellWidth = width / cells)
  u16  height
  u8   checksum        (sum of the 7 fields above) & 0xFF
  u32  byteCount       length of the encoded stream that follows
  u8[byteCount] data   BwCodec stream decoding to exactly `bits` pixels
}
```

The image is the whole animation as a horizontal strip of `cells` equal frames.
Reading validates: magic, `bits == width*height`, the per-animation checksum, and
that the stream decodes to exactly `bits` pixels.

## Pixel codec (`BwCodec`)

Pixels are scanned row-major. Each byte is self-describing:

- **Detail chunk** `1xxxxxxx` — the low 7 bits are the next 7 pixels, MSB first,
  `1 = black` / `0 = white`. 7 pixels/byte; cannot carry transparency.
- **Short run** `0ccnnnnn` (`cc` ≠ `11`) — `cc` is the colour (`0` white, `1`
  black, `2` transparent) and `nnnnn` holds the count **minus one**, so it covers
  `1..32` of that colour (the otherwise-dead "run of 0" code is reclaimed).
- **Long run** `011ccHHH` + `LLLLLLLL` — when the colour field is the otherwise
  unused `11`, this is a two-byte run: the real colour is `cc` (bits 4–3) and the
  11-bit `HHH:LLLLLLLL` holds the count **minus one** (covers `1..2048`). Because
  pixels are row-major, a flat fill or transparent background is one contiguous
  run, so this collapses it to two bytes instead of one byte per 32 pixels — the
  main win for background-heavy sprites.

The encoder is **optimal for this alphabet**: a linear DP picks, at each pixel,
the cheapest of short run / long run / 7-pixel detail chunk. `BwCodec.encodedSize`
returns that true minimum, shown next to the PNG size in the B&W image editor's
status bar and in the **Extract region → black & white** dialog
(`format N B (X% of png)`) so you can confirm the format is a win before saving.

## C decoder (Playdate)

The whole codec is one branch per byte — no tables, no allocation, no libraries:

```c
// decode `bits` pixels from data[] into out[] (0=white, 1=black, 2=transparent)
size_t i = 0, p = 0;
while (i < len) {
  uint8_t b = data[i++];
  if (b & 0x80) {                       // detail: 7 mono pixels, MSB first
    for (int k = 0; k < 7; k++) out[p++] = (b >> (6 - k)) & 1;
  } else if ((b & 0x60) == 0x60) {      // long run (count stored as count-1)
    uint8_t c = (b >> 3) & 3;
    int n = (((b & 7) << 8) | data[i++]) + 1;
    while (n--) out[p++] = c;
  } else {                              // short run (count stored as count-1)
    uint8_t c = (b >> 5) & 3;
    int n = (b & 0x1F) + 1;
    while (n--) out[p++] = c;
  }
}
```
