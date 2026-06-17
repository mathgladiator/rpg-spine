package mg.assets;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * The black-and-white image core of the asset pipeline. Every image in this
 * project is a 1-bit PNG (black or white, no grey) because the target is the
 * Playdate's 1-bit display. This class is the single chokepoint that enforces
 * that rule: it produces and consumes {@code TYPE_BYTE_BINARY} {@link
 * BufferedImage}s backed by a two-entry {black, white} palette, so anything it
 * writes is a genuine 1-bit PNG.
 *
 * <p>Pixels are addressed as booleans: {@code true} = black (ink), {@code false}
 * = white (paper). Index 0 in the palette is white, index 1 is black, so a
 * freshly allocated image is blank paper.
 *
 * <h2>Resize algorithm (documented contract)</h2>
 * Downscaling 1-bit art cannot blend, so we reduce by integer factors using a
 * 2&times;2 box vote, which is exact, deterministic, and reversible to reason
 * about:
 * <ul>
 *   <li>{@link #reduceHalf} maps every non-overlapping 2&times;2 block of the
 *       source to one output pixel. Count the black pixels in the block
 *       (0&ndash;4); the output is black iff that count is &ge; {@code threshold}.
 *   <li>{@code threshold} selects the visual intent:
 *     <ul>
 *       <li>{@link #PRESERVE_INK} (1): output black if <em>any</em> source pixel
 *           is black — keeps thin lines and silhouettes, thickens slightly.
 *       <li>{@link #MAJORITY} (2): output black on a tie or majority — the
 *           balanced, density-preserving default.
 *       <li>{@link #PRESERVE_PAPER} (3): output black only if a strong majority
 *           is black — thins ink, keeps highlights.
 *     </ul>
 *   <li>{@link #reduceQuarter} is exactly {@code reduceHalf(reduceHalf(img))}
 *       with the same threshold, so quarter-size is two well-defined halvings,
 *       not a separate algorithm.
 * </ul>
 * Odd source dimensions are handled by treating out-of-bounds pixels as white
 * (paper), so a block on the right/bottom edge votes only with the pixels that
 * exist. Authoring at sizes divisible by 4 (e.g. 48&rarr;24&rarr;12) avoids edge
 * cases entirely.
 */
public final class Mono {

  /** reduceHalf threshold: any black source pixel -> black (keeps thin ink). */
  public static final int PRESERVE_INK = 1;
  /** reduceHalf threshold: tie or majority black -> black (balanced default). */
  public static final int MAJORITY = 2;
  /** reduceHalf threshold: strong majority black -> black (thins ink). */
  public static final int PRESERVE_PAPER = 3;

  /** pixel states. */
  public static final int WHITE = 0;
  public static final int BLACK = 1;
  public static final int TRANSPARENT = 2;

  /**
   * The shared palette: index 0 = white (paper, opaque), 1 = black (ink, opaque),
   * 2 = transparent (alpha 0). Transparent is a real PNG-writable alpha entry; the
   * editor renders it green, but on disk it is genuinely transparent.
   */
  private static final IndexColorModel PALETTE = new IndexColorModel(
      2, 3,
      new byte[] {(byte) 255, 0, 0},          // reds
      new byte[] {(byte) 255, 0, 0},          // greens
      new byte[] {(byte) 255, 0, 0},          // blues
      new byte[] {(byte) 255, (byte) 255, 0}); // alpha: white/black opaque, transparent 0

  private Mono() {}

  // --------------------------------------------------------------- construction

  /** a new 1-bit image, all white (paper) or all black. */
  public static BufferedImage blank(int width, int height, boolean black) {
    BufferedImage img = newBinary(width, height);
    if (black) {
      WritableRaster r = img.getRaster();
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          r.setSample(x, y, 0, BLACK);
        }
      }
    }
    return img; // default samples are 0 = white (paper)
  }

  /** a fresh black/white/transparent indexed image on the shared palette. */
  public static BufferedImage newBinary(int width, int height) {
    return new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, PALETTE);
  }

  /** an independent deep copy of an image (for undo snapshots), type preserved. */
  public static BufferedImage copy(BufferedImage src) {
    return new BufferedImage(
        src.getColorModel(),
        src.copyData(null),
        src.getColorModel().isAlphaPremultiplied(),
        null);
  }

  // ------------------------------------------------------------- pixel accessors

  /** the state of a pixel: {@link #WHITE} / {@link #BLACK} / {@link #TRANSPARENT}.
   *  Out-of-bounds reads as white (paper). */
  public static int state(BufferedImage img, int x, int y) {
    if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
      return WHITE;
    }
    return img.getRaster().getSample(x, y, 0);
  }

  /** set a pixel's state; silently ignores out-of-bounds. */
  public static void setState(BufferedImage img, int x, int y, int state) {
    if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
      return;
    }
    img.getRaster().setSample(x, y, 0, state);
  }

  /** true if the pixel is black (ink). Out-of-bounds reads as white (paper). */
  public static boolean isBlack(BufferedImage img, int x, int y) {
    return state(img, x, y) == BLACK;
  }

  /** true if the pixel is transparent. */
  public static boolean isTransparent(BufferedImage img, int x, int y) {
    return state(img, x, y) == TRANSPARENT;
  }

  /** set a pixel black (ink) or white (paper); silently ignores out-of-bounds. */
  public static void set(BufferedImage img, int x, int y, boolean black) {
    setState(img, x, y, black ? BLACK : WHITE);
  }

  /** set a pixel transparent; silently ignores out-of-bounds. */
  public static void setTransparent(BufferedImage img, int x, int y) {
    setState(img, x, y, TRANSPARENT);
  }

  // ----------------------------------------------------------- 1-bit conversion

  /**
   * Convert any image to 1-bit by a hard luminance threshold. {@code level} is
   * 0&ndash;255: a pixel becomes black when its perceived luminance is below it
   * ({@code 128} is a sensible middle). No dithering — clean, flat areas.
   */
  public static BufferedImage toMonoThreshold(BufferedImage src, int level) {
    BufferedImage out = newBinary(src.getWidth(), src.getHeight());
    for (int y = 0; y < src.getHeight(); y++) {
      for (int x = 0; x < src.getWidth(); x++) {
        set(out, x, y, luminance(src.getRGB(x, y)) < level);
      }
    }
    return out;
  }

  /**
   * Convert any image to 1-bit using an ordered 4&times;4 Bayer dither, which
   * approximates greys as black/white patterns the way the Playdate SDK expects.
   * Preferred for shaded source art (e.g. Meshy renders) before hand cleanup.
   */
  public static BufferedImage toMonoDither(BufferedImage src) {
    BufferedImage out = newBinary(src.getWidth(), src.getHeight());
    for (int y = 0; y < src.getHeight(); y++) {
      for (int x = 0; x < src.getWidth(); x++) {
        int lum = luminance(src.getRGB(x, y));
        // BAYER_4X4 is 0..15; scale to a 0..255 threshold centred per cell.
        int t = (BAYER_4X4[y & 3][x & 3] * 16) + 8;
        set(out, x, y, lum < t);
      }
    }
    return out;
  }

  /** perceived luminance (0..255) of a packed ARGB pixel. */
  private static int luminance(int argb) {
    int r = (argb >> 16) & 0xFF;
    int g = (argb >> 8) & 0xFF;
    int b = argb & 0xFF;
    return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
  }

  private static final int[][] BAYER_4X4 = {
      {0, 8, 2, 10},
      {12, 4, 14, 6},
      {3, 11, 1, 9},
      {15, 7, 13, 5},
  };

  // ------------------------------------------------------------------- resizing

  /** halve both dimensions with a 2x2 box vote (see class docs / {@link #MAJORITY}). */
  public static BufferedImage reduceHalf(BufferedImage src, int threshold) {
    int w = (src.getWidth() + 1) / 2;
    int h = (src.getHeight() + 1) / 2;
    BufferedImage out = newBinary(w, h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int sx = x * 2;
        int sy = y * 2;
        int blacks = (isBlack(src, sx, sy) ? 1 : 0)
            + (isBlack(src, sx + 1, sy) ? 1 : 0)
            + (isBlack(src, sx, sy + 1) ? 1 : 0)
            + (isBlack(src, sx + 1, sy + 1) ? 1 : 0);
        set(out, x, y, blacks >= threshold);
      }
    }
    return out;
  }

  /** quarter-size = two successive {@link #reduceHalf} passes at one threshold. */
  public static BufferedImage reduceQuarter(BufferedImage src, int threshold) {
    return reduceHalf(reduceHalf(src, threshold), threshold);
  }

  /**
   * Crop away a uniform margin: {@code trimBlack=false} removes surrounding white
   * (keeps the black content's bounding box), {@code trimBlack=true} removes
   * surrounding black. Returns a 1&times;1 image if nothing remains.
   */
  public static BufferedImage trim(BufferedImage src, boolean trimBlack) {
    int minX = src.getWidth();
    int minY = src.getHeight();
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < src.getHeight(); y++) {
      for (int x = 0; x < src.getWidth(); x++) {
        // a "content" pixel is one that is NOT the colour being trimmed away
        if (isBlack(src, x, y) != trimBlack) {
          if (x < minX) minX = x;
          if (y < minY) minY = y;
          if (x > maxX) maxX = x;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (maxX < 0) {
      return blank(1, 1, false);
    }
    return region(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
  }

  /**
   * Resize the canvas to {@code w}&times;{@code h} on white paper, keeping pixels
   * 1:1 (no scaling). {@code centered} centres the old content, otherwise it is
   * anchored top-left; content outside the new bounds is clipped.
   */
  public static BufferedImage resizeCanvas(BufferedImage src, int w, int h, boolean centered) {
    BufferedImage out = blank(w, h, false);
    int offX = centered ? (w - src.getWidth()) / 2 : 0;
    int offY = centered ? (h - src.getHeight()) / 2 : 0;
    for (int y = 0; y < src.getHeight(); y++) {
      for (int x = 0; x < src.getWidth(); x++) {
        if (isBlack(src, x, y)) {
          set(out, offX + x, offY + y, true);
        }
      }
    }
    return out;
  }

  /**
   * Scale the image to an arbitrary {@code w}&times;{@code h} by area vote: each
   * destination pixel samples the source rectangle it covers and is black when at
   * least half of that area is black. Downscales cleanly and upscales as nearest.
   */
  public static BufferedImage scaleTo(BufferedImage src, int w, int h) {
    BufferedImage out = newBinary(w, h);
    for (int dy = 0; dy < h; dy++) {
      int sy0 = (int) ((long) dy * src.getHeight() / h);
      int sy1 = Math.max(sy0 + 1, (int) ((long) (dy + 1) * src.getHeight() / h));
      for (int dx = 0; dx < w; dx++) {
        int sx0 = (int) ((long) dx * src.getWidth() / w);
        int sx1 = Math.max(sx0 + 1, (int) ((long) (dx + 1) * src.getWidth() / w));
        int black = 0;
        int total = 0;
        for (int sy = sy0; sy < sy1; sy++) {
          for (int sx = sx0; sx < sx1; sx++) {
            total++;
            if (isBlack(src, sx, sy)) {
              black++;
            }
          }
        }
        set(out, dx, dy, black * 2 >= total);
      }
    }
    return out;
  }

  /** copy a rectangular region out as its own image (state-preserving). */
  public static BufferedImage region(BufferedImage src, int x, int y, int w, int h) {
    BufferedImage out = newBinary(w, h);
    for (int yy = 0; yy < h; yy++) {
      for (int xx = 0; xx < w; xx++) {
        setState(out, xx, yy, state(src, x + xx, y + yy));
      }
    }
    return out;
  }

  /**
   * Infer the background by an edge scan: white pixels reachable from the border
   * without crossing ink (black) or an existing transparent pixel — i.e. the
   * exterior region. Returns a {@code [w][h]} mask of background pixels (callers
   * typically turn these transparent). Interior white "holes" are not flagged.
   */
  public static boolean[][] backgroundMask(BufferedImage img) {
    int w = img.getWidth();
    int h = img.getHeight();
    boolean[][] bg = new boolean[w][h];
    java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
    for (int x = 0; x < w; x++) {
      seedBg(img, bg, stack, x, 0);
      seedBg(img, bg, stack, x, h - 1);
    }
    for (int y = 0; y < h; y++) {
      seedBg(img, bg, stack, 0, y);
      seedBg(img, bg, stack, w - 1, y);
    }
    while (!stack.isEmpty()) {
      int[] p = stack.pop();
      seedBg(img, bg, stack, p[0] + 1, p[1]);
      seedBg(img, bg, stack, p[0] - 1, p[1]);
      seedBg(img, bg, stack, p[0], p[1] + 1);
      seedBg(img, bg, stack, p[0], p[1] - 1);
    }
    return bg;
  }

  private static void seedBg(BufferedImage img, boolean[][] bg, java.util.ArrayDeque<int[]> stack, int x, int y) {
    if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight() || bg[x][y]) {
      return;
    }
    if (state(img, x, y) != WHITE) {
      return; // ink and transparency are walls
    }
    bg[x][y] = true;
    stack.push(new int[] {x, y});
  }

  // -------------------------------------------------------------------- file IO

  /**
   * Read a PNG and coerce it to black / white / transparent: a (mostly)
   * transparent pixel becomes {@link #TRANSPARENT}, otherwise it is thresholded
   * at the midpoint. Round-trips our own saved images losslessly.
   */
  public static BufferedImage load(File file) throws IOException {
    BufferedImage raw = ImageIO.read(file);
    if (raw == null) {
      throw new IOException("not a readable image: " + file);
    }
    int w = raw.getWidth();
    int h = raw.getHeight();
    boolean hasAlpha = raw.getColorModel().hasAlpha();
    BufferedImage out = newBinary(w, h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int argb = raw.getRGB(x, y);
        if (hasAlpha && (argb >>> 24) < 128) {
          setTransparent(out, x, y);
        } else {
          set(out, x, y, luminance(argb) < 128);
        }
      }
    }
    return out;
  }

  /** write a 1-bit PNG. The image must be a binary image from this class. */
  public static void savePng(BufferedImage mono, File file) throws IOException {
    File parent = file.getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    if (!ImageIO.write(mono, "png", file)) {
      throw new IOException("no PNG writer available for " + file);
    }
  }
}
