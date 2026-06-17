package mg.editor.item;

import mg.editor.KV;
import mg.editor.asset.Animation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-memory model for an .item file. Shares a small common core (id, name,
 * value, description) plus type-specific parameters. Visuals are explicit files:
 * <b>three icon images</b> (one per project icon size) that the artist selects,
 * and a <b>usage animation</b> (a frame-file list with speed and loop) shown when
 * the item is used. No generation or resizing here — everything is selected.
 */
public class Item {

  public enum Type { WEAPON, ARMOR, SHIELD, ACCESSORY, CONSUMABLE, KEY, MISC }

  public enum ParamKind { INT, TEXT }

  public static class Param {
    public final String key;
    public final String label;
    public final ParamKind kind;
    public final String dflt;

    public Param(String key, String label, ParamKind kind, String dflt) {
      this.key = key;
      this.label = label;
      this.kind = kind;
      this.dflt = dflt;
    }
  }

  public String id = "item";
  public String name = "Item";
  public Type type = Type.MISC;
  public int value = 0;
  public String description = "";

  /** three icon images, indexed large(0) / medium(1) / small(2). */
  public final List<String> icons = new ArrayList<>(List.of("", "", ""));
  /** the item-use animation (explicit frame files + speed + loop). */
  public final Animation usage = new Animation();
  public final Map<String, String> params = new LinkedHashMap<>();

  /** reserved keys on the item line that are NOT type-specific params. */
  private static final List<String> RESERVED =
      List.of("id", "name", "type", "value", "desc", "icon", "usage");

  public static List<Param> schemaFor(Type type) {
    List<Param> p = new ArrayList<>();
    switch (type) {
      case WEAPON -> {
        p.add(new Param("attack", "Attack", ParamKind.INT, "1"));
        p.add(new Param("speed", "Speed", ParamKind.INT, "0"));
        p.add(new Param("range", "Range", ParamKind.INT, "1"));
        p.add(new Param("element", "Element", ParamKind.TEXT, ""));
        p.add(new Param("twohanded", "Two-handed (0/1)", ParamKind.INT, "0"));
      }
      case ARMOR -> {
        p.add(new Param("armor", "Armor", ParamKind.INT, "1"));
        p.add(new Param("slot", "Slot (head/body/arms/legs)", ParamKind.TEXT, "body"));
        p.add(new Param("weight", "Weight", ParamKind.INT, "0"));
      }
      case SHIELD -> {
        p.add(new Param("armor", "Armor", ParamKind.INT, "1"));
        p.add(new Param("block", "Block chance %", ParamKind.INT, "0"));
      }
      case ACCESSORY -> {
        p.add(new Param("effect", "Effect", ParamKind.TEXT, ""));
        p.add(new Param("magnitude", "Magnitude", ParamKind.INT, "0"));
      }
      case CONSUMABLE -> {
        p.add(new Param("heal", "Heal HP", ParamKind.INT, "0"));
        p.add(new Param("restore", "Restore MP", ParamKind.INT, "0"));
        p.add(new Param("effect", "Effect", ParamKind.TEXT, ""));
      }
      case KEY, MISC -> { /* no type-specific params */ }
    }
    return p;
  }

  public void conformParamsToType() {
    Map<String, String> next = new LinkedHashMap<>();
    for (Param p : schemaFor(type)) {
      next.put(p.key, params.getOrDefault(p.key, p.dflt));
    }
    params.clear();
    params.putAll(next);
  }

  public static Item blank(String name) {
    Item it = new Item();
    it.id = name;
    it.name = name;
    it.conformParamsToType();
    return it;
  }

  /** every image path this item references (relative to its folder). */
  public List<String> imageRefs() {
    List<String> out = new ArrayList<>();
    for (String s : icons) {
      if (s != null && !s.isBlank()) {
        out.add(s);
      }
    }
    out.addAll(usage.frames);
    return out;
  }

  // -------------------------------------------------------------- serialization

  public String serialize() {
    StringBuilder sb = new StringBuilder();
    sb.append("item id=").append(KV.q(id))
        .append(" name=").append(KV.q(name))
        .append(" type=").append(type.name().toLowerCase())
        .append(" value=").append(value);
    for (Map.Entry<String, String> e : params.entrySet()) {
      sb.append(' ').append(e.getKey()).append('=').append(KV.q(e.getValue()));
    }
    if (!description.isEmpty()) {
      sb.append(" desc=").append(KV.q(description));
    }
    sb.append('\n');
    sb.append("icons large=").append(KV.q(icons.get(0)))
        .append(" med=").append(KV.q(icons.get(1)))
        .append(" small=").append(KV.q(icons.get(2)))
        .append('\n');
    sb.append("usage fps=").append(usage.fps)
        .append(" loop=").append(usage.loop)
        .append(" frames=").append(KV.q(usage.framesJoined()))
        .append('\n');
    return sb.toString();
  }

  public void save(File file) throws Exception {
    Files.write(file.toPath(), serialize().getBytes(StandardCharsets.UTF_8));
  }

  public static Item load(File file) throws Exception {
    if (!file.exists() || file.length() == 0) {
      return blank(stripExt(file.getName()));
    }
    Item it = new Item();
    for (String line : Files.readAllLines(file.toPath())) {
      KV kv = KV.parse(line);
      if (kv == null) {
        continue;
      }
      switch (kv.verb) {
        case "item" -> {
          it.id = kv.get("id", "item");
          it.name = kv.get("name", it.id);
          it.type = parseType(kv.get("type", "misc"));
          it.value = kv.getInt("value", 0);
          it.description = kv.get("desc", "");
          // legacy single icon/usage fields (older files) → best-effort migrate
          if (kv.attrs.containsKey("icon")) {
            it.icons.set(0, kv.get("icon", ""));
          }
          if (kv.attrs.containsKey("usage") && !kv.get("usage", "").isBlank()) {
            it.usage.frames.add(kv.get("usage", ""));
          }
          for (Map.Entry<String, String> e : kv.attrs.entrySet()) {
            if (!RESERVED.contains(e.getKey())) {
              it.params.put(e.getKey(), e.getValue());
            }
          }
        }
        case "icons" -> {
          it.icons.set(0, kv.get("large", ""));
          it.icons.set(1, kv.get("med", ""));
          it.icons.set(2, kv.get("small", ""));
        }
        case "usage" -> {
          it.usage.fps = kv.getInt("fps", 6);
          it.usage.loop = kv.getInt("loop", 0);
          it.usage.setFramesJoined(kv.get("frames", ""));
        }
        default -> { /* ignore unknown */ }
      }
    }
    return it;
  }

  private static Type parseType(String s) {
    try {
      return Type.valueOf(s.toUpperCase());
    } catch (Exception e) {
      return Type.MISC;
    }
  }

  private static String stripExt(String n) {
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }
}
