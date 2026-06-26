package mg.editor;

import mg.editor.dungeon.Dungeon;
import mg.editor.item.Item;
import mg.editor.monster.Monster;
import mg.editor.story.Story;
import mg.editor.world.World;
import mg.tokens.SpineLangException;
import mg.tree.Parser;
import mg.tree.Root;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans a project for asset problems:
 * <ul>
 *   <li><b>lost</b> — an image referenced by a document that does not exist on disk.
 *   <li><b>orphan</b> — an image file on disk that no document references.
 * </ul>
 * Also yields the set of files that have <b>errors</b> (a document that fails to
 * parse/load, or that references a lost image) so the tree can flag them.
 */
public final class AssetAudit {
  private AssetAudit() {}

  /** a referenced image that is missing, and the document that wanted it. */
  public record Lost(File owner, String ref, File resolved) {}

  /** a monster id placed in a dungeon that no .monster file defines. */
  public record MissingMonster(File owner, String id) {}

  public static final class Result {
    public final List<Lost> lost = new ArrayList<>();
    public final List<File> orphans = new ArrayList<>();
    public final List<MissingMonster> missingMonsters = new ArrayList<>();
    /** canonical files that have any error (parse failure, a lost reference, or a missing monster). */
    public final Set<File> errored = new HashSet<>();
  }

  public static Result run(File root) {
    Result r = new Result();
    if (root == null || !root.isDirectory()) {
      return r;
    }
    List<File> all = new ArrayList<>();
    collect(root, all);

    // first, every monster id defined by a .monster file (for dungeon placement validation)
    Set<String> knownMonsters = new HashSet<>();
    for (File f : all) {
      if (f.getName().toLowerCase().endsWith(".monster")) {
        try {
          knownMonsters.add(Monster.load(f).id);
        } catch (Exception ignore) {
          // a broken .monster surfaces as an errored file below
        }
      }
    }

    Set<File> referenced = new HashSet<>();
    for (File f : all) {
      String name = f.getName().toLowerCase();
      try {
        if (name.endsWith(".monster")) {
          referenceCheck(r, referenced, f, Monster.load(f).imageRefs());
        } else if (name.endsWith(".item")) {
          referenceCheck(r, referenced, f, Item.load(f).imageRefs());
        } else if (name.endsWith(".world")) {
          referenceCheck(r, referenced, f, World.load(f).imageRefs());
        } else if (name.endsWith(".story")) {
          Story story = Story.load(f);
          referenceCheck(r, referenced, f, story.imageRefs());
          if (!story.lint().isEmpty()) {
            r.errored.add(canon(f));
          }
        } else if (name.endsWith(".dungeon")) {
          checkDungeon(r, knownMonsters, f);
        } else if (name.endsWith(".rpg")) {
          checkRpg(r, f);
        }
      } catch (Exception ex) {
        r.errored.add(canon(f));
      }
    }

    for (File f : all) {
      if (ImagePicker.isImage(f) && !referenced.contains(canon(f))) {
        r.orphans.add(f);
      }
    }
    return r;
  }

  private static void referenceCheck(Result r, Set<File> referenced, File owner, List<String> refs) {
    File base = owner.getParentFile();
    for (String ref : refs) {
      File resolved = new File(ref);
      if (!resolved.isAbsolute() && base != null) {
        resolved = new File(base, ref);
      }
      referenced.add(canon(resolved));
      if (!resolved.exists()) {
        r.lost.add(new Lost(owner, ref, resolved));
        r.errored.add(canon(owner));
      }
    }
  }

  private static void checkDungeon(Result r, Set<String> knownMonsters, File f) throws Exception {
    Dungeon d = Dungeon.load(f);
    for (String id : d.monsterIds()) {
      if (!knownMonsters.contains(id)) {
        r.missingMonsters.add(new MissingMonster(f, id));
        r.errored.add(canon(f));
      }
    }
  }

  private static void checkRpg(Result r, File f) {
    try {
      Root root = new Root();
      Parser.merge_string(root, f.getName(), Files.readString(f.toPath()));
    } catch (SpineLangException | RuntimeException | java.io.IOException ex) {
      r.errored.add(canon(f));
    }
  }

  private static void collect(File dir, List<File> out) {
    File[] kids = dir.listFiles();
    if (kids == null) {
      return;
    }
    for (File k : kids) {
      if (k.getName().equals("target")) {
        continue;
      }
      if (k.isDirectory()) {
        collect(k, out);
      } else {
        out.add(k);
      }
    }
  }

  /** canonical file for stable identity comparisons (falls back to absolute). */
  static File canon(File f) {
    try {
      return f.getCanonicalFile();
    } catch (Exception e) {
      return f.getAbsoluteFile();
    }
  }
}
