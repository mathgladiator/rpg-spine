package mg.editor.dungeon;

import mg.editor.KV;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The in-memory model for a .dungeon file, redesigned around a simple on/off
 * occupancy grid instead of per-edge walls.
 *
 * <h2>Two grids</h2>
 * Every level is a fine <b>micro grid</b> of cells whose dimensions are multiples
 * of {@link #MACRO} (5). Player movement happens on the coarser <b>macro grid</b>
 * (one macro cell = 5&times;5 micro cells). Interactive features (ladders, holes,
 * portals) live at the <em>center</em> of a macro cell; monsters may sit on any
 * micro cell and carry their own size (1&ndash;5 micro cells).
 *
 * <h2>Walls are implicit</h2>
 * There is no wall/door/edge data. Each micro cell just references a
 * {@link Material} in the shared {@link #palette}; a material is either a
 * {@link Kind#FLOOR} (open / walkable) or a {@link Kind#WALL} (occupied / solid).
 * The smooth wall surface the renderer draws is <em>inferred</em> from this
 * occupancy field by marching squares (see {@code documents/DUNGEON_WALLS.md});
 * each wall material's {@code weight} sets how sharp (stone) or smooth (dirt) that
 * boundary is.
 *
 * <p>Stored as a flat, line-oriented {@link KV} document: one {@code mat} line per
 * palette entry, then per level a header, one {@code row} line per micro row
 * (base-36 palette indices), and {@code feature}/{@code mon} lines.
 */
public class Dungeon {

  /** micro cells per macro cell, on each axis. */
  public static final int MACRO = 5;

  /** a palette entry is either open floor or solid wall. */
  public enum Kind { FLOOR, WALL }

  /**
   * What a feature does; all anchor to a macro-cell center. {@link #TARGET} is a
   * named destination any cell can become; ladders/holes/portals send the party to
   * a target <em>by id</em> rather than by coordinates, so moving a target never
   * breaks its references.
   */
  public enum FeatureType { LADDER_UP, LADDER_DOWN, HOLE, PORTAL, TARGET }

  /** a paint material: a colour plus whether it is floor (open) or wall (occupied). */
  public static final class Material {
    public String name;
    public String color;   // "#rrggbb"
    public Kind kind;
    public int weight;     // wall sharpness 0..100 (0 = smooth/dirt, 100 = sharp/stone); floors ignore

    public Material(String name, String color, Kind kind, int weight) {
      this.name = name;
      this.color = color;
      this.kind = kind;
      this.weight = weight;
    }

    public boolean isWall() {
      return kind == Kind.WALL;
    }
  }

  /** a ladder/hole/portal/target anchored to the center of macro cell (mx,my). */
  public static final class Feature {
    public FeatureType type = FeatureType.LADDER_DOWN;
    public int mx;
    public int my;
    /** for TARGET: this destination's name. */
    public String id = "";
    /** for ladder/hole/portal: the target id the party is sent to. */
    public String dest = "";
    public String note = "";
  }

  /** a single monster placed at micro cell (x,y); its size lives in its .monster file. */
  public static final class MonsterPlacement {
    public String monsterId = "";
    public int x;
    public int y;
  }

  /** one micro-grid level. */
  public static final class Level {
    public String name;
    public int width;        // micro cells (multiple of MACRO)
    public int height;       // micro cells (multiple of MACRO)
    public int[][] cells;    // palette index per micro cell, [x][y]
    public final List<Feature> features = new ArrayList<>();
    public final List<MonsterPlacement> monsters = new ArrayList<>();

    public Level(String name, int width, int height, int fill) {
      this.name = name;
      this.width = snap5(width);
      this.height = snap5(height);
      this.cells = new int[this.width][this.height];
      for (int[] col : cells) {
        Arrays.fill(col, fill);
      }
    }

    public int macroW() {
      return width / MACRO;
    }

    public int macroH() {
      return height / MACRO;
    }

    public boolean inBounds(int x, int y) {
      return x >= 0 && y >= 0 && x < width && y < height;
    }

    /** palette index at (x,y), or -1 out of bounds. */
    public int cell(int x, int y) {
      return inBounds(x, y) ? cells[x][y] : -1;
    }

    /** resize (snapping to multiples of MACRO), preserving overlapping cells. */
    public void resize(int newW, int newH, int fill) {
      int nw = snap5(newW);
      int nh = snap5(newH);
      int[][] grid = new int[nw][nh];
      for (int x = 0; x < nw; x++) {
        for (int y = 0; y < nh; y++) {
          grid[x][y] = (x < width && y < height) ? cells[x][y] : fill;
        }
      }
      cells = grid;
      width = nw;
      height = nh;
    }
  }

  // ----- top level ------------------------------------------------------------

  public String name = "Dungeon";
  public final List<Material> palette = new ArrayList<>();
  public final List<Level> levels = new ArrayList<>();

  public Material material(int i) {
    return (i >= 0 && i < palette.size()) ? palette.get(i) : null;
  }

  /** true if palette index {@code i} is a wall (occupied). Unknown indices read as solid. */
  public boolean isWall(int i) {
    Material m = material(i);
    return m == null || m.isWall();
  }

  /** the first wall material's index (the solid-rock fill), or 0. */
  public int defaultWallIndex() {
    for (int i = 0; i < palette.size(); i++) {
      if (palette.get(i).isWall()) {
        return i;
      }
    }
    return 0;
  }

  /** the first floor material's index, or 0. */
  public int defaultFloorIndex() {
    for (int i = 0; i < palette.size(); i++) {
      if (!palette.get(i).isWall()) {
        return i;
      }
    }
    return 0;
  }

  /** occupancy with out-of-bounds treated as solid rock — the renderer's wall field. */
  public boolean occupied(Level lv, int x, int y) {
    if (!lv.inBounds(x, y)) {
      return true;
    }
    return isWall(lv.cells[x][y]);
  }

  public static Dungeon blank() {
    Dungeon d = new Dungeon();
    d.palette.addAll(defaultPalette());
    d.levels.add(new Level("Level 1", 20, 20, d.defaultWallIndex()));
    return d;
  }

  /** the seed palette: a few wall materials (varied sharpness) and floor materials. */
  public static List<Material> defaultPalette() {
    List<Material> p = new ArrayList<>();
    p.add(new Material("stone", "#7d7d7d", Kind.WALL, 100)); // sharp
    p.add(new Material("brick", "#8a5a3b", Kind.WALL, 80));
    p.add(new Material("dirt", "#6b4f3a", Kind.WALL, 15));   // smooth
    p.add(new Material("ice", "#bfe3ef", Kind.WALL, 55));
    p.add(new Material("floor", "#c2a86a", Kind.FLOOR, 0));
    p.add(new Material("grass", "#5b8c46", Kind.FLOOR, 0));
    p.add(new Material("water", "#3f6fb0", Kind.FLOOR, 0));
    p.add(new Material("lava", "#c0392b", Kind.FLOOR, 0));
    return p;
  }

  /** round up to a multiple of {@link #MACRO} (minimum one macro cell). */
  public static int snap5(int n) {
    int m = ((n + MACRO - 1) / MACRO) * MACRO;
    return Math.max(MACRO, m);
  }

  // ----- serialization --------------------------------------------------------

  public String serialize() {
    StringBuilder sb = new StringBuilder();
    sb.append("dungeon name=").append(KV.q(name)).append('\n');
    for (Material m : palette) {
      sb.append("mat name=").append(KV.q(m.name))
          .append(" color=").append(KV.q(m.color))
          .append(" kind=").append(m.kind.name().toLowerCase())
          .append(" weight=").append(m.weight)
          .append('\n');
    }
    for (int li = 0; li < levels.size(); li++) {
      Level lv = levels.get(li);
      sb.append("level index=").append(li)
          .append(" name=").append(KV.q(lv.name))
          .append(" width=").append(lv.width)
          .append(" height=").append(lv.height)
          .append('\n');
      for (int y = 0; y < lv.height; y++) {
        StringBuilder row = new StringBuilder(lv.width);
        for (int x = 0; x < lv.width; x++) {
          row.append(Character.forDigit(Math.max(0, Math.min(35, lv.cells[x][y])), 36));
        }
        sb.append("row y=").append(y).append(" cells=").append(KV.q(row.toString())).append('\n');
      }
      for (Feature f : lv.features) {
        sb.append("feature type=").append(f.type.name().toLowerCase())
            .append(" mx=").append(f.mx).append(" my=").append(f.my);
        if (!f.id.isEmpty()) {
          sb.append(" id=").append(KV.q(f.id));
        }
        if (!f.dest.isEmpty()) {
          sb.append(" dest=").append(KV.q(f.dest));
        }
        if (!f.note.isEmpty()) {
          sb.append(" note=").append(KV.q(f.note));
        }
        sb.append('\n');
      }
      for (MonsterPlacement mp : lv.monsters) {
        sb.append("mon id=").append(KV.q(mp.monsterId))
            .append(" x=").append(mp.x).append(" y=").append(mp.y).append('\n');
      }
    }
    return sb.toString();
  }

  public void save(File file) throws Exception {
    Files.write(file.toPath(), serialize().getBytes(StandardCharsets.UTF_8));
  }

  /** every monster id placed anywhere in the dungeon (for the audit). */
  public List<String> monsterIds() {
    List<String> out = new ArrayList<>();
    for (Level lv : levels) {
      for (MonsterPlacement mp : lv.monsters) {
        if (mp.monsterId != null && !mp.monsterId.isBlank()) {
          out.add(mp.monsterId);
        }
      }
    }
    return out;
  }

  /** every named TARGET id defined anywhere in the dungeon (destinations to pick from). */
  public List<String> targetIds() {
    List<String> out = new ArrayList<>();
    for (Level lv : levels) {
      for (Feature f : lv.features) {
        if (f.type == FeatureType.TARGET && f.id != null && !f.id.isBlank()) {
          out.add(f.id);
        }
      }
    }
    return out;
  }

  public static Dungeon load(File file) throws Exception {
    if (!file.exists() || file.length() == 0) {
      Dungeon d = blank();
      d.name = stripExt(file.getName());
      return d;
    }
    Dungeon d = new Dungeon();
    Level cur = null;
    for (String line : Files.readAllLines(file.toPath())) {
      KV kv = KV.parse(line);
      if (kv == null) {
        continue;
      }
      switch (kv.verb) {
        case "dungeon" -> d.name = kv.get("name", "Dungeon");
        case "mat" -> {
          Kind kind = "floor".equalsIgnoreCase(kv.get("kind", "wall")) ? Kind.FLOOR : Kind.WALL;
          d.palette.add(new Material(
              kv.get("name", "mat"), kv.get("color", "#7d7d7d"), kind, kv.getInt("weight", kind == Kind.WALL ? 100 : 0)));
        }
        case "level" -> {
          if (d.palette.isEmpty()) {
            d.palette.addAll(defaultPalette());
          }
          cur = new Level(kv.get("name", "Level"), kv.getInt("width", 20), kv.getInt("height", 20), d.defaultWallIndex());
          d.levels.add(cur);
        }
        case "row" -> {
          if (cur == null) {
            break;
          }
          int y = kv.getInt("y", -1);
          String cells = kv.get("cells", "");
          if (y >= 0 && y < cur.height) {
            for (int x = 0; x < cur.width && x < cells.length(); x++) {
              int idx = Character.digit(cells.charAt(x), 36);
              cur.cells[x][y] = idx < 0 ? 0 : idx;
            }
          }
        }
        case "feature" -> {
          if (cur == null) {
            break;
          }
          Feature f = new Feature();
          f.type = parseFeature(kv.get("type", "ladder_down"));
          f.mx = kv.getInt("mx", 0);
          f.my = kv.getInt("my", 0);
          f.id = kv.get("id", "");
          f.dest = kv.get("dest", "");
          f.note = kv.get("note", "");
          cur.features.add(f);
        }
        case "mon" -> {
          if (cur == null) {
            break;
          }
          MonsterPlacement mp = new MonsterPlacement();
          mp.monsterId = kv.get("id", "");
          mp.x = kv.getInt("x", 0);
          mp.y = kv.getInt("y", 0);
          cur.monsters.add(mp);
        }
        default -> { /* ignore unknown verbs for forward-compat */ }
      }
    }
    if (d.palette.isEmpty()) {
      d.palette.addAll(defaultPalette());
    }
    if (d.levels.isEmpty()) {
      d.levels.add(new Level("Level 1", 20, 20, d.defaultWallIndex()));
    }
    return d;
  }

  private static String stripExt(String n) {
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  private static FeatureType parseFeature(String s) {
    try {
      return FeatureType.valueOf(s.toUpperCase());
    } catch (Exception e) {
      return FeatureType.LADDER_DOWN;
    }
  }
}
