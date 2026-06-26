package mg.editor;

import javafx.application.Platform;

/**
 * A process-wide hook so any code that creates files on disk (reference
 * generation, B&W extraction, codegen output, …) can ask the editor to rescan
 * and repaint the project tree. {@link EditorApp} registers the hook on startup;
 * file-producing actions call {@link #fire()} when they finish.
 */
public final class ProjectRefresh {
  private static Runnable hook;

  private ProjectRefresh() {}

  /** register the tree-refresh action (called once by {@link EditorApp}). */
  public static void set(Runnable r) {
    hook = r;
  }

  /** request a project rescan/repaint on the JavaFX thread (no-op if none registered). */
  public static void fire() {
    Runnable h = hook;
    if (h == null) {
      return;
    }
    if (Platform.isFxApplicationThread()) {
      h.run();
    } else {
      Platform.runLater(h);
    }
  }
}
