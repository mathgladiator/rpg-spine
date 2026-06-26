package mg.editor.dungeon;

import mg.editor.KV;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable drawing stamp: a small grid of tri-state cells (wall / open / skip)
 * with no floor material or features — just occupancy. Templates are authored in
 * the template editor, saved as {@code .template}, and stamped into any dungeon
 * (with a live preview): {@code WALL} cells become the chosen wall material,
 * {@code OPEN} cells the chosen floor, and {@code SKIP} cells are left untouched
 * so a stamp only changes what it draws.
 *
 * <p>Serialized as KV: a {@code template} header then one {@code row} per line,
 * each cell a char — {@code #} wall, {@code .} open, {@code _} skip.
 */
public final class Template {

  public static final byte SKIP = 0;
  public static final byte WALL = 1;
  public static final byte OPEN = 2;

  public String name;
  public int width;
  public int height;
  public byte[][] cells; // [x][y]

  public Template(String name, int width, int height) {
    this.name = name;
    this.width = Math.max(1, width);
    this.height = Math.max(1, height);
    this.cells = new byte[this.width][this.height];
  }

  public boolean inBounds(int x, int y) {
    return x >= 0 && y >= 0 && x < width && y < height;
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
      Template t = new Template(stripExt(file.getName()), 7, 7);
      return t;
    }
    Template t = new Template(stripExt(file.getName()), 7, 7);
    boolean sized = false;
    List<String> rows = new ArrayList<>();
    for (String line : Files.readAllLines(file.toPath())) {
      KV kv = KV.parse(line);
      if (kv == null) {
        continue;
      }
      if (kv.verb.equals("template")) {
        t.name = kv.get("name", t.name);
        t.width = Math.max(1, kv.getInt("width", 7));
        t.height = Math.max(1, kv.getInt("height", 7));
        t.cells = new byte[t.width][t.height];
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

  // ----- built-in stamps ------------------------------------------------------

  /** the shipped library of drawing stamps (used alongside project .template files). */
  public static List<Template> builtins() {
    List<Template> out = new ArrayList<>();
    out.add(fill("Open room 5×5", 5, 5, OPEN));
    out.add(fill("Pillar 1×1", 1, 1, WALL));
    out.add(hollow("Walled room 7×7", 7, 7));
    out.add(fill("Corridor 5×1", 5, 1, OPEN));
    out.add(fill("Corridor 1×5", 1, 5, OPEN));
    out.add(cross("Cross 5×5", 5));
    out.add(disc("Disc 7×7", 7));
    out.add(diagonal("Diagonal wall 5×5", 5));
    return out;
  }

  private static Template fill(String name, int w, int h, byte v) {
    Template t = new Template(name, w, h);
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        t.cells[x][y] = v;
      }
    }
    return t;
  }

  private static Template hollow(String name, int w, int h) {
    Template t = new Template(name, w, h);
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1;
        t.cells[x][y] = edge ? WALL : OPEN;
      }
    }
    return t;
  }

  private static Template cross(String name, int n) {
    Template t = new Template(name, n, n);
    int mid = n / 2;
    for (int x = 0; x < n; x++) {
      for (int y = 0; y < n; y++) {
        t.cells[x][y] = (x == mid || y == mid) ? OPEN : SKIP;
      }
    }
    return t;
  }

  private static Template disc(String name, int n) {
    Template t = new Template(name, n, n);
    double c = (n - 1) / 2.0;
    double r = n / 2.0;
    for (int x = 0; x < n; x++) {
      for (int y = 0; y < n; y++) {
        t.cells[x][y] = Math.hypot(x - c, y - c) <= r ? OPEN : SKIP;
      }
    }
    return t;
  }

  private static Template diagonal(String name, int n) {
    Template t = new Template(name, n, n);
    for (int x = 0; x < n; x++) {
      for (int y = 0; y < n; y++) {
        t.cells[x][y] = (x == y) ? WALL : SKIP;
      }
    }
    return t;
  }
}
