package mg.editor;

import javafx.scene.Node;

/** A document editor surface hosted in the center pane of {@link EditorApp}. */
public interface Editor {
  /** the JavaFX node to mount in the center of the window */
  Node getNode();

  /** persist the in-memory document back to disk */
  void save() throws Exception;

  /** whether there are unsaved changes */
  boolean isDirty();

  /** short human label for the status bar / title */
  String title();
}
