package mg.editor;

import javafx.application.Application;

import java.io.File;

/**
 * Bridges the plain {@code mg.Main} entry point into the JavaFX world. Keeping
 * the {@link Application} subclass out of the process main class lets the fat
 * jar launch from the classpath without a module path, sidestepping the
 * "JavaFX runtime components are missing" guard.
 */
public final class EditorLauncher {
  /** the directory the editor was pointed at; read back inside {@link EditorApp}. */
  static volatile File ROOT;

  private EditorLauncher() {}

  public static void open(File dir) {
    ROOT = dir.getAbsoluteFile();
    Application.launch(EditorApp.class);
  }

  /**
   * Launch with no preset directory — used when the jar is double-clicked. The
   * app auto-opens the most recent project, and only prompts for a folder when
   * there are no recent projects.
   */
  public static void openDefault() {
    ROOT = null;
    Application.launch(EditorApp.class);
  }
}
