package mg.codegen;

import mg.editor.ProjectRefresh;
import mg.editor.ProjectSettings;
import mg.editor.dungeon.Dungeon;
import mg.editor.item.Item;
import mg.editor.monster.Monster;
import mg.editor.story.Story;
import mg.tree.Field;
import mg.tree.Parser;
import mg.tree.Root;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The C code generator — work in progress. This class is the <b>pipeline and the
 * front end</b>: it resolves the output directory (relative to the project root),
 * parses the schema, validates content, and emits a generated header skeleton with
 * the authoritative field/effect inventory. The bodies (the actual C that loads/
 * saves the spine document and runs the dungeon/story VMs) are filled in
 * separately. Pure and UI-free so it can be driven by {@link mg.editor.CompilerWindow}
 * on a background thread and exercised by unit tests.
 *
 * @see documents/CODEGEN.md
 * @see documents/design.dvm.md
 */
public final class Compiler {
  private Compiler() {}

  /** the outcome of one compile: messages, files written, and error/warning counts. */
  public static final class Result {
    public final List<String> messages = new ArrayList<>();
    public final List<File> written = new ArrayList<>();
    public File outputDir;
    public int errors;
    public int warnings;

    public boolean ok() {
      return errors == 0;
    }
  }

  /**
   * Run codegen for {@code root} into {@code settings.outputDir}, streaming progress
   * lines to {@code log} (which may run on any thread). Never throws — failures land
   * in the {@link Result} as error messages.
   */
  public static Result run(File root, ProjectSettings settings, Consumer<String> log) {
    Result r = new Result();
    Consumer<String> say = m -> { r.messages.add(m); if (log != null) { log.accept(m); } };
    if (root == null || !root.isDirectory()) {
      r.errors++;
      say.accept("ERROR: no project open");
      return r;
    }

    String dir = settings == null || settings.outputDir == null || settings.outputDir.isBlank()
        ? "out" : settings.outputDir.strip();
    File out = new File(root, dir);
    r.outputDir = out;
    say.accept("rpg-spine codegen (work in progress)");
    say.accept("project: " + root.getAbsolutePath());
    say.accept("output : " + dir + "/  (" + out.getAbsolutePath() + ")");
    if (!out.exists() && !out.mkdirs()) {
      r.errors++;
      say.accept("ERROR: could not create output directory " + out.getAbsolutePath());
      return r;
    }

    // ---- gather content ----
    List<File> rpg = new ArrayList<>(), dungeons = new ArrayList<>(), stories = new ArrayList<>();
    List<File> items = new ArrayList<>(), monsters = new ArrayList<>();
    collect(root, out, rpg, dungeons, stories, items, monsters);
    say.accept("found: " + rpg.size() + " .rpg, " + dungeons.size() + " .dungeon, "
        + stories.size() + " .story, " + items.size() + " .item, " + monsters.size() + " .monster");

    // ---- parse the schema (fields + effects) ----
    Root schema = new Root();
    for (File f : rpg) {
      try {
        Parser.merge_string(schema, f.getName(), Files.readString(f.toPath()));
        say.accept("  parsed " + rel(root, f));
      } catch (Exception ex) {
        r.errors++;
        say.accept("ERROR: " + rel(root, f) + ": " + ex.getMessage());
      }
    }
    say.accept("schema: " + schema.fields.size() + " root field(s), " + schema.structs.size()
        + " struct(s), " + schema.effects.size() + " effect(s)");

    // ---- validate content (loads + lints) ----
    for (File f : stories) {
      try {
        Story s = Story.load(f);
        for (String problem : s.lint()) {
          r.warnings++;
          say.accept("WARN: " + rel(root, f) + ": " + problem);
        }
        for (Story.Node n : s.nodes) {
          for (Story.Effect e : n.onEnter) {
            if (!e.name.isBlank() && !schema.effects.contains(e.name)) {
              r.warnings++;
              say.accept("WARN: " + rel(root, f) + ": node " + n.id
                  + " uses effect '" + e.name + "' not declared in any .rpg");
            }
          }
        }
      } catch (Exception ex) {
        r.errors++;
        say.accept("ERROR: " + rel(root, f) + ": " + ex.getMessage());
      }
    }
    for (File f : dungeons) {
      try {
        Dungeon.load(f);
      } catch (Exception ex) {
        r.errors++;
        say.accept("ERROR: " + rel(root, f) + ": " + ex.getMessage());
      }
    }
    for (File f : items) {
      try {
        Item.load(f);
      } catch (Exception ex) {
        r.errors++;
        say.accept("ERROR: " + rel(root, f) + ": " + ex.getMessage());
      }
    }
    for (File f : monsters) {
      try {
        Monster.load(f);
      } catch (Exception ex) {
        r.errors++;
        say.accept("ERROR: " + rel(root, f) + ": " + ex.getMessage());
      }
    }

    // ---- emit the generated header skeleton (inventory; bodies are TODO) ----
    try {
      File header = new File(out, "spine.gen.h");
      Files.write(header.toPath(), header(schema, rpg, dungeons, stories, items, monsters)
          .getBytes(StandardCharsets.UTF_8));
      r.written.add(header);
      say.accept("wrote " + dir + "/spine.gen.h");
    } catch (Exception ex) {
      r.errors++;
      say.accept("ERROR: writing header: " + ex.getMessage());
    }

    say.accept(r.ok()
        ? ("done — " + r.written.size() + " file(s) written, " + r.warnings + " warning(s)")
        : ("FAILED — " + r.errors + " error(s), " + r.warnings + " warning(s)"));
    ProjectRefresh.fire(); // generated files appeared on disk
    return r;
  }

