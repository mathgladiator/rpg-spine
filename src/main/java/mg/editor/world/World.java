package mg.editor.world;

import mg.editor.KV;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The in-memory model for a .world file. A world is at once a *graph* — named
 * location nodes joined by paths that can bend through intermediate points — and
 * a *scene graph*: free-standing objects placed on the map that are revealed as
 * the player trips variable bindings (flags/counters in the save document). The
 * editor never invents reflection; every reveal/blocked condition is just a
 * binding string the C codegen will resolve against the SPINE document.
 */
public class World {

  public enum LocationType {
    TOWN, CITY, VILLAGE, CASTLE, DUNGEON, CAVE, RUIN, SHRINE, CAMP, PORT, LANDMARK
  }

  /** a map node the player can travel to. */
  public static class Location {
    public String id = "loc";
    public String name = "Location";
    public LocationType type = LocationType.TOWN;
    public double x, y;
    public String reveal = "";          // binding; empty = always visible
    public boolean discovered = true;   // initial discovery state
    public final Map<String, String> meta = new LinkedHashMap<>();
  }

  /** a path between two locations, optionally bending through waypoints. */
  public static class Edge {
    public String id = "edge";
    public String from = "";
    public String to = "";
    public int cost = 1;
    public boolean bidirectional = true;
    public String blocked = "";         // binding; non-empty gates travel
    public final List<double[]> bends = new ArrayList<>(); // {x,y} waypoints in order
  }

  /** an object placed on the map, revealed over time. */
  public static class SceneObject {
    public String id = "obj";
    public String kind = "marker";      // chest, npc, monster, marker, portal, ...
    public String label = "";
    public double x, y;
    public String reveal = "";          // binding; empty = always visible
    public final Map<String, String> meta = new LinkedHashMap<>();
  }

  public String name = "World";
  public String mapImage = "";          // optional background image, relative to file
  public final List<Location> locations = new ArrayList<>();
  public final List<Edge> edges = new ArrayList<>();
  public final List<SceneObject> objects = new ArrayList<>();

  public Location locationById(String id) {
    for (Location l : locations) {
      if (l.id.equals(id)) {
        return l;
      }
    }
    return null;
  }

  /** every distinct binding referenced anywhere, for the Variables panel. */
  public Set<String> bindings() {
    Set<String> out = new LinkedHashSet<>();
    for (Location l : locations) {
      if (!l.reveal.isEmpty()) out.add(l.reveal);
    }
    for (Edge e : edges) {
      if (!e.blocked.isEmpty()) out.add(e.blocked);
    }
    for (SceneObject o : objects) {
      if (!o.reveal.isEmpty()) out.add(o.reveal);
    }
    return out;
  }

  public static World blank(String name) {
    World w = new World();
    w.name = name;
    return w;
  }

  // ---------------------------------------------------------------- meta utils

  /** public accessors for the editor (different package). */
  public static String metaToTextPublic(Map<String, String> meta) {
    return metaToText(meta);
  }

  public static void metaFromTextPublic(Map<String, String> meta, String text) {
    metaFromText(meta, text);
  }

