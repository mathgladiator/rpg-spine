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

Pixels are scanned row-major. Each byte is self-describing by its top bit:

- **Detail chunk** `1xxxxxxx` — the low 7 bits are the next 7 pixels, MSB first,
  `1 = black` / `0 = white`. 7 pixels/byte; cannot carry transparency.
- **Run chunk** `0ccnnnnn` — `cc` is the colour (`0` white, `1` black,
  `2` transparent; `3` reserved) and `nnnnn` is a run of `1..31` of that colour.

The encoder is **optimal for this alphabet**: a linear DP picks, at each pixel,
the cheaper of "maximal run" vs "7-pixel detail chunk". `BwCodec.encodedSize`
returns that true minimum, which the B&W image editor shows in its status bar
(`rle N B / png N B`) so you can compare the encoded size against the PNG before
committing art.
