package mg.editor.monster;

import mg.editor.KV;
import mg.editor.asset.Animation;
import mg.editor.asset.ExtractSettings;
import mg.editor.asset.SkeletonData;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-memory model for a .monster file.
 *
 * <p>Art is built from explicit image files:
 * <ul>
 *   <li><b>References</b> — full-colour {@code .ref.png} view-only images used to
 *       feed the AI and to extract black-and-white frames from.
 *   <li><b>Battle</b> — a {@code stance} image, a {@code damage} (damaged stance)
 *       image, and an {@code attack} animation.
 *   <li><b>Dungeon</b> — {@code idle} and {@code walk}, each in four viewer
 *       orientations (towards / away / left / right), every one an animation.
 * </ul>
 * Plus the numeric design: a per-level stat table and a skills list. Stored as a
 * flat {@link KV} document.
 */
public class Monster {

  /** viewer-relative orientations for dungeon (first-person) animations. */
  public static final String[] ORIENTATIONS = {"towards", "away", "left", "right"};

  /** one row of the per-level stat table; values keyed by stat-column name. */
  public static class LevelRow {
    public int level;
    public final Map<String, Integer> stats = new LinkedHashMap<>();

    public LevelRow(int level) {
      this.level = level;
    }

    public int get(String col) {
      Integer v = stats.get(col);
      return v == null ? 0 : v;
    }
  }

  /** a skill the monster can use. */
  public static class Skill {
    public String name = "Skill";
    public int learnLevel = 1;
    public int mp = 0;
    public int power = 0;
    public String type = "";
  }

  public String id = "monster";
  public String name = "Monster";
  public String family = "";

  public final List<String> references = new ArrayList<>();

  public String battleStance = "";
  public String battleDamage = "";
  public final Animation battleAttack = new Animation();

  public final Map<String, Animation> dungeonIdle = new LinkedHashMap<>();
  public final Map<String, Animation> dungeonWalk = new LinkedHashMap<>();

  /** cached editable skeletons keyed by reference-image path. */
  public final Map<String, SkeletonData> skeletons = new LinkedHashMap<>();

  /** last-used reference → B&W extract settings (reseeds the next extract). */
  public final ExtractSettings extract = new ExtractSettings();

  public final List<String> statColumns = new ArrayList<>(List.of(
      "HP", "MP", "ATK", "DEF", "SPD", "XP", "GOLD"));
  public final List<LevelRow> levels = new ArrayList<>();
  public final List<Skill> skills = new ArrayList<>();

  {
    for (String o : ORIENTATIONS) {
      dungeonIdle.put(o, new Animation());
      dungeonWalk.put(o, new Animation());
    }
  }

  public static Monster blank(String name) {
    Monster m = new Monster();
    m.id = name;
    m.name = name;
    LevelRow row = new LevelRow(1);
    for (String c : m.statColumns) {
      row.stats.put(c, 0);
    }
    row.stats.put("HP", 10);
    m.levels.add(row);
    return m;
  }

  /** the animation a serialized role maps to, or null for an unknown role. */
  public Animation animFor(String role) {
    if (role == null) {
      return null;
    }
    if (role.equals("battle.attack")) {
      return battleAttack;
    }
    if (role.startsWith("dungeon.idle.")) {
      return dungeonIdle.get(role.substring("dungeon.idle.".length()));
    }
    if (role.startsWith("dungeon.walk.")) {
      return dungeonWalk.get(role.substring("dungeon.walk.".length()));
    }
    return null;
  }

  public void addStatColumn(String col) {
    if (col == null || col.isBlank() || statColumns.contains(col)) {
      return;
    }
    statColumns.add(col);
    for (LevelRow r : levels) {
      r.stats.putIfAbsent(col, 0);
    }
  }

  public void removeStatColumn(String col) {
    statColumns.remove(col);
    for (LevelRow r : levels) {
      r.stats.remove(col);
    }
  }

  public LevelRow addLevel() {
    int next = levels.isEmpty() ? 1 : levels.get(levels.size() - 1).level + 1;
    LevelRow row = new LevelRow(next);
    for (String c : statColumns) {
      row.stats.put(c, 0);
    }
    levels.add(row);
    return row;
  }

  /** every image path this monster references (relative to its folder). */
  public List<String> imageRefs() {
    List<String> out = new ArrayList<>(references);
    out.add(battleStance);
    out.add(battleDamage);
    out.addAll(battleAttack.frames);
    for (Animation a : dungeonIdle.values()) {
      out.addAll(a.frames);
    }
    for (Animation a : dungeonWalk.values()) {
      out.addAll(a.frames);
    }
    out.removeIf(s -> s == null || s.isBlank());
    return out;
  }

  // -------------------------------------------------------------- serialization