  static String metaToText(Map<String, String> meta) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : meta.entrySet()) {
      if (sb.length() > 0) sb.append(';');
      sb.append(e.getKey()).append('=').append(e.getValue());
    }
    return sb.toString();
  }

  static void metaFromText(Map<String, String> meta, String text) {
    meta.clear();
    if (text == null) {
      return;
    }
    for (String part : text.split(";")) {
      String p = part.strip();
      if (p.isEmpty()) continue;
      int eq = p.indexOf('=');
      if (eq >= 0) {
        meta.put(p.substring(0, eq).strip(), p.substring(eq + 1).strip());
      } else {
        meta.put(p, "");
      }
    }
  }

  private static String bendsToText(List<double[]> bends) {
    StringBuilder sb = new StringBuilder();
    for (double[] b : bends) {
      if (sb.length() > 0) sb.append(';');
      sb.append(round(b[0])).append(',').append(round(b[1]));
    }
    return sb.toString();
  }

  private static int round(double d) {
    return (int) Math.round(d);
  }

  // -------------------------------------------------------------- serialization

  public String serialize() {
    StringBuilder sb = new StringBuilder();
    sb.append("world name=").append(KV.q(name));
    if (!mapImage.isEmpty()) sb.append(" map=").append(KV.q(mapImage));
    sb.append('\n');
    for (Location l : locations) {
      sb.append("location id=").append(KV.q(l.id))
          .append(" name=").append(KV.q(l.name))
          .append(" type=").append(l.type.name().toLowerCase())
          .append(" x=").append(round(l.x))
          .append(" y=").append(round(l.y))
          .append(" discovered=").append(l.discovered);
      if (!l.reveal.isEmpty()) sb.append(" reveal=").append(KV.q(l.reveal));
      if (!l.meta.isEmpty()) sb.append(" meta=").append(KV.q(metaToText(l.meta)));
      sb.append('\n');
    }
    for (Edge e : edges) {
      sb.append("edge id=").append(KV.q(e.id))
          .append(" from=").append(KV.q(e.from))
          .append(" to=").append(KV.q(e.to))
          .append(" cost=").append(e.cost)
          .append(" bidir=").append(e.bidirectional);
      if (!e.blocked.isEmpty()) sb.append(" blocked=").append(KV.q(e.blocked));
      if (!e.bends.isEmpty()) sb.append(" bends=").append(KV.q(bendsToText(e.bends)));
      sb.append('\n');
    }
    for (SceneObject o : objects) {
      sb.append("object id=").append(KV.q(o.id))
          .append(" kind=").append(KV.q(o.kind))
          .append(" label=").append(KV.q(o.label))
          .append(" x=").append(round(o.x))
          .append(" y=").append(round(o.y));
      if (!o.reveal.isEmpty()) sb.append(" reveal=").append(KV.q(o.reveal));
      if (!o.meta.isEmpty()) sb.append(" meta=").append(KV.q(metaToText(o.meta)));
      sb.append('\n');
    }
    return sb.toString();
  }

  public void save(File file) throws Exception {
    Files.write(file.toPath(), serialize().getBytes(StandardCharsets.UTF_8));
  }

  public static World load(File file) throws Exception {
    if (!file.exists() || file.length() == 0) {
      return blank(stripExt(file.getName()));
    }
    World w = new World();
    for (String line : Files.readAllLines(file.toPath())) {
      KV kv = KV.parse(line);
      if (kv == null) continue;
      switch (kv.verb) {
        case "world" -> {
          w.name = kv.get("name", "World");
          w.mapImage = kv.get("map", "");
        }
        case "location" -> {
          Location l = new Location();
          l.id = kv.get("id", "loc");
          l.name = kv.get("name", l.id);
          l.type = parseType(kv.get("type", "town"));
          l.x = kv.getInt("x", 0);
          l.y = kv.getInt("y", 0);
          l.discovered = kv.getBool("discovered", true);
          l.reveal = kv.get("reveal", "");
          metaFromText(l.meta, kv.get("meta", ""));
          w.locations.add(l);
        }
        case "edge" -> {
          Edge e = new Edge();
          e.id = kv.get("id", "edge");
          e.from = kv.get("from", "");
          e.to = kv.get("to", "");
          e.cost = kv.getInt("cost", 1);
          e.bidirectional = kv.getBool("bidir", true);
          e.blocked = kv.get("blocked", "");
          String bends = kv.get("bends", "");
          for (String b : bends.split(";")) {
            String p = b.strip();
            if (p.isEmpty()) continue;
            String[] xy = p.split(",");
            if (xy.length == 2) {
              try {
                e.bends.add(new double[]{Double.parseDouble(xy[0].strip()), Double.parseDouble(xy[1].strip())});
              } catch (Exception ignore) { }
            }
          }
          w.edges.add(e);
        }
        case "object" -> {
          SceneObject o = new SceneObject();
          o.id = kv.get("id", "obj");
          o.kind = kv.get("kind", "marker");
          o.label = kv.get("label", "");
          o.x = kv.getInt("x", 0);
          o.y = kv.getInt("y", 0);
          o.reveal = kv.get("reveal", "");
          metaFromText(o.meta, kv.get("meta", ""));
          w.objects.add(o);
        }
        default -> { /* forward-compat: ignore */ }
      }
    }
    return w;
  }

  private static LocationType parseType(String s) {
    try {
      return LocationType.valueOf(s.toUpperCase());
    } catch (Exception e) {
      return LocationType.LANDMARK;
    }
  }

  private static String stripExt(String n) {
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }
}
