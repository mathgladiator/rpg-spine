package mg.editor;

import java.io.File;

/**
 * The per-document asset folder convention. Every document keeps its generated
 * assets in sibling folders named after the file (not a shared {@code ref/}), so
 * the structure stays legible: {@code goblin.monster} → {@code goblin.ref/} (full
 * colour references) and {@code goblin.ext/} (extracted 1-bit frames);
 * {@code vault.story} → {@code vault.ref/} / {@code vault.ext/}, and so on.
 */
public final class RefLayout {
  private RefLayout() {}

  /** the document's base name with its extension stripped (e.g. {@code goblin}). */
  public static String base(File doc) {
    String n = doc.getName();
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  /** the {@code <base>.ref} folder beside {@code doc} (full-colour references). */
  public static File refDir(File doc) {
    return new File(doc.getParentFile(), base(doc) + ".ref");
  }

  /** the {@code <base>.ext} folder beside {@code doc} (extracted 1-bit frames). */
  public static File extDir(File doc) {
    return new File(doc.getParentFile(), base(doc) + ".ext");
  }

  /** the {@code <base>.ext} folder name only (for building project-relative paths). */
  public static String extName(File doc) {
    return base(doc) + ".ext";
  }
}