  public String serialize() {
    StringBuilder sb = new StringBuilder();
    sb.append("monster id=").append(KV.q(id)).append(" name=").append(KV.q(name));
    if (!family.isEmpty()) {
      sb.append(" family=").append(KV.q(family));
    }
    sb.append('\n');
    for (String ref : references) {
      sb.append("ref path=").append(KV.q(ref)).append('\n');
    }
    sb.append("battle stance=").append(KV.q(battleStance))
        .append(" damage=").append(KV.q(battleDamage)).append('\n');
    appendAnim(sb, "battle.attack", battleAttack);
    for (String o : ORIENTATIONS) {
      appendAnim(sb, "dungeon.idle." + o, dungeonIdle.get(o));
    }
    for (String o : ORIENTATIONS) {
      appendAnim(sb, "dungeon.walk." + o, dungeonWalk.get(o));
    }
    for (Map.Entry<String, SkeletonData> e : skeletons.entrySet()) {
      SkeletonData d = e.getValue();
      if (d.isEmpty()) {
        continue;
      }
      sb.append("skeleton ref=").append(KV.q(e.getKey()))
          .append(" dir=").append(KV.q(d.direction))
          .append(" data=").append(KV.q(d.encode()))
          .append('\n');
    }
    if (extract.used) {
      sb.append("extract w=").append(extract.width)
          .append(" h=").append(extract.height)
          .append(" algo=").append(KV.q(extract.algo))
          .append(" threshold=").append(extract.threshold)
          .append(" alpha=").append(KV.q(extract.alpha))
          .append(" background=").append(KV.q(extract.background))
          .append('\n');
    }
    sb.append("stats cols=").append(KV.q(String.join(",", statColumns))).append('\n');
    for (LevelRow r : levels) {
      sb.append("level n=").append(r.level);
      for (String c : statColumns) {
        sb.append(' ').append(c).append('=').append(r.get(c));
      }
      sb.append('\n');
    }
    for (Skill s : skills) {
      sb.append("skill name=").append(KV.q(s.name))
          .append(" learn=").append(s.learnLevel)
          .append(" mp=").append(s.mp)
          .append(" power=").append(s.power)
          .append(" type=").append(KV.q(s.type))
          .append('\n');
    }
    return sb.toString();
  }

  private void appendAnim(StringBuilder sb, String role, Animation a) {
    sb.append("anim role=").append(KV.q(role))
        .append(" fps=").append(a.fps)
        .append(" loop=").append(a.loop)
        .append(" frames=").append(KV.q(a.framesJoined()))
        .append('\n');
  }

  public void save(File file) throws Exception {
    Files.write(file.toPath(), serialize().getBytes(StandardCharsets.UTF_8));
  }

  public static Monster load(File file) throws Exception {
    if (!file.exists() || file.length() == 0) {
      return blank(stripExt(file.getName()));
    }
    Monster m = new Monster();
    boolean sawCols = false;
    for (String line : Files.readAllLines(file.toPath())) {
      KV kv = KV.parse(line);
      if (kv == null) {
        continue;
      }
      switch (kv.verb) {
        case "monster" -> {
          m.id = kv.get("id", "monster");
          m.name = kv.get("name", m.id);
          m.family = kv.get("family", "");
        }
        case "ref" -> {
          String p = kv.get("path", "");
          if (!p.isBlank()) {
            m.references.add(p);
          }
        }
        case "battle" -> {
          m.battleStance = kv.get("stance", "");
          m.battleDamage = kv.get("damage", "");
        }
        case "anim" -> {
          Animation a = m.animFor(kv.get("role", ""));
          if (a != null) {
            a.fps = kv.getInt("fps", 6);
            a.loop = kv.getInt("loop", 0);
            a.setFramesJoined(kv.get("frames", ""));
          }
        }
        case "skeleton" -> {
          String ref = kv.get("ref", "");
          if (!ref.isBlank()) {
            m.skeletons.put(ref, SkeletonData.decode(kv.get("dir", "towards"), kv.get("data", "")));
          }
        }
        case "extract" -> {
          m.extract.used = true;
          m.extract.width = kv.getInt("w", 0);
          m.extract.height = kv.getInt("h", 0);
          m.extract.algo = kv.get("algo", "FLOYD");
          m.extract.threshold = kv.getInt("threshold", 128);
          m.extract.alpha = kv.get("alpha", "WHITE");
          m.extract.background = kv.get("background", "none");
        }
        case "stats" -> {
          String cols = kv.get("cols", "");
          if (!cols.isBlank()) {
            m.statColumns.clear();
            for (String c : cols.split(",")) {
              String t = c.strip();
              if (!t.isEmpty()) {
                m.statColumns.add(t);
              }
            }
            sawCols = true;
          }
        }
        case "level" -> {
          LevelRow r = new LevelRow(kv.getInt("n", m.levels.size() + 1));
          for (String c : m.statColumns) {
            r.stats.put(c, kv.getInt(c, 0));
          }
          m.levels.add(r);
        }
        case "skill" -> {
          Skill s = new Skill();
          s.name = kv.get("name", "Skill");
          s.learnLevel = kv.getInt("learn", 1);
          s.mp = kv.getInt("mp", 0);
          s.power = kv.getInt("power", 0);
          s.type = kv.get("type", "");
          m.skills.add(s);
        }
        default -> { /* forward-compat: ignore unknown verbs */ }
      }
    }
    if (!sawCols && m.statColumns.isEmpty()) {
      m.statColumns.addAll(List.of("HP", "MP", "ATK", "DEF", "SPD", "XP", "GOLD"));
    }
    if (m.levels.isEmpty()) {
      m.levels.add(new LevelRow(1));
    }
    return m;
  }

  private static String stripExt(String n) {
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }
}
