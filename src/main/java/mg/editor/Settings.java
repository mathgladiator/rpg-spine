package mg.editor;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/**
 * Persistent application settings, stored as a properties file alongside the
 * recent-projects list in the platform per-user config directory
 * ({@link RecentProjects#configDir()}). This is where the Grok (xAI) API key
 * used for AI assistance lives. The file can hold secrets, so it is created
 * with owner-only permissions wherever the OS supports POSIX file modes.
 */
public final class Settings {
  public static final String GROK_API_KEY = "grok.api.key";
  public static final String GROK_MODEL = "grok.model";
  public static final String DEFAULT_GROK_MODEL = "grok-4";

  public static final String PIXELLAB_API_KEY = "pixellab.api.key";

  private Settings() {}

  /** the settings file location (in the shared per-user config dir). */
  public static File store() {
    return new File(RecentProjects.configDir(), "settings.properties");
  }

  private static Properties load() {
    Properties p = new Properties();
    File f = store();
    if (f.exists()) {
      try (InputStream in = Files.newInputStream(f.toPath())) {
        p.load(in);
      } catch (Exception ignore) {
        // a corrupt/unreadable file just means default (empty) settings
      }
    }
    return p;
  }

  /** the value for {@code key}, or {@code fallback} when unset/blank. */
  public static String get(String key, String fallback) {
    String v = load().getProperty(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }

  /** set (or, for a blank value, clear) a setting and persist immediately. */
  public static void set(String key, String value) {
    Properties p = load();
    if (value == null || value.isBlank()) {
      p.remove(key);
    } else {
      p.setProperty(key, value.strip());
    }
    persist(p);
  }

  public static String grokApiKey() {
    return get(GROK_API_KEY, "");
  }

  public static String grokModel() {
    return get(GROK_MODEL, DEFAULT_GROK_MODEL);
  }

  public static boolean hasGrokApiKey() {
    return !grokApiKey().isBlank();
  }

  public static String pixellabApiKey() {
    return get(PIXELLAB_API_KEY, "");
  }

  public static boolean hasPixellabApiKey() {
    return !pixellabApiKey().isBlank();
  }

  private static void persist(Properties p) {
    File f = store();
    try (OutputStream out = Files.newOutputStream(f.toPath())) {
      p.store(out, "rpg-spine application settings — may contain secrets");
    } catch (Exception ignore) {
      // best effort; not fatal if we cannot persist
    }
    restrictPermissions(f);
  }

  /** lock the file down to the owner on POSIX systems; a no-op elsewhere. */
  private static void restrictPermissions(File f) {
    try {
      Set<PosixFilePermission> ownerOnly =
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(f.toPath(), ownerOnly);
    } catch (Exception ignore) {
      // non-POSIX (Windows) or unsupported filesystem — leave default perms
    }
  }
}
