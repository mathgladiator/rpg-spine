package mg.editor.dungeon;

import mg.editor.KV;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable room stamp: a grid of tri-state cells (wall / open / skip) sized in
 * <b>macro units</b> (its micro width/height are always multiples of
 * {@link Dungeon#MACRO}). It carries no floor material or features — just
 * occupancy — and is authored in the template editor, saved as {@code .template},
 * and stamped into a dungeon <b>aligned to the macro grid</b>: {@code WALL} cells
 * become the chosen wall material, {@code OPEN} cells the chosen floor, and
 * {@code SKIP} cells are left untouched.
 *
 * <p>Serialized as KV: a {@code template} header then one {@code row} per line,
 * each cell a char — {@code #} wall, {@code .} open, {@code _} skip.
 */
public final class Template {

  public static final byte SKIP = 0;
  public static final byte WALL = 1;
  public static final byte OPEN = 2;

  public String name;
  public int width;   // micro cells (multiple of MACRO)
  public int height;  // micro cells (multiple of MACRO)
  public byte[][] cells; // [x][y]

  /** create a template {@code macroW}×{@code macroH} macro cells in size. */
  public Template(String name, int macroW, int macroH) {
    this.name = name;
    this.width = Math.max(1, macroW) * Dungeon.MACRO;
    this.height = Math.max(1, macroH) * Dungeon.MACRO;
    this.cells = new byte[width][height];
  }

  public int macroW() {
    return width / Dungeon.MACRO;
  }

  public int macroH() {
    return height / Dungeon.MACRO;
  }

  public boolean inBounds(int x, int y) {
    return x >= 0 && y >= 0 && x < width && y < height;
  }

  /** resize to {@code macroW}×{@code macroH} macro cells, preserving overlap. */
  public void resizeMacro(int macroW, int macroH) {
    int nw = Math.max(1, macroW) * Dungeon.MACRO;
    int nh = Math.max(1, macroH) * Dungeon.MACRO;
    byte[][] grid = new byte[nw][nh];
    for (int x = 0; x < nw; x++) {
      for (int y = 0; y < nh; y++) {
        grid[x][y] = inBounds(x, y) ? cells[x][y] : SKIP;
      }
    }
    cells = grid;
    width = nw;
    height = nh;
  }

  private static char toChar(byte v) {
    return v == WALL ? '#' : (v == OPEN ? '.' : '_');
  }

  private static byte fromChar(char c) {
    return c == '#' ? WALL : (c == '.' ? OPEN : SKIP);
  }

  public String serialize() {
    StringBuilder sb = new StringBuilder();
    sb.append("template name=").append(KV.q(name))
        .append(" width=").append(width).append(" height=").append(height).append('\n');
    for (int y = 0; y < height; y++) {
      StringBuilder row = new StringBuilder(width);
      for (int x = 0; x < width; x++) {
        row.append(toChar(cells[x][y]));
      }
      sb.append("row y=").append(y).append(" cells=").append(KV.q(row.toString())).append('\n');
    }
    return sb.toString();
  }

  public void save(File file) throws Exception {
    Files.write(file.toPath(), serialize().getBytes(StandardCharsets.UTF_8));
  }

  public static Template load(File file) throws Exception {
    if (!file.exists() || file.length() == 0) {
      return new Template(stripExt(file.getName()), 1, 1);
    }
    Template t = new Template(stripExt(file.getName()), 1, 1);
    boolean sized = false;
    for (String line : Files.readAllLines(file.toPath())) {
      KV kv = KV.parse(line);
      if (kv == null) {
        continue;
      }
      if (kv.verb.equals("template")) {
        t.name = kv.get("name", t.name);
        int w = Dungeon.snap5(kv.getInt("width", 5));
        int h = Dungeon.snap5(kv.getInt("height", 5));
        t.width = w;
        t.height = h;
        t.cells = new byte[w][h];
        sized = true;
      } else if (kv.verb.equals("row") && sized) {
        int y = kv.getInt("y", -1);
        String cells = kv.get("cells", "");
        if (y >= 0 && y < t.height) {
          for (int x = 0; x < t.width && x < cells.length(); x++) {
            t.cells[x][y] = fromChar(cells.charAt(x));
          }
        }
      }
    }
    return t;
  }

  private static String stripExt(String n) {
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  // ----- built-in room stamps (macro-aligned) ---------------------------------

  public static List<Template> builtins() {
    List<Template> out = new ArrayList<>();
    out.add(fill("Room 1×1", 1, 1, OPEN));
    out.add(fill("Room 2×2", 2, 2, OPEN));
    out.add(hall("Hall 2×1", 2, 1));
    out.add(hall("Hall 1×2", 1, 2));
    out.add(walled("Walled room 2×2", 2, 2));
    out.add(pillar("Pillar room 1×1", 1, 1));
    out.add(diagonal("Diagonal 1×1", 1));
    return out;
  }

  private static Template fill(String name, int mw, int mh, byte v) {
    Template t = new Template(name, mw, mh);
    for (int x = 0; x < t.width; x++) {
      for (int y = 0; y < t.height; y++) {
        t.cells[x][y] = v;
      }
    }
    return t;
  }

  private static Template hall(String name, int mw, int mh) {
    return fill(name, mw, mh, OPEN);
  }

  private static Template walled(String name, int mw, int mh) {
    Template t = new Template(name, mw, mh);
    for (int x = 0; x < t.width; x++) {
      for (int y = 0; y < t.height; y++) {
        boolean edge = x == 0 || y == 0 || x == t.width - 1 || y == t.height - 1;
        t.cells[x][y] = edge ? WALL : OPEN;
      }
    }
    return t;
  }

  private static Template pillar(String name, int mw, int mh) {
    Template t = fill(name, mw, mh, OPEN);
    t.cells[t.width / 2][t.height / 2] = WALL;
    return t;
  }

  private static Template diagonal(String name, int m) {
    Template t = new Template(name, m, m);
    for (int x = 0; x < t.width; x++) {
      for (int y = 0; y < t.height; y++) {
        t.cells[x][y] = (x == y) ? WALL : SKIP;
      }
    }
    return t;
  }
}
