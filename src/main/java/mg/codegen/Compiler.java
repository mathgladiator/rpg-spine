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
import mg.tree.Struct;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The C code generator. This class is the pipeline and the front end <b>and</b> the
 * emitter: it resolves the output directory (relative to the project root), parses
 * the schema, validates content, and emits the generated, game-structured C —
 * {@code spine.gen.h} (the SPINE struct, field-code defines, effect ids/prototypes,
 * and the load/save API) and {@code spine.gen.c} (the Thrift-style load/save bodies)
 * — plus a copy of the hand-written byte runtime ({@code spine_runtime.{h,c}}).
 *
 * <p>The generated reader/writer is built only from the runtime primitives; all byte
 * twiddling lives in the runtime. Pure and UI-free so it can be driven by
 * {@link mg.editor.CompilerWindow} on a background thread and exercised by tests.
 *
 * @see documents/SAVE_FORMAT.md
 * @see documents/CODEGEN.md
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
    say.accept("rpg-spine codegen");
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

    // ---- validate the schema for codegen ----
    Schema model = validate(schema, r, say);
    if (!r.ok() || model == null) {
      say.accept("FAILED — " + r.errors + " error(s), " + r.warnings + " warning(s)");
      ProjectRefresh.fire();
      return r;
    }

    // ---- emit generated C + the runtime ----
    try {
      writeFile(out, "spine.gen.h", emitHeader(model), r, say, dir);
      writeFile(out, "spine.gen.c", emitSource(model), r, say, dir);
      copyResource("/runtime/spine_runtime.h", new File(out, "spine_runtime.h"), r, say, dir);
      copyResource("/runtime/spine_runtime.c", new File(out, "spine_runtime.c"), r, say, dir);
    } catch (Exception ex) {
      r.errors++;
      say.accept("ERROR: emitting: " + ex.getMessage());
    }

    say.accept(r.ok()
        ? ("done — " + r.written.size() + " file(s) written, " + r.warnings + " warning(s)")
        : ("FAILED — " + r.errors + " error(s), " + r.warnings + " warning(s)"));
    ProjectRefresh.fire(); // generated files appeared on disk
    return r;
  }

  // ==========================================================================
  // schema model — the validated, codegen-ready view of a Root
  // ==========================================================================

  /** a resolved scalar type: its C type, op code, and runtime read/write fns. */
  private static final class Scalar {
    final String cType, op, read, write;
    Scalar(String cType, String op, String read, String write) {
      this.cType = cType; this.op = op; this.read = read; this.write = write;
    }
  }

  /** the validated schema: root scalars, root struct-arrays, and per-struct members. */
  private static final class Schema {
    final List<Field> rootScalars = new ArrayList<>();
    final List<Field> rootArrays = new ArrayList<>();          // type is a struct name
    final Map<String, Struct> structs = new LinkedHashMap<>(); // name -> struct
    final Set<String> instantiated = new LinkedHashSet<>();    // structs used as array elem
    final List<String> effects;
    final Root raw;
    Schema(Root raw, List<String> effects) { this.raw = raw; this.effects = effects; }
  }

  private static Scalar scalar(String t) {
    switch (t) {
      case "bool":                       return new Scalar("bool",     "OP_BOOL",   "spine_read_bool",   "spine_write_bool");
      case "byte": case "u8": case "uint8":   return new Scalar("uint8_t", "OP_UINT8",  "spine_read_uint8",  "spine_write_uint8");
      case "i8": case "int8":            return new Scalar("int8_t",   "OP_INT8",   "spine_read_int8",   "spine_write_int8");
      case "short": case "i16": case "int16": return new Scalar("int16_t", "OP_INT16",  "spine_read_int16",  "spine_write_int16");
      case "u16": case "uint16":         return new Scalar("uint16_t", "OP_UINT16", "spine_read_uint16", "spine_write_uint16");
      case "int": case "i32": case "int32":   return new Scalar("int32_t", "OP_INT32",  "spine_read_int32",  "spine_write_int32");
      case "u32": case "uint32":         return new Scalar("uint32_t", "OP_UINT32", "spine_read_uint32", "spine_write_uint32");
      case "long": case "i64": case "int64":  return new Scalar("int64_t", "OP_INT64",  "spine_read_int64",  "spine_write_int64");
      case "u64": case "uint64":         return new Scalar("uint64_t", "OP_UINT64", "spine_read_uint64", "spine_write_uint64");
      default: return null;
    }
  }

  /** Validate the parsed schema and build a codegen model, or null on error. */
  private static Schema validate(Root schema, Result r, Consumer<String> say) {
    boolean ok = true;

    // struct names unique
    Map<String, Struct> structs = new LinkedHashMap<>();
    for (Struct s : schema.structs) {
      if (structs.containsKey(s.name)) {
        r.errors++; ok = false;
        say.accept("ERROR: duplicate struct '" + s.name + "'");
      } else {
        structs.put(s.name, s);
      }
    }

    // field codes globally unique across root + every struct
    Map<Integer, String> codes = new LinkedHashMap<>();
    for (Field f : schema.fields) {
      String dup = codes.put(f.code, "root." + f.name);
      if (dup != null) { r.errors++; ok = false; say.accept("ERROR: duplicate field code " + f.code + " (" + dup + " and root." + f.name + ")"); }
    }
    for (Struct s : structs.values()) {
      for (Field f : s.fields) {
        String dup = codes.put(f.code, s.name + "." + f.name);
        if (dup != null) { r.errors++; ok = false; say.accept("ERROR: duplicate field code " + f.code + " (" + dup + " and " + s.name + "." + f.name + ")"); }
      }
    }

    Schema m = new Schema(schema, schema.effects);
    m.structs.putAll(structs);

    // root fields: scalars, or arrays of a known struct
    for (Field f : schema.fields) {
      if (f.is_array) {
        if (structs.containsKey(f.type)) {
          m.rootArrays.add(f);
          m.instantiated.add(f.type);
        } else if (scalar(f.type) != null) {
          r.errors++; ok = false;
          say.accept("ERROR: root field '" + f.name + "': arrays of scalar type '" + f.type
              + "' are not supported (array elements must be a struct)");
        } else {
          r.errors++; ok = false;
          say.accept("ERROR: root field '" + f.name + "': unknown array element type '" + f.type + "'");
        }
      } else if (scalar(f.type) != null) {
        m.rootScalars.add(f);
      } else if (structs.containsKey(f.type)) {
        r.errors++; ok = false;
        say.accept("ERROR: root field '" + f.name + "': embedded struct value '" + f.type
            + "' is not supported yet (use a one-element array)");
      } else {
        r.errors++; ok = false;
        say.accept("ERROR: root field '" + f.name + "': unknown type '" + f.type + "'");
      }
    }

    // struct members must be plain scalars (no nesting / arrays yet)
    for (Struct s : structs.values()) {
      for (Field f : s.fields) {
        if (f.is_array) {
          r.errors++; ok = false;
          say.accept("ERROR: " + s.name + "." + f.name + ": arrays inside structs are not supported yet");
        } else if (scalar(f.type) == null) {
          r.errors++; ok = false;
          say.accept("ERROR: " + s.name + "." + f.name + ": unsupported member type '" + f.type + "'");
        }
      }
    }

    // effects unique
    Set<String> seenEff = new LinkedHashSet<>();
    for (String e : schema.effects) {
      if (!seenEff.add(e)) { r.errors++; ok = false; say.accept("ERROR: duplicate effect '" + e + "'"); }
    }

    return ok ? m : null;
  }

  // ==========================================================================
  // header emission — spine.gen.h
  // ==========================================================================

  private static String emitHeader(Schema m) {
    StringBuilder sb = new StringBuilder();
    sb.append("/* Generated by rpg-spine codegen — DO NOT EDIT.\n")
        .append(" * The SPINE document, its field codes, declared effects, and the\n")
        .append(" * load/save API. See documents/SAVE_FORMAT.md. */\n")
        .append("#ifndef SPINE_GEN_H\n#define SPINE_GEN_H\n\n")
        .append("#include <stdbool.h>\n#include <stdint.h>\n\n");

    // ---- field codes ----
    sb.append("/* ---- field codes (stable; survive renames) ---- */\n");
    for (Field f : m.raw.fields) {
      sb.append("#define ").append(rootFc(f)).append(' ').append(f.code).append('\n');
    }
    for (Struct s : m.structs.values()) {
      for (Field f : s.fields) {
        sb.append("#define ").append(memberFc(s, f)).append(' ').append(f.code).append('\n');
      }
    }
    sb.append('\n');

    // ---- effects ----
    sb.append("/* ---- effects — each is void effect_<name>(SPINE* doc, int param) ---- */\n");
    for (int i = 0; i < m.effects.size(); i++) {
      sb.append("#define EFF_").append(up(m.effects.get(i))).append(' ').append(i).append('\n');
    }
    sb.append("#define EFF_COUNT ").append(m.effects.size()).append("\n\n");

    // ---- struct typedefs (every declared struct) ----
    sb.append("/* ---- structs ---- */\n");
    for (Struct s : m.structs.values()) {
      sb.append("typedef struct ").append(s.name).append(" {\n");
      for (Field f : s.fields) {
        sb.append("    ").append(scalar(f.type).cType).append(' ').append(f.name).append(";\n");
      }
      sb.append("} ").append(s.name).append(";\n\n");
    }

    // ---- the document ----
    sb.append("/* ---- the global spine document ---- */\n");
    sb.append("typedef struct SPINE {\n");
    for (Field f : m.rootScalars) {
      sb.append("    ").append(scalar(f.type).cType).append(' ').append(f.name).append(";\n");
    }
    for (Field f : m.rootArrays) {
      sb.append("    ").append(f.type).append(" *").append(f.name).append(";\n");
      sb.append("    uint32_t ").append(f.name).append("_count;\n");
    }
    sb.append("} SPINE;\n\n");

    // ---- API ----
    sb.append("/* ---- lifecycle + serialization (see documents/SAVE_FORMAT.md) ---- */\n");
    sb.append("SPINE   *spine_new(void);                              /* zeroed document */\n");
    sb.append("void     spine_free(SPINE *doc);                       /* frees doc + arrays */\n");
    sb.append("SPINE   *spine_load(const uint8_t *data, int size);    /* NULL on any error */\n");
    sb.append("uint8_t *spine_save(const SPINE *doc, int *out_size);  /* caller free()s result */\n\n");

    // ---- effect prototypes ----
    if (!m.effects.isEmpty()) {
      sb.append("/* ---- effect hooks — you implement these somewhere in the game ---- */\n");
      for (String e : m.effects) {
        sb.append("void effect_").append(e).append("(SPINE *doc, int param);\n");
      }
      sb.append('\n');
    }

    sb.append("#endif /* SPINE_GEN_H */\n");
    return sb.toString();
  }

  // ==========================================================================
  // source emission — spine.gen.c
  // ==========================================================================

  private static String emitSource(Schema m) {
    StringBuilder sb = new StringBuilder();
    sb.append("/* Generated by rpg-spine codegen — DO NOT EDIT.\n")
        .append(" * load/save built entirely on the spine_runtime primitives. */\n")
        .append("#include \"spine.gen.h\"\n#include \"spine_runtime.h\"\n\n")
        .append("#include <stdlib.h>\n\n");

    // struct type tags for the load cursor
    sb.append("enum { ST_NONE = 0");
    int t = 1;
    for (String s : m.instantiated) sb.append(", ST_").append(up(s)).append(" = ").append(t++);
    sb.append(" };\n\n");

    // ---- spine_new / spine_free ----
    sb.append("SPINE *spine_new(void) {\n");
    sb.append("    return (SPINE *)calloc(1, sizeof(SPINE));\n");
    sb.append("}\n\n");

    sb.append("void spine_free(SPINE *doc) {\n");
    sb.append("    if (!doc) return;\n");
    for (Field f : m.rootArrays) {
      sb.append("    free(doc->").append(f.name).append(");\n");
    }
    sb.append("    free(doc);\n");
    sb.append("}\n\n");

    // ---- spine_save ----
    sb.append("uint8_t *spine_save(const SPINE *doc, int *out_size) {\n");
    sb.append("    if (out_size) *out_size = 0;\n");
    sb.append("    if (!doc) return NULL;\n");
    sb.append("    spine_writer w; spine_writer_init(&w);\n");
    sb.append("    spine_write_uint8(&w, SPINE_MAGIC0);\n");
    sb.append("    spine_write_uint8(&w, SPINE_MAGIC1);\n");
    sb.append("    spine_write_uint8(&w, SPINE_MAGIC2);\n");
    sb.append("    spine_write_uint8(&w, SPINE_MAGIC3);\n");
    sb.append("    spine_write_uint16(&w, (uint16_t)SPINE_FORMAT_VERSION);\n");
    for (Field f : m.rootScalars) {
      Scalar sc = scalar(f.type);
      sb.append("    spine_write_op_code(&w, ").append(sc.op).append("); spine_write_uint16(&w, ")
          .append(rootFc(f)).append("); ").append(sc.write).append("(&w, doc->").append(f.name).append(");\n");
    }
    for (Field f : m.rootArrays) {
      Struct s = m.structs.get(f.type);
      sb.append("    spine_write_op_code(&w, OP_ENTER_ARRAY); spine_write_uint16(&w, ").append(rootFc(f))
          .append("); spine_write_uint32(&w, doc->").append(f.name).append("_count);\n");
      sb.append("    for (uint32_t i = 0; i < doc->").append(f.name).append("_count; ++i) {\n");
      sb.append("        if (i != 0) spine_write_op_code(&w, OP_NEXT);\n");
      for (Field mf : s.fields) {
        Scalar sc = scalar(mf.type);
        sb.append("        spine_write_op_code(&w, ").append(sc.op).append("); spine_write_uint16(&w, ")
            .append(memberFc(s, mf)).append("); ").append(sc.write).append("(&w, doc->")
            .append(f.name).append("[i].").append(mf.name).append(");\n");
      }
      sb.append("    }\n");
      sb.append("    spine_write_op_code(&w, OP_EXIT_ARRAY);\n");
    }
    sb.append("    spine_write_op_code(&w, OP_END);\n");
    sb.append("    if (!spine_writer_ok(&w)) { spine_writer_free(&w); return NULL; }\n");
    sb.append("    spine_write_uint32(&w, spine_crc32(w.buf, w.size));\n");
    sb.append("    if (!spine_writer_ok(&w)) { spine_writer_free(&w); return NULL; }\n");
    sb.append("    return spine_writer_take(&w, out_size);\n");
    sb.append("}\n\n");

    // ---- spine_load ----
    sb.append("SPINE *spine_load(const uint8_t *data, int size) {\n");
    sb.append("    if (!data || size < 11) return NULL;   /* magic+ver+end+crc minimum */\n");
    sb.append("    spine_reader r; spine_reader_init(&r, data, (size_t)size);\n");
    sb.append("    if (spine_read_uint8(&r) != SPINE_MAGIC0) return NULL;\n");
    sb.append("    if (spine_read_uint8(&r) != SPINE_MAGIC1) return NULL;\n");
    sb.append("    if (spine_read_uint8(&r) != SPINE_MAGIC2) return NULL;\n");
    sb.append("    if (spine_read_uint8(&r) != SPINE_MAGIC3) return NULL;\n");
    sb.append("    if (spine_read_uint16(&r) != (uint16_t)SPINE_FORMAT_VERSION) return NULL;\n");
    sb.append("    {\n");
    sb.append("        uint32_t stored = (uint32_t)data[size - 4] << 24 | (uint32_t)data[size - 3] << 16\n");
    sb.append("                        | (uint32_t)data[size - 2] << 8  | (uint32_t)data[size - 1];\n");
    sb.append("        if (spine_crc32(data, (size_t)size - 4) != stored) return NULL;\n");
    sb.append("    }\n");
    sb.append("    SPINE *doc = spine_new();\n");
    sb.append("    if (!doc) return NULL;\n");
    sb.append("    int      cur_type = ST_NONE;\n");
    sb.append("    void    *cur_base = NULL;\n");
    sb.append("    uint32_t cur_idx = 0, cur_cnt = 0;\n");
    sb.append("    (void)cur_base; (void)cur_idx; (void)cur_cnt;\n");
    sb.append("    for (;;) {\n");
    sb.append("        uint8_t op = spine_read_op_code(&r);\n");
    sb.append("        if (!spine_reader_ok(&r)) goto fail;\n");
    sb.append("        if (op == OP_END) break;\n");
    sb.append("        if (op == OP_ENTER_ARRAY) {\n");
    sb.append("            uint16_t code = spine_read_uint16(&r);\n");
    sb.append("            uint32_t count = spine_read_uint32(&r);\n");
    sb.append("            if (!spine_reader_ok(&r)) goto fail;\n");
    sb.append("            switch (code) {\n");
    for (Field f : m.rootArrays) {
      sb.append("            case ").append(rootFc(f)).append(":\n");
      sb.append("                doc->").append(f.name).append(" = count ? (").append(f.type)
          .append(" *)calloc(count, sizeof(").append(f.type).append(")) : NULL;\n");
      sb.append("                if (count && !doc->").append(f.name).append(") goto fail;\n");
      sb.append("                doc->").append(f.name).append("_count = count;\n");
      sb.append("                cur_type = ST_").append(up(f.type)).append("; cur_base = doc->")
          .append(f.name).append("; cur_idx = 0; cur_cnt = count;\n");
      sb.append("                break;\n");
    }
    sb.append("            default: goto fail;\n");
    sb.append("            }\n");
    sb.append("            continue;\n");
    sb.append("        }\n");
    sb.append("        if (op == OP_NEXT) {\n");
    sb.append("            if (cur_type == ST_NONE) goto fail;\n");
    sb.append("            cur_idx++;\n");
    sb.append("            if (cur_idx >= cur_cnt) goto fail;\n");
    sb.append("            continue;\n");
    sb.append("        }\n");
    sb.append("        if (op == OP_EXIT_ARRAY) { cur_type = ST_NONE; cur_base = NULL; cur_idx = cur_cnt = 0; continue; }\n");
    sb.append("        {\n");
    sb.append("            uint16_t code = spine_read_uint16(&r);\n");
    sb.append("            if (!spine_reader_ok(&r)) goto fail;\n");
    sb.append("            switch (code) {\n");
    for (Field f : m.rootScalars) {
      Scalar sc = scalar(f.type);
      sb.append("            case ").append(rootFc(f)).append(":\n");
      sb.append("                if (op != ").append(sc.op).append(") goto fail;\n");
      sb.append("                doc->").append(f.name).append(" = ").append(sc.read).append("(&r);\n");
      sb.append("                break;\n");
    }
    for (String sn : m.instantiated) {
      Struct s = m.structs.get(sn);
      for (Field mf : s.fields) {
        Scalar sc = scalar(mf.type);
        sb.append("            case ").append(memberFc(s, mf)).append(":\n");
        sb.append("                if (cur_type != ST_").append(up(sn)).append(") goto fail;\n");
        sb.append("                if (op != ").append(sc.op).append(") goto fail;\n");
        sb.append("                ((").append(sn).append(" *)cur_base)[cur_idx].").append(mf.name)
            .append(" = ").append(sc.read).append("(&r);\n");
        sb.append("                break;\n");
      }
    }
    sb.append("            default:\n");
    sb.append("                spine_skip_value(&r, op);   /* unknown field code: skip by width */\n");
    sb.append("                break;\n");
    sb.append("            }\n");
    sb.append("            if (!spine_reader_ok(&r)) goto fail;\n");
    sb.append("        }\n");
    sb.append("    }\n");
    sb.append("    return doc;\n");
    sb.append("fail:\n");
    sb.append("    spine_free(doc);\n");
    sb.append("    return NULL;\n");
    sb.append("}\n");
    return sb.toString();
  }

  // ==========================================================================
  // helpers
  // ==========================================================================

  private static String up(String s) { return s.toUpperCase(Locale.ROOT); }
  private static String rootFc(Field f) { return "FC_" + up(f.name); }
  private static String memberFc(Struct s, Field f) { return "FC_" + up(s.name) + "_" + up(f.name); }

  private static void writeFile(File outDir, String name, String content, Result r,
                                Consumer<String> say, String dir) throws Exception {
    File f = new File(outDir, name);
    Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    r.written.add(f);
    say.accept("wrote " + dir + "/" + name);
  }

  private static void copyResource(String res, File dest, Result r, Consumer<String> say, String dir)
      throws Exception {
    try (InputStream in = Compiler.class.getResourceAsStream(res)) {
      if (in == null) {
        r.errors++;
        say.accept("ERROR: missing bundled runtime resource " + res);
        return;
      }
      Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
      r.written.add(dest);
      say.accept("wrote " + dir + "/" + dest.getName());
    }
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
