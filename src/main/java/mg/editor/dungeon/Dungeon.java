package mg.editor.dungeon;

import mg.editor.KV;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-memory model for a .dungeon file: a stack of grid levels plus a shared
 * bestiary and encounter tables. Old-school (Wizardry / Bard's Tale) maps are a
 * grid of cells where the interesting design lives on the *edges* between cells
 * (walls, doors, secret doors) and in per-cell special tiles (spinners,
 * teleporters, chutes, darkness, fixed encounters). The format is a flat,
 * line-oriented {@link KV} document so it stays diff-friendly and is trivial for
 * the eventual C codegen to ingest.
 */
public class Dungeon {

  /** the four cardinal edges of a cell, in N,E,S,W order. */
  public enum Side { N, E, S, W }

  /** the walkability class of a cell (the base spec: open / closed / hole / ladder). */
  public enum CellState {
    OPEN,         // walkable floor
    CLOSED,       // solid rock, not enterable
    HOLE,         // a pit/chute: party falls to the level below
    LADDER_UP,    // climb to the level above
    LADDER_DOWN   // climb to the level below
  }

  /** what sits on one edge of a cell. */
  public enum WallType {
    NONE('.'),     // open passage
    WALL('#'),     // solid wall
    DOOR('D'),     // a normal door
    LOCKED('L'),   // a locked door (needs a key/flag)
    SECRET('S'),   // a secret door, indistinguishable from wall until searched
    GRATE('G'),    // a portcullis/grate: see through, can't pass
    ARCH('A');     // an archway: passable but visually a boundary

    public final char code;
    WallType(char code) { this.code = code; }

    public static WallType fromCode(char c) {
      for (WallType w : values()) {
        if (w.code == c) return w;
      }
      return NONE;
    }
  }

  /** a per-cell special tile that triggers logic when the party enters. */
  public enum Special {
    NONE,
    SPINNER,    // randomly re-faces the party
    TELEPORT,   // jumps the party to target (level,x,y)
    ANTIMAGIC,  // magic is suppressed here
    PIT_TRAP,   // a hidden pit (damage / fall)
    STAIRS,     // explicit stairs to target (level,x,y)
    ELEVATOR,   // multi-level lift to target level
    FOUNTAIN,   // heal / restore point
    DARK_FORCE  // forced darkness override even with light
  }

  // ----- bestiary -------------------------------------------------------------

  /** a single monster definition shared across the dungeon. */
  public static class MonsterDef {
    public String id = "monster";
    public String name = "Monster";
    public int hp = 8;
    public int ac = 6;
    public int atk = 3;
    public int xp = 10;
    public String sprite = "";
  }

  /** a named encounter table: members are "monsterId:min-max" entries. */
  public static class MonsterGroup {
    public String id = "group";
    public final List<Member> members = new ArrayList<>();

    public static class Member {
      public String monsterId;
      public int min;
      public int max;
      public Member(String monsterId, int min, int max) {
        this.monsterId = monsterId;
        this.min = min;
        this.max = max;
      }
    }

    public String membersToText() {
      StringBuilder sb = new StringBuilder();
      for (Member m : members) {
        if (sb.length() > 0) sb.append(',');
        sb.append(m.monsterId).append(':').append(m.min).append('-').append(m.max);
      }
      return sb.toString();
    }

    public void membersFromText(String text) {
      members.clear();
      if (text == null) return;
      for (String part : text.split(",")) {
        String p = part.strip();
        if (p.isEmpty()) continue;
        try {
          int colon = p.indexOf(':');
          String id = colon >= 0 ? p.substring(0, colon) : p;
          int min = 1, max = 1;
          if (colon >= 0) {
            String range = p.substring(colon + 1);
            int dash = range.indexOf('-');
            if (dash >= 0) {
              min = Integer.parseInt(range.substring(0, dash).strip());
              max = Integer.parseInt(range.substring(dash + 1).strip());
            } else {
              min = max = Integer.parseInt(range.strip());
            }
          }
          members.add(new Member(id, min, max));
        } catch (Exception ignore) {
          // skip malformed members; the editor validates separately
        }
      }
    }
  }

  // ----- cells & levels -------------------------------------------------------

  /** one grid cell. */
  public static class Cell {
    public CellState state = CellState.OPEN;
    public String floor = "stone";
    public String wall = "stone";
    public final WallType[] edges = {WallType.NONE, WallType.NONE, WallType.NONE, WallType.NONE};
    public boolean dark = false;
    public Special special = Special.NONE;
    public int targetLevel = -1;
    public int targetX = -1;
    public int targetY = -1;
    public String message = "";
    public String note = "";
    public String encounter = "";   // group id, or empty
    public int encounterPct = 0;    // 0..100

    public WallType edge(Side s) { return edges[s.ordinal()]; }
    public void edge(Side s, WallType w) { edges[s.ordinal()] = w; }

    public String edgesCode() {
      return "" + edges[0].code + edges[1].code + edges[2].code + edges[3].code;
    }

    public boolean isDefault(String defFloor, String defWall) {
      return state == CellState.OPEN
          && floor.equals(defFloor) && wall.equals(defWall)
          && edges[0] == WallType.NONE && edges[1] == WallType.NONE
          && edges[2] == WallType.NONE && edges[3] == WallType.NONE
          && !dark && special == Special.NONE
          && message.isEmpty() && note.isEmpty() && encounter.isEmpty() && encounterPct == 0;
    }
  }

  /** one grid level of the dungeon. */
  public static class Level {
    public int width;
    public int height;
    public String name;
    public boolean dark;
    public Cell[][] cells;   // [x][y]

    public Level(int width, int height, String name) {
      this.width = width;
      this.height = height;
      this.name = name;
      this.dark = false;
      cells = new Cell[width][height];
      for (int x = 0; x < width; x++) {
        for (int y = 0; y < height; y++) {
          cells[x][y] = new Cell();
        }
      }
    }

    public Cell at(int x, int y) {
      if (x < 0 || y < 0 || x >= width || y >= height) return null;
      return cells[x][y];
    }

    /** resize, preserving overlapping cells. */
    public void resize(int newW, int newH) {
      Cell[][] grid = new Cell[newW][newH];
      for (int x = 0; x < newW; x++) {
        for (int y = 0; y < newH; y++) {
          grid[x][y] = (x < width && y < height) ? cells[x][y] : new Cell();
        }
      }
      cells = grid;
      width = newW;
      height = newH;
    }
  }

  // ----- top level ------------------------------------------------------------

  public String name = "Dungeon";
  public final List<String> floorPalette = new ArrayList<>(List.of(
      "stone", "dirt", "water", "lava", "grass", "wood", "sand", "ice", "void"));
  public final List<String> wallPalette = new ArrayList<>(List.of(
      "stone", "brick", "wood", "rune", "ice", "bone", "moss"));
  public final List<Level> levels = new ArrayList<>();
  public final List<MonsterDef> monsters = new ArrayList<>();
  public final List<MonsterGroup> groups = new ArrayList<>();

  public String defFloor() { return floorPalette.isEmpty() ? "stone" : floorPalette.get(0); }
  public String defWall() { return wallPalette.isEmpty() ? "stone" : wallPalette.get(0); }

  public static Dungeon blank() {
    Dungeon d = new Dungeon();
    d.levels.add(new Level(20, 20, "Level 1"));
    return d;
  }

  // ----- serialization --------------------------------------------------------

  public String serialize() {
    StringBuilder sb = new StringBuilder();
    sb.append("dungeon name=").append(KV.q(name)).append('\n');
    sb.append("floors ").append(String.join(",", floorPalette)).append('\n');
    sb.append("walls ").append(String.join(",", wallPalette)).append('\n');
    for (MonsterDef m : monsters) {
      sb.append("monster id=").append(KV.q(m.id))
          .append(" name=").append(KV.q(m.name))
          .append(" hp=").append(m.hp)
          .append(" ac=").append(m.ac)
          .append(" atk=").append(m.atk)
          .append(" xp=").append(m.xp)
          .append(" sprite=").append(KV.q(m.sprite))
          .append('\n');
    }
    for (MonsterGroup g : groups) {
      sb.append("group id=").append(KV.q(g.id))
          .append(" members=").append(KV.q(g.membersToText()))
          .append('\n');
    }
    for (int li = 0; li < levels.size(); li++) {
      Level lv = levels.get(li);
      sb.append("level index=").append(li)
          .append(" width=").append(lv.width)
          .append(" height=").append(lv.height)
          .append(" name=").append(KV.q(lv.name))
          .append(" dark=").append(lv.dark)
          .append('\n');
      for (int y = 0; y < lv.height; y++) {
        for (int x = 0; x < lv.width; x++) {
          Cell c = lv.cells[x][y];
          if (c.isDefault(defFloor(), defWall())) continue;
          sb.append("cell ").append(x).append(',').append(y)
              .append(" state=").append(c.state.name().toLowerCase())
              .append(" floor=").append(KV.q(c.floor))
              .append(" wall=").append(KV.q(c.wall))
              .append(" edges=").append(c.edgesCode())
              .append(" dark=").append(c.dark)
              .append(" special=").append(c.special.name().toLowerCase());
          if (c.special != Special.NONE && c.targetLevel >= 0) {
            sb.append(" tgt=").append(c.targetLevel).append(':').append(c.targetX).append(',').append(c.targetY);
          }
          if (!c.message.isEmpty()) sb.append(" msg=").append(KV.q(c.message));
          if (!c.note.isEmpty()) sb.append(" note=").append(KV.q(c.note));
          if (!c.encounter.isEmpty()) {
            sb.append(" enc=").append(KV.q(c.encounter)).append(" encpct=").append(c.encounterPct);
          }
          sb.append('\n');
        }
      }
    }
    return sb.toString();
  }

  public void save(File file) throws Exception {
    Files.write(file.toPath(), serialize().getBytes(StandardCharsets.UTF_8));
  }

  public static Dungeon load(File file) throws Exception {
    if (!file.exists() || file.length() == 0) {
      Dungeon d = blank();
      d.name = stripExt(file.getName());
      return d;
    }
    Dungeon d = new Dungeon();
    Level cur = null;
    boolean sawFloors = false, sawWalls = false;
    for (String line : Files.readAllLines(file.toPath())) {
      KV kv = KV.parse(line);
      if (kv == null) continue;
      switch (kv.verb) {
        case "dungeon" -> d.name = kv.get("name", "Dungeon");
        case "floors" -> {
          d.floorPalette.clear();
          for (String s : firstAttrCsv(kv)) d.floorPalette.add(s);
          sawFloors = true;
        }
        case "walls" -> {
          d.wallPalette.clear();
          for (String s : firstAttrCsv(kv)) d.wallPalette.add(s);
          sawWalls = true;
        }
        case "monster" -> {
          MonsterDef m = new MonsterDef();
          m.id = kv.get("id", "monster");
          m.name = kv.get("name", m.id);
          m.hp = kv.getInt("hp", 8);
          m.ac = kv.getInt("ac", 6);
          m.atk = kv.getInt("atk", 3);
          m.xp = kv.getInt("xp", 10);
          m.sprite = kv.get("sprite", "");
          d.monsters.add(m);
        }
        case "group" -> {
          MonsterGroup g = new MonsterGroup();
          g.id = kv.get("id", "group");
          g.membersFromText(kv.get("members", ""));
          d.groups.add(g);
        }
        case "level" -> {
          cur = new Level(kv.getInt("width", 20), kv.getInt("height", 20), kv.get("name", "Level"));
          cur.dark = kv.getBool("dark", false);
          d.levels.add(cur);
        }
        case "cell" -> {
          if (cur == null) break;
          String[] xy = firstAttrKey(kv).split(",");
          if (xy.length != 2) break;
          int x = parseInt(xy[0], -1), y = parseInt(xy[1], -1);
          Cell c = cur.at(x, y);
          if (c == null) break;
          c.state = parseState(kv.get("state", "open"));
          c.floor = kv.get("floor", d.defFloor());
          c.wall = kv.get("wall", d.defWall());
          String edges = kv.get("edges", "....");
          for (int i = 0; i < 4 && i < edges.length(); i++) {
            c.edges[i] = WallType.fromCode(edges.charAt(i));
          }
          c.dark = kv.getBool("dark", false);
          c.special = parseSpecial(kv.get("special", "none"));
          String tgt = kv.get("tgt", "");
          if (!tgt.isEmpty()) {
            try {
              int colon = tgt.indexOf(':');
              c.targetLevel = Integer.parseInt(tgt.substring(0, colon));
              String[] txy = tgt.substring(colon + 1).split(",");
              c.targetX = Integer.parseInt(txy[0]);
              c.targetY = Integer.parseInt(txy[1]);
            } catch (Exception ignore) { }
          }
          c.message = kv.get("msg", "");
          c.note = kv.get("note", "");
          c.encounter = kv.get("enc", "");
          c.encounterPct = kv.getInt("encpct", 0);
        }
        default -> { /* ignore unknown verbs for forward-compat */ }
      }
    }
    if (!sawFloors) { /* keep defaults */ }
    if (!sawWalls) { /* keep defaults */ }
    if (d.levels.isEmpty()) d.levels.add(new Level(20, 20, "Level 1"));
    return d;
  }

  private static String stripExt(String n) {
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  /** the CSV payload of a verb whose payload was stored as the first bare token (floors/walls). */
  private static List<String> firstAttrCsv(KV kv) {
    String key = firstAttrKey(kv);
    List<String> out = new ArrayList<>();
    for (String s : key.split(",")) {
      String t = s.strip();
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }

  /** the first attribute key (used where the payload is a bare token, e.g. "3,4" or a CSV). */
  private static String firstAttrKey(KV kv) {
    for (String k : kv.attrs.keySet()) {
      return k;
    }
    return "";
  }

  private static int parseInt(String s, int dflt) {
    try { return Integer.parseInt(s.strip()); } catch (Exception e) { return dflt; }
  }

  private static CellState parseState(String s) {
    try { return CellState.valueOf(s.toUpperCase()); } catch (Exception e) { return CellState.OPEN; }
  }

  private static Special parseSpecial(String s) {
    try { return Special.valueOf(s.toUpperCase()); } catch (Exception e) { return Special.NONE; }
  }
}
