package mg.assets;

import java.awt.image.BufferedImage;

/**
 * Black-and-white conversion algorithms — the toolbox for turning a colour /
 * grey source into a high-quality 1-bit image for the Playdate. All are pure
 * Java and emit a {@link Mono} {@code TYPE_BYTE_BINARY} image, so they slot into
 * the asset pipeline directly.
 *
 * <h2>Families</h2>
 * <ul>
 *   <li><b>Threshold</b> — a hard cut; flat, posterised, fastest.
 *   <li><b>Ordered, dispersed-dot (Bayer)</b> — a tiled threshold matrix; very
 *       fast, deterministic, tileable, gives the classic "ordered" texture.
 *       Sizes 2&times;2 / 4&times;4 / 8&times;8 trade coarseness for smoothness.
 *   <li><b>Ordered, clustered-dot (halftone)</b> — like newsprint dots; groups
 *       ink so it survives heavy downscaling better than dispersed patterns.
 *   <li><b>Error diffusion</b> — pushes the quantisation error into not-yet-drawn
 *       neighbours, giving the smoothest tonal gradients. Floyd–Steinberg is the
 *       classic; Atkinson (Macintosh) is higher-contrast/lighter; JJN, Stucki,
 *       Burkes, Sierra spread error over a wider kernel for finer gradients.
 *       Run serpentine (alternating row direction) to avoid directional worming.
 *   <li><b>Contour (marching squares)</b> — not a halftone: it traces the
 *       iso-line between dark and light and draws clean outline curves, which is
 *       ideal for crisp line-art silhouettes on a 1-bit screen.
 * </ul>
 * The {@code threshold} parameter (0&ndash;255) is the quantisation midpoint:
 * for threshold/diffusion a pixel goes black below it; for ordered modes it
 * biases the matrix; for contour it is the iso-level.
 */
public final class Dither {

  /** the available conversions, in roughly increasing sophistication. */
  public enum Algo {
    THRESHOLD("Threshold"),
    BAYER_2("Ordered 2×2 (Bayer)"),
    BAYER_4("Ordered 4×4 (Bayer)"),
    BAYER_8("Ordered 8×8 (Bayer)"),
    HALFTONE("Halftone (clustered 4×4)"),
    FLOYD("Floyd–Steinberg"),
    ATKINSON("Atkinson"),
    JJN("Jarvis–Judice–Ninke"),
    STUCKI("Stucki"),
    BURKES("Burkes"),
    SIERRA("Sierra"),
    SIERRA_LITE("Sierra Lite"),
    CONTOUR("Contour (marching squares)"),
    OUTLINE("Outline (silhouette, marching squares)");

    private final String label;

    Algo(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  /** how a (mostly) transparent source pixel is handled during conversion. */
  public enum AlphaMode { WHITE, BLACK, TRANSPARENT }

  private Dither() {}

  /**
   * Convert with explicit alpha handling: source pixels whose alpha is below the
   * midpoint become white, black, or {@link Mono#TRANSPARENT} per {@code alpha};
   * opaque pixels are converted by {@code algo} as usual.
   */
  public static BufferedImage apply(BufferedImage src, Algo algo, int threshold, AlphaMode alpha) {
    BufferedImage out = apply(src, algo, threshold);
    // OUTLINE draws its own transparent background, so the alpha reinterpretation
    // below (which would flood transparent source pixels with white/black) is skipped.
    if (alpha == null || algo == Algo.OUTLINE) {
      return out;
    }
    boolean hasAlpha = src.getColorModel().hasAlpha();
    if (!hasAlpha) {
      return out; // nothing transparent to reinterpret
    }
    for (int y = 0; y < src.getHeight(); y++) {
      for (int x = 0; x < src.getWidth(); x++) {
        if (((src.getRGB(x, y) >>> 24) & 0xFF) < 128) {
          switch (alpha) {
            case WHITE -> Mono.set(out, x, y, false);
            case BLACK -> Mono.set(out, x, y, true);
            case TRANSPARENT -> Mono.setTransparent(out, x, y);
          }
        }
      }
    }
    return out;
  }

  /** convert {@code src} to 1-bit using {@code algo} and the given midpoint. */
  public static BufferedImage apply(BufferedImage src, Algo algo, int threshold) {
    return switch (algo) {
      case THRESHOLD -> threshold(src, threshold);
      case BAYER_2 -> ordered(src, bayer(2), 2, threshold);
      case BAYER_4 -> ordered(src, bayer(4), 4, threshold);
      case BAYER_8 -> ordered(src, bayer(8), 8, threshold);
      case HALFTONE -> ordered(src, CLUSTERED_4, 4, threshold);
      case FLOYD -> diffuse(src, FLOYD_STEINBERG, 16, threshold);
      case ATKINSON -> diffuse(src, ATKINSON_K, 8, threshold);
      case JJN -> diffuse(src, JJN_K, 48, threshold);
      case STUCKI -> diffuse(src, STUCKI_K, 42, threshold);
      case BURKES -> diffuse(src, BURKES_K, 32, threshold);
      case SIERRA -> diffuse(src, SIERRA_K, 32, threshold);
      case SIERRA_LITE -> diffuse(src, SIERRA_LITE_K, 4, threshold);
      case CONTOUR -> contour(src, threshold);
      case OUTLINE -> outline(src, threshold);
    };
  }

  // -------------------------------------------------------------- thresholding

  public static BufferedImage threshold(BufferedImage src, int level) {
    int w = src.getWidth();
    int h = src.getHeight();
    BufferedImage out = Mono.newBinary(w, h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        Mono.set(out, x, y, lum(src.getRGB(x, y)) < level);
      }
    }
    return out;
  }

