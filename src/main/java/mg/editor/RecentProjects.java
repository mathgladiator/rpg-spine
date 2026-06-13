package mg.editor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A small most-recently-used list of project folders, persisted to a
 * platform-conventional per-user config location:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\rpg-spine\recent.txt}</li>
 *   <li>macOS:   {@code ~/Library/Application Support/rpg-spine/recent.txt}</li>
 *   <li>Linux:   {@code $XDG_CONFIG_HOME/rpg-spine/recent.txt} (else {@code ~/.config/...})</li>
 * </ul>
 */
public final class RecentProjects {
  private static final int MAX = 10;

  private RecentProjects() {}

  /** the platform-appropriate config directory (created on demand). */
  public static File configDir() {
    String os = System.getProperty("os.name", "").toLowerCase();
    String home = System.getProperty("user.home", ".");
    File base;
    if (os.contains("win")) {
      String appData = System.getenv("APPDATA");
      base = (appData != null && !appData.isBlank()) ? new File(appData) : new File(home, "AppData/Roaming");
    } else if (os.contains("mac")) {
      base = new File(home, "Library/Application Support");
    } else {
      String xdg = System.getenv("XDG_CONFIG_HOME");
      base = (xdg != null && !xdg.isBlank()) ? new File(xdg) : new File(home, ".config");
    }
    File dir = new File(base, "rpg-spine");
    dir.mkdirs();
    return dir;
  }

  private static File store() {
    return new File(configDir(), "recent.txt");
  }

  /** the recent project folders, most-recent first, filtered to those that still exist. */
  public static List<File> load() {
    List<File> out = new ArrayList<>();
    File f = store();
    if (!f.exists()) {
      return out;
    }
    try {
      Set<String> seen = new LinkedHashSet<>();
      for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
        String path = line.strip();
        if (path.isEmpty() || !seen.add(path)) {
          continue;
        }
        File dir = new File(path);
        if (dir.isDirectory()) {
          out.add(dir);
        }
      }
    } catch (Exception ignore) {
      // a corrupt/unreadable list just means no recents
    }
    return out;
  }

  /** the single most-recent project that still exists, or null. */
  public static File mostRecent() {
    List<File> all = load();
    return all.isEmpty() ? null : all.get(0);
  }

  /** record a project as most-recently used, moving it to the front. */
  public static void add(File dir) {
    if (dir == null) {
      return;
    }
    File abs = dir.getAbsoluteFile();
    List<File> all = load();
    all.removeIf(f -> f.getAbsoluteFile().equals(abs));
    all.add(0, abs);
    while (all.size() > MAX) {
      all.remove(all.size() - 1);
    }
    persist(all);
  }

  public static void clear() {
    persist(new ArrayList<>());
  }

  private static void persist(List<File> list) {
    StringBuilder sb = new StringBuilder();
    for (File f : list) {
      sb.append(f.getAbsolutePath()).append('\n');
    }
    try {
      Files.write(store().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignore) {
      // best effort; not fatal if we can't persist recents
    }
  }
}
