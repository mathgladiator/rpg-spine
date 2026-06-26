package mg.editor.story;

import mg.editor.KV;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The in-memory model for a {@code .story} file — a node graph the player walks
 * through once (no re-entry; it ends in survival or death, like a dungeon). Three
 * node kinds cover the brief (see {@code documents/story.vm.md}):
 *
 * <ul>
 *   <li>{@link Kind#BEAT} — an optional full-screen image plus a line/passage of
 *       text, and a single forward edge ({@link Node#next}).
 *   <li>{@link Kind#CHOICE} — a prompt plus N {@link Choice} decisions, each
 *       carrying optional {@link #fx side effects} and a target node.
 *   <li>{@link Kind#OUTCOME} — a terminal {@code survive}/{@code die}.
 * </ul>
 *
 * A node's <b>on-enter effects</b> are a list of named {@link Effect}s, each a name
 * declared in a {@code .rpg} schema ({@code effect kill_player;}) plus an integer
 * parameter (default 0). At codegen each maps to a C function taking the whole
 * document plus that parameter. Multiple effects on a node compose in order;
 * decisions carry no effects of their own — a choice navigates, and the target
 * node's on-enter effects do the work. Stored as flat {@link KV} text.
 */
public class Story {

  public enum Kind { BEAT, CHOICE, OUTCOME }

  public enum Result { SURVIVE, DIE }

  /** a named effect invocation: an {@code effect} from the {@code .rpg} + an int param. */
  public static final class Effect {
    public String name = "";
    public int param = 0;

    public Effect() {}

    public Effect(String name, int param) {
      this.name = name;
      this.param = param;
    }
  }

  /** one decision on a {@link Kind#CHOICE} node — a label and where it leads. */
  public static final class Choice {
    public String text = "";   // the option label shown to the player
    public String to = "";     // target node id; empty = fall through to the node's next
  }

  /** a single story node. Unused fields per kind are simply ignored. */
  public static final class Node {
    public String id = "n";
    public Kind kind = Kind.BEAT;
    public String text = "";
    public String image = "";   // BEAT: full-screen image (project-relative)
    public String next = "";    // BEAT/CHOICE: forward / fall-through target id
    public final List<Effect> onEnter = new ArrayList<>(); // effects applied on enter, in order
    public final List<Choice> choices = new ArrayList<>(); // CHOICE only
    // OUTCOME only:
    public Result result = Result.SURVIVE;
    public String reason = "";
    public String reward = "";  // an image / epilogue asset shown on the outcome
    // editor layout metadata (ignored by the VM):
    public double x;
    public double y;
  }

  public String id = "story";
  public String name = "Story";
  public String start = "";
  public final List<Node> nodes = new ArrayList<>();
  /** full-colour reference images (in {@code <base>.ref/}) the artist works from. */
  public final List<String> references = new ArrayList<>();

  public Node nodeById(String nid) {
    for (Node n : nodes) {
      if (n.id.equals(nid)) {
        return n;
      }
    }
    return null;
  }

  /** every image this story references (beat images + outcome rewards + refs), for the audit. */
  public List<String> imageRefs() {
    List<String> out = new ArrayList<>();
    for (Node n : nodes) {
      if (n.kind == Kind.BEAT && !n.image.isBlank()) {
        out.add(n.image);
      }
      if (n.kind == Kind.OUTCOME && !n.reward.isBlank()) {
        out.add(n.reward);
      }
    }
    for (String r : references) {
      if (r != null && !r.isBlank()) {
        out.add(r);
      }
    }
    return out;
  }

  /** the forward targets of a node: its {@code next} plus every choice {@code to}. */
  public List<String> targetsOf(Node n) {
    List<String> out = new ArrayList<>();
    if (n.kind == Kind.CHOICE) {
      for (Choice c : n.choices) {
        out.add(c.to.isBlank() ? n.next : c.to);
      }
    }
    if (n.kind == Kind.BEAT || (n.kind == Kind.CHOICE && n.choices.isEmpty())) {
      out.add(n.next);
    }
    out.removeIf(String::isBlank);
    return out;
  }

  /** node ids reachable from {@link #start} (a BFS over forward edges). */
  public Set<String> reachable() {
    Set<String> seen = new LinkedHashSet<>();
    Node s = nodeById(start);
    if (s == null) {
      return seen;
    }
    Deque<Node> q = new ArrayDeque<>();
    q.add(s);
    seen.add(s.id);
    while (!q.isEmpty()) {
      Node n = q.poll();
      for (String t : targetsOf(n)) {
        Node tn = nodeById(t);
        if (tn != null && seen.add(tn.id)) {
          q.add(tn);
        }
      }
    }
    return seen;
  }

  /**
   * Human-readable graph problems, for the editor's live diagnostics: a bad start,
   * dangling edges, an empty choice, an unreachable node, or no reachable ending.
   */
  public List<String> lint() {
    List<String> out = new ArrayList<>();
    if (nodeById(start) == null) {
      out.add("start node '" + start + "' does not exist");
    }
    Set<String> ids = new LinkedHashSet<>();
    for (Node n : nodes) {
      if (!ids.add(n.id)) {
        out.add("duplicate node id '" + n.id + "'");
      }
    }
    for (Node n : nodes) {
      if (n.kind == Kind.CHOICE && n.choices.isEmpty()) {
        out.add(n.id + ": choice node has no decisions");
      }
      for (String t : targetsOf(n)) {
        if (nodeById(t) == null) {
          out.add(n.id + ": edge to missing node '" + t + "'");
        }
      }
      if (n.kind != Kind.OUTCOME && targetsOf(n).isEmpty()) {
        out.add(n.id + ": no outgoing edge and not an outcome (dead end)");
      }
    }
    Set<String> reach = reachable();
    boolean ending = false;
    for (Node n : nodes) {
      if (n.kind == Kind.OUTCOME && reach.contains(n.id)) {
        ending = true;
      }
      if (!reach.contains(n.id) && !n.id.equals(start)) {
        out.add(n.id + ": unreachable from start");
      }
    }
    if (!nodes.isEmpty() && !ending) {
      out.add("no outcome (survive/die) is reachable from start");
    }
    return out;
  }

  public static Story blank(String name) {
    Story s = new Story();
    s.name = name;
    s.id = slug(name);
    Node start = new Node();
    start.id = "start";
    start.kind = Kind.BEAT;
    start.text = "Once you begin, there is no turning back.";
    start.next = "end";
    start.x = 80;
    start.y = 60;
    Node end = new Node();
    end.id = "end";
    end.kind = Kind.OUTCOME;
    end.result = Result.SURVIVE;
    end.x = 360;
    end.y = 60;
    s.nodes.add(start);
    s.nodes.add(end);
    s.start = "start";
    return s;
  }

  // -------------------------------------------------------------- serialization

  public String serialize() {
    StringBuilder sb = new StringBuilder();
    sb.append("story id=").append(KV.q(id))
        .append(" name=").append(KV.q(name))
        .append(" start=").append(KV.q(start)).append('\n');
    for (String r : references) {
      if (r != null && !r.isBlank()) {
        sb.append("ref path=").append(KV.q(r)).append('\n');
      }
    }
    for (Node n : nodes) {
      sb.append("node id=").append(KV.q(n.id))
          .append(" kind=").append(n.kind.name().toLowerCase());
      if (!n.text.isEmpty()) {
        sb.append(" text=").append(KV.q(n.text));
      }
      if (n.kind == Kind.BEAT && !n.image.isEmpty()) {
        sb.append(" image=").append(KV.q(n.image));
      }
      if ((n.kind == Kind.BEAT || n.kind == Kind.CHOICE) && !n.next.isEmpty()) {
        sb.append(" next=").append(KV.q(n.next));
      }
      if (n.kind == Kind.OUTCOME) {
        sb.append(" result=").append(n.result.name().toLowerCase());
        if (!n.reason.isEmpty()) {
          sb.append(" reason=").append(KV.q(n.reason));
        }
        if (!n.reward.isEmpty()) {
          sb.append(" reward=").append(KV.q(n.reward));
        }
      }
      sb.append(" pos=").append(KV.q(round(n.x) + "," + round(n.y))).append('\n');
      for (Effect ef : n.onEnter) {
        if (ef.name.isBlank()) {
          continue;
        }
        sb.append("effect from=").append(KV.q(n.id))
            .append(" name=").append(KV.q(ef.name))
            .append(" param=").append(ef.param).append('\n');
      }
      if (n.kind == Kind.CHOICE) {
        for (Choice c : n.choices) {
          sb.append("choice from=").append(KV.q(n.id))
              .append(" text=").append(KV.q(c.text));
          if (!c.to.isEmpty()) {
            sb.append(" to=").append(KV.q(c.to));
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

  public static Story load(File file) throws Exception {
    if (!file.exists() || file.length() == 0) {
      return blank(stripExt(file.getName()));
    }
    Story s = new Story();
    for (String line : Files.readAllLines(file.toPath())) {
      KV kv = KV.parse(line);
      if (kv == null) {
        continue;
      }
      switch (kv.verb) {
        case "story" -> {
          s.id = kv.get("id", "story");
          s.name = kv.get("name", s.id);
          s.start = kv.get("start", "");
        }
        case "ref" -> {
          String p = kv.get("path", "");
          if (!p.isBlank()) {
            s.references.add(p);
          }
        }
        case "node" -> {
          Node n = new Node();
          n.id = kv.get("id", "n");
          n.kind = parseKind(kv.get("kind", "beat"));
          n.text = kv.get("text", "");
          n.image = kv.get("image", "");
          n.next = kv.get("next", "");
          n.result = "die".equalsIgnoreCase(kv.get("result", "survive")) ? Result.DIE : Result.SURVIVE;
          n.reason = kv.get("reason", "");
          n.reward = kv.get("reward", "");
          double[] p = parsePos(kv.get("pos", ""));
          n.x = p[0];
          n.y = p[1];
          s.nodes.add(n);
        }
        case "choice" -> {
          Node owner = s.nodeById(kv.get("from", ""));
          if (owner != null) {
            Choice c = new Choice();
            c.text = kv.get("text", "");
            c.to = kv.get("to", "");
            owner.choices.add(c);
          }
        }
        case "effect" -> {
          Node owner = s.nodeById(kv.get("from", ""));
          if (owner != null) {
            owner.onEnter.add(new Effect(kv.get("name", ""), kv.getInt("param", 0)));
          }
        }
        default -> { /* forward-compat: ignore unknown verbs */ }
      }
    }
    if (s.start.isBlank() && !s.nodes.isEmpty()) {
      s.start = s.nodes.get(0).id;
    }
    return s;
  }

  private static Kind parseKind(String t) {
    try {
      return Kind.valueOf(t.toUpperCase());
    } catch (Exception e) {
      return Kind.BEAT;
    }
  }

  private static double[] parsePos(String t) {
    String[] xy = t.split(",");
    if (xy.length == 2) {
      try {
        return new double[] {Double.parseDouble(xy[0].strip()), Double.parseDouble(xy[1].strip())};
      } catch (Exception ignore) {
        // fall through
      }
    }
    return new double[] {40, 40};
  }

  private static int round(double d) {
    return (int) Math.round(d);
  }

  static String slug(String s) {
    String out = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    return out.isEmpty() ? "story" : out;
  }

  private static String stripExt(String n) {
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }
}
