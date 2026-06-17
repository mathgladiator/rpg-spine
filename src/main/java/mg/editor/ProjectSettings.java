package mg.editor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Per-project settings stored in a {@code .project} file in the project root.
 * These drive asset conventions the tooling otherwise can't guess: the pixel
 * size of item icons and the cell size of animation frames. A single
 * {@link #current()} instance reflects the open project, defaulting to sensible
 * values when no {@code .project} exists yet.
 */
public final class ProjectSettings {
  public int iconSize = 32;     // large item-icon edge, px (also used as the default size)
  public int iconMed = 16;      // medium item-icon edge, px
  public int iconSmall = 8;     // small item-icon edge, px
  public int animCellW = 48;    // animation frame cell width, px
  public int animCellH = 48;    // animation frame cell height, px

  private static ProjectSettings current = new ProjectSettings();

  /** the settings for the currently open project (never null). */
  public static ProjectSettings current() {
    return current;
  }

  private static File file(File root) {
    return new File(root, ".project");
  }

  /** load (or default) the project settings for {@code root} and make them current. */
  public static ProjectSettings load(File root) {
    ProjectSettings p = new ProjectSettings();
    File f = file(root);
    if (f.exists()) {
      try {
        for (String line : Files.readAllLines(f.toPath())) {
          KV kv = KV.parse(line);
          if (kv == null || !kv.verb.equals("project")) {
            continue;
          }
          p.iconSize = kv.getInt("icon_size", p.iconSize);
          p.iconMed = kv.getInt("icon_med", p.iconMed);
          p.iconSmall = kv.getInt("icon_small", p.iconSmall);
          p.animCellW = kv.getInt("anim_cell_w", p.animCellW);
          p.animCellH = kv.getInt("anim_cell_h", p.animCellH);
        }
      } catch (Exception ex) {
        Log.error("failed to read .project; using defaults", ex);
      }
    }
    current = p;
    return p;
  }

  /** write the settings to {@code <root>/.project}. */
  public void save(File root) throws Exception {
    String body = "project"
        + " icon_size=" + iconSize
        + " icon_med=" + iconMed
        + " icon_small=" + iconSmall
        + " anim_cell_w=" + animCellW
        + " anim_cell_h=" + animCellH + "\n";
    Files.write(file(root).toPath(), body.getBytes(StandardCharsets.UTF_8));
  }
}
