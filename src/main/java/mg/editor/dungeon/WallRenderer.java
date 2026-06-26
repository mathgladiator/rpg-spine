package mg.editor.dungeon;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the inferred wall surface of an occupancy grid. The algorithm is chosen
 * per macro cell ({@link Dungeon.Fill}); callers clip to a macro's rectangle and
 * call {@link #fill}/{@link #boundary} for that macro's cell range:
 *
 * <ul>
 *   <li>{@link Dungeon.Fill#MARCHING} — per-cell rounded blob: each wall cell fills
 *       its square with convex corners rounded by the cell's weight. Orthogonal.
 *   <li>{@link Dungeon.Fill#DIAGONAL} — dual-grid marching squares (lattice corners
 *       at cell centers) which connects staggered cells into diagonal lines.
 *   <li>{@link Dungeon.Fill#SQUARES} — plain on/off blocks; weight ignored.
 * </ul>
 *
 * <p>No colour blending: every cell fills with its own material colour, and a
 * DIAGONAL contour cell uses the single <em>majority</em> material of its occupied
 * corners (no averaging of e.g. dirt and stone). See
 * {@code documents/DUNGEON_WALLS.md}; the C ray caster must match.
 */
public final class WallRenderer {

  private WallRenderer() {}

  private static final double MAX_BOW = 0.5;

  /** a grid view: occupancy plus the colour/weight of occupied cells. */
  public interface Cells {
    /** true if (x,y) is solid wall; impls decide what out-of-bounds means. */
    boolean occupied(int x, int y);

    /** fill colour for an occupied cell (or rock for out-of-bounds). */
    Color color(int x, int y);

    /** wall weight 0..100 for an occupied cell (100 for out-of-bounds rock). */
    int weight(int x, int y);
  }

  // ----- dispatch -------------------------------------------------------------

  /** fill the wall bodies for cells [x0,x0+w) × [y0,y0+h) using {@code algo}. */
  public static void fill(GraphicsContext g, Dungeon.Fill algo, double s, int x0, int y0, int w, int h, Cells c) {
    Cells cc = confine(c, x0, y0, w, h);
    switch (algo) {
      case SQUARES -> squaresFill(g, s, x0, y0, w, h, cc);
      case MARCHING -> marchingFill(g, s, x0, y0, w, h, cc);
      case DIAGONAL -> diagonalFill(g, s, x0, y0, w, h, cc);
    }
  }

  /** stroke the inferred boundary (caller sets stroke colour/width/dashes). */
  public static void boundary(GraphicsContext g, Dungeon.Fill algo, double s, int x0, int y0, int w, int h, Cells c) {
    Cells cc = confine(c, x0, y0, w, h);
    switch (algo) {
      case SQUARES -> squaresBoundary(g, s, x0, y0, w, h, cc);
      case MARCHING -> marchingBoundary(g, s, x0, y0, w, h, cc);
      case DIAGONAL -> diagonalBoundary(g, s, x0, y0, w, h, cc);
    }
  }

  /**
   * A view that clamps every lookup to the cell box [x0,x0+w) × [y0,y0+h), so an
   * algorithm reads <em>only</em> this macro's cells: out-of-box reads return the
   * nearest edge cell (the edge is replicated outward). This stops the DIAGONAL
   * dual grid from bleeding across macro boundaries and makes every algorithm meet
   * a macro boundary on the micro-cell grid — a consistent interface regardless of
   * which algorithm a neighbouring macro uses.
   */
  private static Cells confine(Cells c, int x0, int y0, int w, int h) {
    int xa = x0, ya = y0, xb = x0 + w - 1, yb = y0 + h - 1;
    return new Cells() {
      @Override public boolean occupied(int x, int y) { return c.occupied(clamp(x, xa, xb), clamp(y, ya, yb)); }
      @Override public Color color(int x, int y) { return c.color(clamp(x, xa, xb), clamp(y, ya, yb)); }
      @Override public int weight(int x, int y) { return c.weight(clamp(x, xa, xb), clamp(y, ya, yb)); }
    };
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  // ----- SQUARES --------------------------------------------------------------

  private static void squaresFill(GraphicsContext g, double s, int x0, int y0, int w, int h, Cells c) {
    for (int x = x0; x < x0 + w; x++) {
      for (int y = y0; y < y0 + h; y++) {
        if (c.occupied(x, y)) {
          g.setFill(c.color(x, y));
          g.fillRect(x * s, y * s, s, s);
        }
      }
    }
  }

  private static void squaresBoundary(GraphicsContext g, double s, int x0, int y0, int w, int h, Cells c) {
    for (int x = x0; x < x0 + w; x++) {
      for (int y = y0; y < y0 + h; y++) {
        if (!c.occupied(x, y)) {
          continue;
        }
        double px = x * s, py = y * s;
        if (!c.occupied(x, y - 1)) g.strokeLine(px, py, px + s, py);
        if (!c.occupied(x + 1, y)) g.strokeLine(px + s, py, px + s, py + s);
        if (!c.occupied(x, y + 1)) g.strokeLine(px, py + s, px + s, py + s);
        if (!c.occupied(x - 1, y)) g.strokeLine(px, py, px, py + s);
      }
    }
  }

  // ----- MARCHING (per-cell rounded blob) -------------------------------------

  private static double radius(double s, int weight) {
    return Math.min((1.0 - weight / 100.0) * (s * 0.5), s / 2);
  }

  private static void marchingFill(GraphicsContext g, double s, int x0, int y0, int w, int h, Cells c) {
    for (int x = x0; x < x0 + w; x++) {
      for (int y = y0; y < y0 + h; y++) {
        if (!c.occupied(x, y)) {
          continue;
        }
        double px = x * s, py = y * s;
        double r = radius(s, c.weight(x, y));
        boolean tl = !c.occupied(x, y - 1) && !c.occupied(x - 1, y);
        boolean tr = !c.occupied(x, y - 1) && !c.occupied(x + 1, y);
        boolean br = !c.occupied(x, y + 1) && !c.occupied(x + 1, y);
        boolean bl = !c.occupied(x, y + 1) && !c.occupied(x - 1, y);
        g.setFill(c.color(x, y));
        g.beginPath();
        g.moveTo(px + (tl ? r : 0), py);
        if (tr) { g.lineTo(px + s - r, py); g.quadraticCurveTo(px + s, py, px + s, py + r); }
        else { g.lineTo(px + s, py); }
        if (br) { g.lineTo(px + s, py + s - r); g.quadraticCurveTo(px + s, py + s, px + s - r, py + s); }
        else { g.lineTo(px + s, py + s); }
        if (bl) { g.lineTo(px + r, py + s); g.quadraticCurveTo(px, py + s, px, py + s - r); }
        else { g.lineTo(px, py + s); }
        if (tl) { g.lineTo(px, py + r); g.quadraticCurveTo(px, py, px + r, py); }
        else { g.lineTo(px, py); }
        g.closePath();
        g.fill();
      }
    }
  }

  private static void marchingBoundary(GraphicsContext g, double s, int x0, int y0, int w, int h, Cells c) {
    for (int x = x0; x < x0 + w; x++) {
      for (int y = y0; y < y0 + h; y++) {
        if (!c.occupied(x, y)) {
          continue;
        }
        double px = x * s, py = y * s;
        double r = radius(s, c.weight(x, y));
        boolean openN = !c.occupied(x, y - 1), openE = !c.occupied(x + 1, y);
        boolean openS = !c.occupied(x, y + 1), openW = !c.occupied(x - 1, y);
        boolean tl = openN && openW, tr = openN && openE, br = openS && openE, bl = openS && openW;
        if (openN) g.strokeLine(px + (tl ? r : 0), py, px + s - (tr ? r : 0), py);
        if (openE) g.strokeLine(px + s, py + (tr ? r : 0), px + s, py + s - (br ? r : 0));
        if (openS) g.strokeLine(px + (bl ? r : 0), py + s, px + s - (br ? r : 0), py + s);
        if (openW) g.strokeLine(px, py + (tl ? r : 0), px, py + s - (bl ? r : 0));
        if (tl) arc(g, px, py + r, px, py, px + r, py);
        if (tr) arc(g, px + s - r, py, px + s, py, px + s, py + r);
        if (br) arc(g, px + s, py + s - r, px + s, py + s, px + s - r, py + s);
        if (bl) arc(g, px + r, py + s, px, py + s, px, py + s - r);
      }
    }
  }

  private static void arc(GraphicsContext g, double x0, double y0, double cx, double cy, double x1, double y1) {
    g.beginPath();
    g.moveTo(x0, y0);
    g.quadraticCurveTo(cx, cy, x1, y1);
    g.stroke();
  }

  // ----- DIAGONAL (dual-grid marching squares) --------------------------------

  private record Piece(double[][] ring, boolean[] boundary) {}

  private interface SquareSink {
    void accept(Color color, int weight, double[] centroid, List<Piece> pieces);
  }

  private static void forEachSquare(double s, int x0, int y0, int w, int h, Cells cells, SquareSink sink) {
    for (int i = x0 - 1; i < x0 + w; i++) {
      for (int j = y0 - 1; j < y0 + h; j++) {
        boolean a = cells.occupied(i, j);
        boolean b = cells.occupied(i + 1, j);
        boolean cc = cells.occupied(i + 1, j + 1);
        boolean d = cells.occupied(i, j + 1);
        int code = (a ? 8 : 0) | (b ? 4 : 0) | (cc ? 2 : 0) | (d ? 1 : 0);
        if (code == 0) {
          continue;
        }
        double left = i * s + s / 2, top = j * s + s / 2;
        double right = (i + 1) * s + s / 2, bottom = (j + 1) * s + s / 2;
        double[] TL = {left, top}, TR = {right, top}, BR = {right, bottom}, BL = {left, bottom};
        double[] mT = {(left + right) / 2, top}, mR = {right, (top + bottom) / 2};
        double[] mB = {(left + right) / 2, bottom}, mL = {left, (top + bottom) / 2};

        // majority colour/weight (no blending) + centroid of occupied corner points
        List<Color> cols = new ArrayList<>(4);
        List<Integer> wts = new ArrayList<>(4);
        double cx = 0, cy = 0;
        int occ = 0;
        if (a) { cols.add(cells.color(i, j)); wts.add(cells.weight(i, j)); cx += TL[0]; cy += TL[1]; occ++; }
        if (b) { cols.add(cells.color(i + 1, j)); wts.add(cells.weight(i + 1, j)); cx += TR[0]; cy += TR[1]; occ++; }
        if (cc) { cols.add(cells.color(i + 1, j + 1)); wts.add(cells.weight(i + 1, j + 1)); cx += BR[0]; cy += BR[1]; occ++; }
        if (d) { cols.add(cells.color(i, j + 1)); wts.add(cells.weight(i, j + 1)); cx += BL[0]; cy += BL[1]; occ++; }
        int rep = majority(cols);
        double[] centroid = {cx / occ, cy / occ};

        sink.accept(cols.get(rep), wts.get(rep), centroid, pieces(code, TL, TR, BR, BL, mT, mR, mB, mL));
      }
    }
  }

  /** index of the most common colour (ties → first), so a square takes one material, never a blend. */
  private static int majority(List<Color> cols) {
    int best = 0, bestCount = 0;
    for (int i = 0; i < cols.size(); i++) {
      int count = 0;
      for (Color c : cols) {
        if (c.equals(cols.get(i))) {
          count++;
        }
      }
      if (count > bestCount) {
        bestCount = count;
        best = i;
      }
    }
    return best;
  }

  private static void diagonalFill(GraphicsContext g, double s, int x0, int y0, int w, int h, Cells c) {
    forEachSquare(s, x0, y0, w, h, c, (color, weight, centroid, pieces) -> {
      g.setFill(color);
      for (Piece p : pieces) {
        double[][] ring = p.ring();
        boolean[] bnd = p.boundary();
        int n = ring.length;
        g.beginPath();
        g.moveTo(ring[0][0], ring[0][1]);
        for (int k = 0; k < n; k++) {
          double[] q = ring[(k + 1) % n];
          if (bnd[k]) {
            double[] ctl = bow(ring[k], q, centroid, weight);
            g.quadraticCurveTo(ctl[0], ctl[1], q[0], q[1]);
          } else {
            g.lineTo(q[0], q[1]);
          }
        }
        g.closePath();
        g.fill();
      }
    });
  }

  private static void diagonalBoundary(GraphicsContext g, double s, int x0, int y0, int w, int h, Cells c) {
    forEachSquare(s, x0, y0, w, h, c, (color, weight, centroid, pieces) -> {
      for (Piece p : pieces) {
        double[][] ring = p.ring();
        boolean[] bnd = p.boundary();
        int n = ring.length;
        for (int k = 0; k < n; k++) {
          if (!bnd[k]) {
            continue;
          }
          double[] a = ring[k];
          double[] b = ring[(k + 1) % n];
          double[] ctl = bow(a, b, centroid, weight);
          g.beginPath();
          g.moveTo(a[0], a[1]);
          g.quadraticCurveTo(ctl[0], ctl[1], b[0], b[1]);
          g.stroke();
        }
      }
    });
  }

  private static List<Piece> pieces(int code, double[] TL, double[] TR, double[] BR, double[] BL,
                                    double[] mT, double[] mR, double[] mB, double[] mL) {
    List<Piece> out = new ArrayList<>(2);
    switch (code) {
      case 15 -> out.add(new Piece(new double[][] {TL, TR, BR, BL}, new boolean[] {false, false, false, false}));
      case 1 -> out.add(new Piece(new double[][] {BL, mB, mL}, new boolean[] {false, true, false}));
      case 2 -> out.add(new Piece(new double[][] {BR, mR, mB}, new boolean[] {false, true, false}));
      case 4 -> out.add(new Piece(new double[][] {TR, mT, mR}, new boolean[] {false, true, false}));
      case 8 -> out.add(new Piece(new double[][] {TL, mL, mT}, new boolean[] {false, true, false}));
      case 3 -> out.add(new Piece(new double[][] {BL, BR, mR, mL}, new boolean[] {false, false, true, false}));
      case 12 -> out.add(new Piece(new double[][] {TL, TR, mR, mL}, new boolean[] {false, false, true, false}));
      case 6 -> out.add(new Piece(new double[][] {TR, BR, mB, mT}, new boolean[] {false, false, true, false}));
      case 9 -> out.add(new Piece(new double[][] {TL, BL, mB, mT}, new boolean[] {false, false, true, false}));
      case 7 -> out.add(new Piece(new double[][] {TR, BR, BL, mL, mT}, new boolean[] {false, false, false, true, false}));
      case 11 -> out.add(new Piece(new double[][] {TL, mT, mR, BR, BL}, new boolean[] {false, true, false, false, false}));
      case 13 -> out.add(new Piece(new double[][] {TL, TR, mR, mB, BL}, new boolean[] {false, false, true, false, false}));
      case 14 -> out.add(new Piece(new double[][] {TL, TR, BR, mB, mL}, new boolean[] {false, false, false, true, false}));
      case 5 -> out.add(new Piece(new double[][] {mT, TR, mR, mB, BL, mL},
          new boolean[] {false, false, true, false, false, true}));
      case 10 -> out.add(new Piece(new double[][] {TL, mT, mR, BR, mB, mL},
          new boolean[] {false, true, false, false, true, false}));
      default -> { /* 0: nothing */ }
    }
    return out;
  }

  private static double[] bow(double[] p, double[] q, double[] centroid, int weight) {
    double mx = (p[0] + q[0]) / 2, my = (p[1] + q[1]) / 2;
    double amt = (1.0 - weight / 100.0) * MAX_BOW;
    return new double[] {mx + (mx - centroid[0]) * amt, my + (my - centroid[1]) * amt};
  }
}
