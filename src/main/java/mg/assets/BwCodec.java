package mg.assets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * The single-byte run-length codec for black / white / transparent pixel
 * streams. Every byte is meaningful and self-describing by its top bit:
 *
 * <ul>
 *   <li><b>Detail chunk</b> ({@code 1xxxxxxx}) — the top bit is 1 and the low 7
 *       bits are the next 7 pixels, each {@code 1 = black} / {@code 0 = white}
 *       (most-significant first). Highly detailed black-and-white art packs at
 *       7 pixels per byte. Detail chunks cannot carry transparency.
 *   <li><b>Short run</b> ({@code 0ccnnnnn}, {@code cc} &ne; {@code 11}) — the top
 *       bit is 0, the next two bits {@code cc} are the colour ({@link Mono#WHITE
 *       0}/{@link Mono#BLACK 1}/{@link Mono#TRANSPARENT 2}), and the low five bits
 *       {@code nnnnn} hold the run count minus one, so they cover 1&ndash;32 of
 *       that colour (the otherwise-dead "run of 0" code is reclaimed).
 *   <li><b>Long run</b> ({@code 011ccHHH} + {@code LLLLLLLL}) — when the colour
 *       field is the otherwise-unused {@code 11}, this is a two-byte run: the real
 *       colour {@code cc} sits in bits 4&ndash;3 and an 11-bit (count minus one)
 *       value ({@code HHH} high, {@code LLLLLLLL} low) covers 1&ndash;2048, so a
 *       flat fill or a transparent background collapses to two bytes instead of
 *       one byte per 32 pixels. This is the big win for background-heavy sprites.
 * </ul>
 *
 * <p>Pixels are the tri-state {@link Mono} values (0/1/2), scanned row-major
 * (left-to-right, top-to-bottom). The encoder is optimal for this byte alphabet:
 * a linear dynamic program picks, at every position, the cheaper of "emit a
 * maximal run" versus "emit a 7-pixel detail chunk", so {@link #encodedSize} is
 * the true minimum byte count for this scheme — exactly what the editor shows
 * next to the PNG size.
 */
public final class BwCodec {

  private BwCodec() {}

  /** row-major tri-state pixels ({@link Mono#WHITE}/{@code BLACK}/{@code TRANSPARENT}) of an image. */
  public static int[] pixelsOf(BufferedImage img) {
    int w = img.getWidth();
    int h = img.getHeight();
    int[] px = new int[w * h];
    int i = 0;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        px[i++] = Mono.state(img, x, y);
      }
    }
    return px;
  }

  /** rebuild a {@link Mono} 1-bit image from a row-major tri-state pixel stream. */
  public static BufferedImage toImage(int[] px, int width, int height) {
    if (px.length != width * height) {
      throw new IllegalArgumentException("pixel count " + px.length + " != " + width + "x" + height);
    }
    BufferedImage img = Mono.newBinary(width, height);
    int i = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        Mono.setState(img, x, y, px[i++]);
      }
    }
    return img;
  }

  /**
   * Encode a tri-state pixel stream to the single-byte RLE/detail format. The
   * result is the minimal byte sequence for this scheme (see class docs).
   */
  public static byte[] encode(int[] px) {
    int n = px.length;
    int[] dp = new int[n + 1];
    int[] take = new int[n + 1];
    boolean[] detail = new boolean[n + 1];
    // `run` is the maximal same-colour run starting at i, capped at 2048 (the most
    // any code can consume). Computed incrementally from i+1 — O(n), not O(n^2).
    int run = 0;
    for (int i = n - 1; i >= 0; i--) {
      run = (i + 1 < n && px[i] == px[i + 1]) ? Math.min(run + 1, 2048) : 1;
      int rleTake = Math.min(run, 32); // 5-bit count stored as take-1, so 1..32
      int best = 1 + dp[i + rleTake];
      int bestTake = rleTake;
      boolean bestDetail = false;
      // a long run (2 bytes) pays for itself once it beats two short runs
      int longTake = run; // already capped to 2048 (11-bit count stored as take-1)
      if (longTake > 32) {
        int cost = 2 + dp[i + longTake];
        if (cost < best) {
          best = cost;
          bestTake = longTake;
          bestDetail = false;
        }
      }
      // a detail chunk is exactly 7 non-transparent pixels
      if (i + 7 <= n) {
        boolean ok = true;
        for (int k = 0; k < 7; k++) {
          if (px[i + k] == Mono.TRANSPARENT) {
            ok = false;
            break;
          }
        }
        if (ok) {
          int cost = 1 + dp[i + 7];
          if (cost < best) {
            best = cost;
            bestTake = 7;
            bestDetail = true;
          }
        }
      }
      dp[i] = best;
      take[i] = bestTake;
      detail[i] = bestDetail;
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream(dp.length == 0 ? 0 : dp[0]);
    int i = 0;
    while (i < n) {
      if (detail[i]) {
        int b = 0x80;
        for (int k = 0; k < 7; k++) {
          if (px[i + k] == Mono.BLACK) {
            b |= 1 << (6 - k);
          }
        }
        out.write(b);
        i += 7;
      } else {
        int c = px[i];
        int t = take[i];
        if (t > 32) {
          // long run: 011 marker, colour in bits 4-3, then take-1 as an 11-bit count
          int v = t - 1;
          out.write(0x60 | (c << 3) | ((v >> 8) & 0x7));
          out.write(v & 0xFF);
        } else {
          out.write((c << 5) | (t - 1)); // top bit 0, colour in bits 6-5, count-1 in bits 4-0
        }
        i += t;
      }
    }
    return out.toByteArray();
  }

  /** the encoded size in bytes for this pixel stream, without allocating the bytes. */
  public static int encodedSize(int[] px) {
    int n = px.length;
    int[] dp = new int[n + 1];
    int run = 0; // maximal same-colour run starting at i, capped at 2048; O(n) overall
    for (int i = n - 1; i >= 0; i--) {
      run = (i + 1 < n && px[i] == px[i + 1]) ? Math.min(run + 1, 2048) : 1;
      int best = 1 + dp[i + Math.min(run, 32)];
      int longTake = run;
      if (longTake > 32) {
        best = Math.min(best, 2 + dp[i + longTake]);
      }
      if (i + 7 <= n) {
        boolean ok = true;
        for (int k = 0; k < 7; k++) {
          if (px[i + k] == Mono.TRANSPARENT) {
            ok = false;
            break;
          }
        }
        if (ok) {
          best = Math.min(best, 1 + dp[i + 7]);
        }
      }
      dp[i] = best;
    }
    return n == 0 ? 0 : dp[0];
  }

  /** decode exactly {@code bits} pixels from an encoded byte stream. */
  public static int[] decode(byte[] data, int bits) {
    int[] px = new int[bits];
    int p = 0;
    int idx = 0;
    while (idx < data.length) {
      int b = data[idx++] & 0xFF;
      int c;
      int t;
      if ((b & 0x80) != 0) {
        // detail chunk: 7 black/white pixels, MSB first
        for (int k = 0; k < 7; k++) {
          if (p >= bits) {
            throw new IllegalArgumentException("detail chunk overruns " + bits + " pixels");
          }
          px[p++] = ((b >> (6 - k)) & 1) == 1 ? Mono.BLACK : Mono.WHITE;
        }
        continue;
      } else if ((b & 0x60) == 0x60) {
        // long run: colour in bits 4-3, 11-bit count (stored as count-1) across two bytes
        if (idx >= data.length) {
          throw new IllegalArgumentException("truncated long run");
        }
        c = (b >> 3) & 0x3;
        t = (((b & 0x7) << 8) | (data[idx++] & 0xFF)) + 1;
      } else {
        // short run: colour in bits 6-5, 5-bit count (stored as count-1)
        c = (b >> 5) & 0x3;
        t = (b & 0x1F) + 1;
      }
      for (int j = 0; j < t; j++) {
        if (p >= bits) {
          throw new IllegalArgumentException("run chunk overruns " + bits + " pixels");
        }
        px[p++] = c;
      }
    }
    if (p != bits) {
      throw new IllegalArgumentException("decoded " + p + " pixels, expected " + bits);
    }
    return px;
  }
}
