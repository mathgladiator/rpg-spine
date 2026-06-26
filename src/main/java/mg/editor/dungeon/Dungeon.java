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
   * How a macro cell's 5&times;5 wall region is rendered/collided. Each macro cell
   * picks its own so a map can mix styles:
   * <ul>
   *   <li>{@link #MARCHING} — the original per-cell rounded blob (convex corners
   *       rounded by weight); orthogonal, no diagonal bridging.
   *   <li>{@link #DIAGONAL} — dual-grid marching squares that infers diagonal
   *       lines from staggered cells.
   *   <li>{@link #SQUARES} — plain on/off blocks; weight ignored.
   * </ul>
   */
  public enum Fill {
    MARCHING('m'), DIAGONAL('d'), SQUARES('s');

    public final char code;

    Fill(char code) {
      this.code = code;
    }

    public static Fill fromCode(char c) {
      for (Fill f : values()) {
        if (f.code == c) {
          return f;
        }
      }
      return MARCHING;
    }
  }

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

  /**
   * A named rectangular region with a runtime boolean (default off). Toggling it
   * repaints its micro cells to the {@code on} or {@code off} palette material —
   * the mechanism for hidden doorways: off = solid wall, on = open floor. The
   * boolean is meant to be mutable at runtime in the C engine (flip → repaint the
   * rect → the ray caster sees new occupancy), so it stays trivial to implement.
   */
  public static final class Region {
    public String name = "region";
    public int x;
    public int y;
    public int w;
    public int h;
    public int onIndex;   // palette material when on
    public int offIndex;  // palette material when off
    public boolean on = false;

    /** the palette index this region currently paints. */
    public int currentIndex() {
      return on ? onIndex : offIndex;
    }
  }

  /** a cardinal facing, listed clockwise from north (the inference scan order). */
  public enum Dir { N, E, S, W }

  /** a small decorative/interactive object on a micro cell, facing {@link #dir}. */
  public static final class Doodad {
    public int x;
    public int y;
    public String id = "doodad";
    public Dir dir = Dir.N;
  }

  /** the maximum number of doodads a single micro cell may hold. */
  public static final int MAX_DOODADS = 3;

  /**
   * The orientation of a door's panel — the line through its two anchor points.
   * {@link #EW} anchors East&amp;West (panel spans horizontally) and gates N&ndash;S
   * travel; {@link #NS} anchors North&amp;South and gates E&ndash;W travel.
   */
  public enum DoorAxis { NS, EW }

  /** how a door is gated. */
  public enum DoorLock {
    /** any interaction opens it (a plain push-door). */
    UNLOCKED,
    /** opens only while the party carries the item named by {@link Door#key}. */
    KEY,
    /** opened/closed solely by the script {@link Door#event} (a lever, a boss death). */
    EVENT
  }

  /**
   * A door on a <b>fully-open macro cell</b>. A door is <em>orthogonal</em> to the
   * occupancy grid — it never repaints cells; it is a movement gate at the macro
   * center that the C engine consults independently of the inferred walls.
   *
   * <h3>Placement contract</h3>
   * A door is valid only when its macro cell is entirely open floor and the cell has
   * <b>exactly two anchor points opposite each other through the center</b>: the two
   * macro-edge neighbours along {@link #axis} are solid rock (the jambs the panel
   * hangs from) while the perpendicular pair are open (the corridor it gates). This
   * is checked by {@link Dungeon#doorValid}; the editor infers {@link #axis} on
   * placement via {@link Dungeon#inferDoorAxis} and rejects ambiguous cells (T/cross
   * junctions, dead ends).
   *
   * <p>{@link #open} is the runtime initial state — a mutable overlay bit in the save
   * document, like {@link Region#on}. {@code key}/{@code event} bind to ids resolved
   * at codegen time (see {@code documents/design.dvm.md}).
   */
  public static final class Door {
    public int mx;
    public int my;
    public DoorAxis axis = DoorAxis.EW;
    public DoorLock lock = DoorLock.UNLOCKED;
    /** required item id when {@link #lock} is {@link DoorLock#KEY}. */
    public String key = "";
    /** controlling script event id when {@link #lock} is {@link DoorLock#EVENT}. */
    public String event = "";
    /** initial open/closed state (mutable at runtime). */
    public boolean open = false;
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
    public Fill[][] macroFill; // wall algorithm per macro cell, [mx][my]
    public final List<Feature> features = new ArrayList<>();
    public final List<MonsterPlacement> monsters = new ArrayList<>();
    public final List<Region> regions = new ArrayList<>();
    public final List<Doodad> doodads = new ArrayList<>();
    public final List<Door> doors = new ArrayList<>();

    /** the door on macro cell (mx,my), or null. */
    public Door doorAt(int mx, int my) {
      for (Door d : doors) {
        if (d.mx == mx && d.my == my) {
          return d;
        }
      }
      return null;
    }

    /** doodads sitting on micro cell (x,y). */
    public List<Doodad> doodadsAt(int x, int y) {
      List<Doodad> out = new ArrayList<>(MAX_DOODADS);
      for (Doodad d : doodads) {
        if (d.x == x && d.y == y) {
          out.add(d);
        }
      }
      return out;
    }

    public Level(String name, int width, int height, int fill) {
      this.name = name;
      this.width = snap5(width);
      this.height = snap5(height);
      this.cells = new int[this.width][this.height];
      for (int[] col : cells) {
        Arrays.fill(col, fill);
      }
      this.macroFill = new Fill[macroW()][macroH()];
      for (Fill[] col : macroFill) {
        Arrays.fill(col, Fill.MARCHING);
      }
    }

    /** the wall algorithm for macro cell (mx,my); MARCHING out of range. */
    public Fill fillAt(int mx, int my) {
      if (mx < 0 || my < 0 || mx >= macroW() || my >= macroH()) {
        return Fill.MARCHING;
      }
      return macroFill[mx][my];
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
      int oldMacroW = macroFill.length;
      int oldMacroH = oldMacroW > 0 ? macroFill[0].length : 0;
      Fill[][] mf = new Fill[nw / MACRO][nh / MACRO];
      for (int mx = 0; mx < mf.length; mx++) {
        for (int my = 0; my < mf[0].length; my++) {
          mf[mx][my] = (mx < oldMacroW && my < oldMacroH) ? macroFill[mx][my] : Fill.MARCHING;
        }
      }
      cells = grid;
      macroFill = mf;
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

  /**
   * Infer a doodad's facing from its cell's neighbours: the first <em>open</em>
   * (floor) neighbour scanning clockwise from north (N, E, S, W). So a doodad on a
   * wall with a single adjacent floor faces that floor; with several, it picks the
   * earliest in clockwise order. Defaults to {@link Dir#N} if fully walled in.
   */
  public Dir inferDir(Level lv, int x, int y) {
    int[][] off = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}; // N, E, S, W
    Dir[] dirs = {Dir.N, Dir.E, Dir.S, Dir.W};
    for (int i = 0; i < 4; i++) {
      int nx = x + off[i][0], ny = y + off[i][1];
      if (lv.inBounds(nx, ny) && !isWall(lv.cells[nx][ny])) {
        return dirs[i];
      }
    }
    return Dir.N;
  }

  /** true if every micro cell of macro cell (mx,my) is open floor (a door's chamber). */
  public boolean macroAllOpen(Level lv, int mx, int my) {
    for (int x = mx * MACRO; x < mx * MACRO + MACRO; x++) {
      for (int y = my * MACRO; y < my * MACRO + MACRO; y++) {
        if (occupied(lv, x, y)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Is the macro-edge neighbour in direction {@code d} open? Samples the cell one
   * step past this macro cell's edge-center micro cell (so a door reads the corridor
   * on the far side). Out of bounds reads as solid (the world's outer rock).
   */
  public boolean macroEdgeOpen(Level lv, int mx, int my, Dir d) {
    int cx = mx * MACRO + MACRO / 2;
    int cy = my * MACRO + MACRO / 2;
    int reach = MACRO / 2 + 1; // one step past this macro's edge into the neighbour
    int nx = cx, ny = cy;
    switch (d) {
      case N -> ny = cy - reach;
      case S -> ny = cy + reach;
      case W -> nx = cx - reach;
      case E -> nx = cx + reach;
    }
    return !occupied(lv, nx, ny);
  }

  /**
   * The door axis a fully-open macro cell can host, or null if none fits. A door
   * needs exactly one opposite pair of edges open (the corridor it gates) and the
   * other opposite pair solid (the two anchor points). Anything else — a dead end,
   * a corner, a T or cross junction — has no unambiguous axis and rejects.
   */
  public DoorAxis inferDoorAxis(Level lv, int mx, int my) {
    if (!macroAllOpen(lv, mx, my)) {
      return null;
    }
    boolean n = macroEdgeOpen(lv, mx, my, Dir.N), s = macroEdgeOpen(lv, mx, my, Dir.S);
    boolean e = macroEdgeOpen(lv, mx, my, Dir.E), w = macroEdgeOpen(lv, mx, my, Dir.W);
    if (n && s && !e && !w) {
      return DoorAxis.EW; // corridor N–S; anchors (solid) E & W
    }
    if (e && w && !n && !s) {
      return DoorAxis.NS; // corridor E–W; anchors (solid) N & S
    }
    return null;
  }

  /** whether {@code d} still satisfies the placement contract for its stored axis. */
  public boolean doorValid(Level lv, Door d) {
    return d != null && d.axis == inferDoorAxis(lv, d.mx, d.my);
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
      for (int my = 0; my < lv.macroH(); my++) {
        StringBuilder row = new StringBuilder(lv.macroW());
        for (int mx = 0; mx < lv.macroW(); mx++) {
          row.append(lv.macroFill[mx][my].code);
        }
        sb.append("mfill y=").append(my).append(" cells=").append(KV.q(row.toString())).append('\n');
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
      for (Region rg : lv.regions) {
        sb.append("region name=").append(KV.q(rg.name))
            .append(" x=").append(rg.x).append(" y=").append(rg.y)
            .append(" w=").append(rg.w).append(" h=").append(rg.h)
            .append(" on=").append(rg.onIndex).append(" off=").append(rg.offIndex)
            .append(" state=").append(rg.on).append('\n');
      }
      for (Doodad dd : lv.doodads) {
        sb.append("doodad x=").append(dd.x).append(" y=").append(dd.y)
            .append(" id=").append(KV.q(dd.id))
            .append(" dir=").append(dd.dir.name().toLowerCase()).append('\n');
      }
      for (Door dr : lv.doors) {
        sb.append("door mx=").append(dr.mx).append(" my=").append(dr.my)
            .append(" axis=").append(dr.axis.name().toLowerCase())
            .append(" lock=").append(dr.lock.name().toLowerCase());
        if (dr.lock == DoorLock.KEY && !dr.key.isEmpty()) {
          sb.append(" key=").append(KV.q(dr.key));
        }
        if (dr.lock == DoorLock.EVENT && !dr.event.isEmpty()) {
          sb.append(" event=").append(KV.q(dr.event));
        }
        sb.append(" open=").append(dr.open);
        if (!dr.note.isEmpty()) {
          sb.append(" note=").append(KV.q(dr.note));
        }
        sb.append('\n');
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
        case "mfill" -> {
          if (cur == null) {
            break;
          }
          int my = kv.getInt("y", -1);
          String cells = kv.get("cells", "");
          if (my >= 0 && my < cur.macroH()) {
            for (int mx = 0; mx < cur.macroW() && mx < cells.length(); mx++) {
              cur.macroFill[mx][my] = Fill.fromCode(cells.charAt(mx));
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
        case "region" -> {
          if (cur == null) {
            break;
          }
          Region rg = new Region();
          rg.name = kv.get("name", "region");
          rg.x = kv.getInt("x", 0);
          rg.y = kv.getInt("y", 0);
          rg.w = kv.getInt("w", 1);
          rg.h = kv.getInt("h", 1);
          rg.onIndex = kv.getInt("on", d.defaultFloorIndex());
          rg.offIndex = kv.getInt("off", d.defaultWallIndex());
          rg.on = kv.getBool("state", false);
          cur.regions.add(rg);
        }
        case "doodad" -> {
          if (cur == null) {
            break;
          }
          Doodad dd = new Doodad();
          dd.x = kv.getInt("x", 0);
          dd.y = kv.getInt("y", 0);
          dd.id = kv.get("id", "doodad");
          try {
            dd.dir = Dir.valueOf(kv.get("dir", "n").toUpperCase());
          } catch (Exception e) {
            dd.dir = Dir.N;
          }
          cur.doodads.add(dd);
        }
        case "door" -> {
          if (cur == null) {
            break;
          }
          Door dr = new Door();
          dr.mx = kv.getInt("mx", 0);
          dr.my = kv.getInt("my", 0);
          dr.axis = "ns".equalsIgnoreCase(kv.get("axis", "ew")) ? DoorAxis.NS : DoorAxis.EW;
          dr.lock = parseLock(kv.get("lock", "unlocked"));
          dr.key = kv.get("key", "");
          dr.event = kv.get("event", "");
          dr.open = kv.getBool("open", false);
          dr.note = kv.get("note", "");
          cur.doors.add(dr);
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

  private static DoorLock parseLock(String s) {
    try {
      return DoorLock.valueOf(s.toUpperCase());
    } catch (Exception e) {
      return DoorLock.UNLOCKED;
    }
  }
}