  /** the generated header: an include-guarded inventory of fields/effects, bodies TODO. */
  private static String header(Root schema, List<File> rpg, List<File> dungeons,
                               List<File> stories, List<File> items, List<File> monsters) {
    StringBuilder sb = new StringBuilder();
    sb.append("/* Generated by rpg-spine codegen — DO NOT EDIT.\n")
        .append(" * Codegen is a work in progress: this header is the authoritative\n")
        .append(" * field/effect inventory; the load/save and VM bodies are TODO. */\n")
        .append("#ifndef SPINE_GEN_H\n#define SPINE_GEN_H\n\n");

    sb.append("/* ---- root fields (").append(schema.fields.size()).append(") ---- */\n");
    for (Field f : schema.fields) {
      sb.append("/*   ").append(f.code).append(": ").append(f.type)
          .append(f.is_array ? "[]" : "").append(' ').append(f.name).append(" */\n");
    }
    sb.append('\n');

    sb.append("/* ---- effects (").append(schema.effects.size())
        .append(") — each is void effect_<name>(spine_t* doc, int param) ---- */\n");
    for (int i = 0; i < schema.effects.size(); i++) {
      sb.append("#define EFF_").append(schema.effects.get(i).toUpperCase()).append(' ').append(i).append('\n');
    }
    sb.append("#define EFF_COUNT ").append(schema.effects.size()).append("\n\n");

    sb.append("/* ---- content manifest ---- */\n");
    sb.append("/*   structs : ").append(schema.structs.size()).append(" */\n");
    sb.append("/*   dungeons: ").append(dungeons.size()).append(" */\n");
    sb.append("/*   stories : ").append(stories.size()).append(" */\n");
    sb.append("/*   items   : ").append(items.size()).append(" */\n");
    sb.append("/*   monsters: ").append(monsters.size()).append(" */\n\n");

    sb.append("#endif /* SPINE_GEN_H */\n");
    return sb.toString();
  }

  private static void collect(File dir, File outDir, List<File> rpg, List<File> dungeons,
                              List<File> stories, List<File> items, List<File> monsters) {
    File[] kids = dir.listFiles();
    if (kids == null) {
      return;
    }
    for (File k : kids) {
      String name = k.getName();
      if (name.equals("target") || name.startsWith(".") || k.equals(outDir)) {
        continue; // skip build dirs, dotfiles, and our own output
      }
      if (k.isDirectory()) {
        collect(k, outDir, rpg, dungeons, stories, items, monsters);
      } else {
        String n = name.toLowerCase();
        if (n.endsWith(".rpg")) {
          rpg.add(k);
        } else if (n.endsWith(".dungeon")) {
          dungeons.add(k);
        } else if (n.endsWith(".story")) {
          stories.add(k);
        } else if (n.endsWith(".item")) {
          items.add(k);
        } else if (n.endsWith(".monster")) {
          monsters.add(k);
        }
      }
    }
  }

  private static String rel(File root, File f) {
    try {
      return root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
    } catch (Exception e) {
      return f.getName();
    }
  }
}
