package mg.editor.dungeon;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the inferred wall surface of an occupancy grid with <b>dual-grid
 * marching squares</b>: the lattice corners are the micro-cell <em>centers</em>,
 * so each 2&times;2 block of cells contributes one contour cell. This naturally
 * forms straight diagonal lines (a single wall cell is a small column; two
 * orthogonally or <em>diagonally</em> adjacent wall cells join with a line), which
 * a per-corner rounding pass cannot do.
 *
 * <p>Each wall material's {@code weight} (0..100) bows the boundary segments: 100
 * keeps them straight (sharp stone, crisp diagonals); 0 bows them outward for a
 * smooth, organic edge (dirt). See {@code documents/DUNGEON_WALLS.md} — the C ray
 * caster must implement the same case table.
 *
 * <p>Used by both {@link mg.editor.DungeonEditor} and the template editor; callers
 * supply a {@link Cells} view (out-of-bounds occupancy is the caller's choice).
 */
public final class WallRenderer {

  private WallRenderer() {}

  /** how far (fraction of the half-diagonal) a fully-smooth edge bows; kept modest so lines stay crisp. */
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

  private record Piece(double[][] ring, boolean[] boundary) {}

  /** fill the wall bodies. */
  public static void fill(GraphicsContext g, int w, int h, double s, Cells cells) {
    forEachSquare(w, h, s, cells, (color, weight, centroid, pieces) -> {
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

  /** stroke the inferred boundary (caller sets stroke colour, width and dashes). */
  public static void boundary(GraphicsContext g, int w, int h, double s, Cells cells) {
    forEachSquare(w, h, s, cells, (color, weight, centroid, pieces) -> {
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

  private interface SquareSink {
    void accept(Color color, int weight, double[] centroid, List<Piece> pieces);
  }

  private static void forEachSquare(int w, int h, double s, Cells cells, SquareSink sink) {
    for (int i = -1; i < w; i++) {
      for (int j = -1; j < h; j++) {
        boolean a = cells.occupied(i, j);          // TL = (i,j)
        boolean b = cells.occupied(i + 1, j);      // TR
        boolean c = cells.occupied(i + 1, j + 1);  // BR
        boolean d = cells.occupied(i, j + 1);      // BL
        int code = (a ? 8 : 0) | (b ? 4 : 0) | (c ? 2 : 0) | (d ? 1 : 0);
        if (code == 0) {
          continue;
        }
        double left = i * s + s / 2, top = j * s + s / 2;
        double right = (i + 1) * s + s / 2, bottom = (j + 1) * s + s / 2;
        double[] TL = {left, top}, TR = {right, top}, BR = {right, bottom}, BL = {left, bottom};
        double[] mT = {(left + right) / 2, top}, mR = {right, (top + bottom) / 2};
        double[] mB = {(left + right) / 2, bottom}, mL = {left, (top + bottom) / 2};

        // colour / weight / centroid from the occupied corners
        double rSum = 0, gSum = 0, bSum = 0, wSum = 0, cxSum = 0, cySum = 0;
        int occ = 0;
        if (a) { Color col = cells.color(i, j); rSum += col.getRed(); gSum += col.getGreen(); bSum += col.getBlue(); wSum += cells.weight(i, j); cxSum += TL[0]; cySum += TL[1]; occ++; }
        if (b) { Color col = cells.color(i + 1, j); rSum += col.getRed(); gSum += col.getGreen(); bSum += col.getBlue(); wSum += cells.weight(i + 1, j); cxSum += TR[0]; cySum += TR[1]; occ++; }
        if (c) { Color col = cells.color(i + 1, j + 1); rSum += col.getRed(); gSum += col.getGreen(); bSum += col.getBlue(); wSum += cells.weight(i + 1, j + 1); cxSum += BR[0]; cySum += BR[1]; occ++; }
        if (d) { Color col = cells.color(i, j + 1); rSum += col.getRed(); gSum += col.getGreen(); bSum += col.getBlue(); wSum += cells.weight(i, j + 1); cxSum += BL[0]; cySum += BL[1]; occ++; }
        Color color = Color.color(rSum / occ, gSum / occ, bSum / occ);
        int weight = (int) Math.round(wSum / occ);
        double[] centroid = {cxSum / occ, cySum / occ};

        sink.accept(color, weight, centroid, pieces(code, TL, TR, BR, BL, mT, mR, mB, mL));
      }
    }
  }

  /** the occupied polygon(s) for a marching-squares case, with boundary edges flagged. */
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
      // diagonals: connect the occupied corners so staggered cells form a diagonal line
      case 5 -> out.add(new Piece(new double[][] {mT, TR, mR, mB, BL, mL},
          new boolean[] {false, false, true, false, false, true}));
      case 10 -> out.add(new Piece(new double[][] {TL, mT, mR, BR, mB, mL},
          new boolean[] {false, true, false, false, true, false}));
      default -> { /* 0: nothing */ }
    }
    return out;
  }

  /** control point that bows a boundary edge outward (away from the occupied centroid) by weight. */
  private static double[] bow(double[] p, double[] q, double[] centroid, int weight) {
    double mx = (p[0] + q[0]) / 2, my = (p[1] + q[1]) / 2;
    double amt = (1.0 - weight / 100.0) * MAX_BOW;
    return new double[] {mx + (mx - centroid[0]) * amt, my + (my - centroid[1]) * amt};
  }
}