  // ----------------------------------------------------------------- ordered

  private static BufferedImage ordered(BufferedImage src, int[][] m, int n, int threshold) {
    int w = src.getWidth();
    int h = src.getHeight();
    int max = n * n;
    BufferedImage out = Mono.newBinary(w, h);
    int bias = threshold - 128; // 0 = neutral; >0 darkens, <0 lightens
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        double t = (m[y % n][x % n] + 0.5) / max * 255.0;
        Mono.set(out, x, y, lum(src.getRGB(x, y)) - bias < t);
      }
    }
    return out;
  }

  /** the recursive dispersed-dot Bayer matrix of order n (a power of two). */
  static int[][] bayer(int n) {
    if (n == 1) {
      return new int[][] {{0}};
    }
    int half = n / 2;
    int[][] s = bayer(half);
    int[][] m = new int[n][n];
    for (int y = 0; y < n; y++) {
      for (int x = 0; x < n; x++) {
        int q = s[y % half][x % half];
        int quadrant = (y < half) ? (x < half ? 0 : 2) : (x < half ? 3 : 1);
        m[y][x] = 4 * q + quadrant;
      }
    }
    return m;
  }

  /** a clustered-dot 4×4 matrix (ink grows outward from the centre). */
  private static final int[][] CLUSTERED_4 = {
      {12, 5, 6, 13},
      {4, 0, 1, 7},
      {11, 3, 2, 8},
      {15, 10, 9, 14},
  };

  // ------------------------------------------------------------- error diffusion

  /** kernels as rows of {dx, dy, weight}; weights summed = divisor. */
  private static final int[][] FLOYD_STEINBERG = {
      {1, 0, 7}, {-1, 1, 3}, {0, 1, 5}, {1, 1, 1}};
  private static final int[][] ATKINSON_K = {
      {1, 0, 1}, {2, 0, 1}, {-1, 1, 1}, {0, 1, 1}, {1, 1, 1}, {0, 2, 1}};
  private static final int[][] JJN_K = {
      {1, 0, 7}, {2, 0, 5},
      {-2, 1, 3}, {-1, 1, 5}, {0, 1, 7}, {1, 1, 5}, {2, 1, 3},
      {-2, 2, 1}, {-1, 2, 3}, {0, 2, 5}, {1, 2, 3}, {2, 2, 1}};
  private static final int[][] STUCKI_K = {
      {1, 0, 8}, {2, 0, 4},
      {-2, 1, 2}, {-1, 1, 4}, {0, 1, 8}, {1, 1, 4}, {2, 1, 2},
      {-2, 2, 1}, {-1, 2, 2}, {0, 2, 4}, {1, 2, 2}, {2, 2, 1}};
  private static final int[][] BURKES_K = {
      {1, 0, 8}, {2, 0, 4},
      {-2, 1, 2}, {-1, 1, 4}, {0, 1, 8}, {1, 1, 4}, {2, 1, 2}};
  private static final int[][] SIERRA_K = {
      {1, 0, 5}, {2, 0, 3},
      {-2, 1, 2}, {-1, 1, 4}, {0, 1, 5}, {1, 1, 4}, {2, 1, 2},
      {-1, 2, 2}, {0, 2, 3}, {1, 2, 2}};
  private static final int[][] SIERRA_LITE_K = {
      {1, 0, 2}, {-1, 1, 1}, {0, 1, 1}};

  private static BufferedImage diffuse(BufferedImage src, int[][] kernel, int divisor, int threshold) {
    int w = src.getWidth();
    int h = src.getHeight();
    float[] g = new float[w * h];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        g[y * w + x] = lum(src.getRGB(x, y));
      }
    }
    BufferedImage out = Mono.newBinary(w, h);
    for (int y = 0; y < h; y++) {
      boolean ltr = (y & 1) == 0; // serpentine: even rows left-to-right
      for (int k = 0; k < w; k++) {
        int x = ltr ? k : (w - 1 - k);
        float old = g[y * w + x];
        boolean black = old < threshold;
        Mono.set(out, x, y, black);
        float err = old - (black ? 0f : 255f);
        for (int[] kp : kernel) {
          int dx = ltr ? kp[0] : -kp[0];
          int nx = x + dx;
          int ny = y + kp[1];
          if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
            g[ny * w + nx] += err * kp[2] / divisor;
          }
        }
      }
    }
    return out;
  }

  // ----------------------------------------------------- marching-squares contour

  /**
   * Trace the iso-contour at {@code level} between dark and light regions and
   * draw it as black outline curves on white. Each grid cell contributes the
   * standard marching-squares segment(s) between its edge midpoints.
   */
  public static BufferedImage contour(BufferedImage src, int level) {
    int w = src.getWidth();
    int h = src.getHeight();
    boolean[][] dark = new boolean[w][h];
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        dark[x][y] = lum(src.getRGB(x, y)) < level;
      }
    }
    BufferedImage out = Mono.blank(w, h, false);
    for (int y = 0; y + 1 < h; y++) {
      for (int x = 0; x + 1 < w; x++) {
        boolean tl = dark[x][y];
        boolean tr = dark[x + 1][y];
        boolean br = dark[x + 1][y + 1];
        boolean bl = dark[x][y + 1];
        int code = (tl ? 8 : 0) | (tr ? 4 : 0) | (br ? 2 : 0) | (bl ? 1 : 0);
        // edge midpoints in pixel space
        double tx = x + 0.5, ty = y;        // top
        double rx = x + 1, ry = y + 0.5;     // right
        double bx = x + 0.5, by = y + 1;     // bottom
        double lx = x, ly = y + 0.5;         // left
        switch (code) {
          case 1, 14 -> line(out, lx, ly, bx, by);
          case 2, 13 -> line(out, bx, by, rx, ry);
          case 3, 12 -> line(out, lx, ly, rx, ry);
          case 4, 11 -> line(out, tx, ty, rx, ry);
          case 6, 9 -> line(out, tx, ty, bx, by);
          case 7, 8 -> line(out, tx, ty, lx, ly);
          case 5 -> { line(out, tx, ty, lx, ly); line(out, bx, by, rx, ry); }
          case 10 -> { line(out, tx, ty, rx, ry); line(out, lx, ly, bx, by); }
          default -> { /* 0 and 15: no contour */ }
        }
      }
    }
    return out;
  }

  /**
   * Trace the boundary of the opaque <em>silhouette</em> (white and black count
   * equally as "inside"; transparent is "outside") with marching squares, then
   * thicken that line to {@code distance} pixels. The result is a black outline on
   * a fully transparent background — a halo/border you can lay over the sprite or
   * use as a sticker edge. {@code distance} 1 is the raw 1px iso-line; larger
   * values dilate it (a Chebyshev distance band). A source with no alpha has no
   * silhouette edge and yields an empty image.
   */
  public static BufferedImage outline(BufferedImage src, int distance) {
    int w = src.getWidth();
    int h = src.getHeight();
    boolean hasAlpha = src.getColorModel().hasAlpha();
    boolean[][] inside = new boolean[w][h];
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        inside[x][y] = !hasAlpha || ((src.getRGB(x, y) >>> 24) & 0xFF) >= 128;
      }
    }
    boolean[][] mask = new boolean[w][h];
    for (int y = 0; y + 1 < h; y++) {
      for (int x = 0; x + 1 < w; x++) {
        boolean tl = inside[x][y];
        boolean tr = inside[x + 1][y];
        boolean br = inside[x + 1][y + 1];
        boolean bl = inside[x][y + 1];
        int code = (tl ? 8 : 0) | (tr ? 4 : 0) | (br ? 2 : 0) | (bl ? 1 : 0);
        double tx = x + 0.5, ty = y;
        double rx = x + 1, ry = y + 0.5;
        double bx = x + 0.5, by = y + 1;
        double lx = x, ly = y + 0.5;
        switch (code) {
          case 1, 14 -> lineMask(mask, w, h, lx, ly, bx, by);
          case 2, 13 -> lineMask(mask, w, h, bx, by, rx, ry);
          case 3, 12 -> lineMask(mask, w, h, lx, ly, rx, ry);
          case 4, 11 -> lineMask(mask, w, h, tx, ty, rx, ry);
          case 6, 9 -> lineMask(mask, w, h, tx, ty, bx, by);
          case 7, 8 -> lineMask(mask, w, h, tx, ty, lx, ly);
          case 5 -> { lineMask(mask, w, h, tx, ty, lx, ly); lineMask(mask, w, h, bx, by, rx, ry); }
          case 10 -> { lineMask(mask, w, h, tx, ty, rx, ry); lineMask(mask, w, h, lx, ly, bx, by); }
          default -> { /* 0 and 15: no boundary */ }
        }
      }
    }
    boolean[][] ink = Math.max(1, distance) <= 1 ? mask : within(mask, w, h, distance);
    BufferedImage out = Mono.newBinary(w, h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if (ink[x][y]) {
          Mono.set(out, x, y, true);
        } else {
          Mono.setTransparent(out, x, y);
        }
      }
    }
    return out;
  }

  /** pixels within a Chebyshev {@code distance} of any set pixel (two-pass transform). */
  private static boolean[][] within(boolean[][] mask, int w, int h, int distance) {
    int inf = w + h + 1;
    int[][] dist = new int[w][h];
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        dist[x][y] = mask[x][y] ? 0 : inf;
      }
    }
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int m = dist[x][y];
        if (x > 0) m = Math.min(m, dist[x - 1][y] + 1);
        if (y > 0) m = Math.min(m, dist[x][y - 1] + 1);
        if (x > 0 && y > 0) m = Math.min(m, dist[x - 1][y - 1] + 1);
        if (x < w - 1 && y > 0) m = Math.min(m, dist[x + 1][y - 1] + 1);
        dist[x][y] = m;
      }
    }
    boolean[][] ink = new boolean[w][h];
    for (int y = h - 1; y >= 0; y--) {
      for (int x = w - 1; x >= 0; x--) {
        int m = dist[x][y];
        if (x < w - 1) m = Math.min(m, dist[x + 1][y] + 1);
        if (y < h - 1) m = Math.min(m, dist[x][y + 1] + 1);
        if (x < w - 1 && y < h - 1) m = Math.min(m, dist[x + 1][y + 1] + 1);
        if (x > 0 && y < h - 1) m = Math.min(m, dist[x - 1][y + 1] + 1);
        dist[x][y] = m;
        ink[x][y] = m < distance;
      }
    }
    return ink;
  }

  /** Bresenham line into a boolean mask (bounds-checked). */
  private static void lineMask(boolean[][] mask, int w, int h, double x0d, double y0d, double x1d, double y1d) {
    int x0 = (int) Math.round(x0d);
    int y0 = (int) Math.round(y0d);
    int x1 = (int) Math.round(x1d);
    int y1 = (int) Math.round(y1d);
    int dx = Math.abs(x1 - x0);
    int dy = -Math.abs(y1 - y0);
    int sx = x0 < x1 ? 1 : -1;
    int sy = y0 < y1 ? 1 : -1;
    int err = dx + dy;
    while (true) {
      if (x0 >= 0 && y0 >= 0 && x0 < w && y0 < h) {
        mask[x0][y0] = true;
      }
      if (x0 == x1 && y0 == y1) {
        break;
      }
      int e2 = 2 * err;
      if (e2 >= dy) {
        err += dy;
        x0 += sx;
      }
      if (e2 <= dx) {
        err += dx;
        y0 += sy;
      }
    }
  }

  /** Bresenham line of black pixels into a Mono image. */
  private static void line(BufferedImage img, double x0d, double y0d, double x1d, double y1d) {
    int x0 = (int) Math.round(x0d);
    int y0 = (int) Math.round(y0d);
    int x1 = (int) Math.round(x1d);
    int y1 = (int) Math.round(y1d);
    int dx = Math.abs(x1 - x0);
    int dy = -Math.abs(y1 - y0);
    int sx = x0 < x1 ? 1 : -1;
    int sy = y0 < y1 ? 1 : -1;
    int err = dx + dy;
    while (true) {
      Mono.set(img, x0, y0, true);
      if (x0 == x1 && y0 == y1) {
        break;
      }
      int e2 = 2 * err;
      if (e2 >= dy) {
        err += dy;
        x0 += sx;
      }
      if (e2 <= dx) {
        err += dx;
        y0 += sy;
      }
    }
  }

  // -------------------------------------------------------------------- helpers

  private static int lum(int argb) {
    int r = (argb >> 16) & 0xFF;
    int g = (argb >> 8) & 0xFF;
    int b = argb & 0xFF;
    return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
  }
}
