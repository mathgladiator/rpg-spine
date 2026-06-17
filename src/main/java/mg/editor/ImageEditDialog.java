package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;

/**
 * Opens a {@link BwImageEditor} for a single PNG in its own window, with
 * Save &amp; Close. Shared by the monster and item editors so "edit this image"
 * behaves identically wherever an image path appears.
 */
public final class ImageEditDialog {
  private ImageEditDialog() {}

  /** open {@code target} (created blank with a size prompt if it does not exist). */
  public static void open(Window owner, File target, Label status, Runnable onSaved) {
    try {
      BwImageEditor editor = new BwImageEditor(target, status);
      Stage stage = new Stage();
      stage.initOwner(owner);
      stage.setTitle(target.getName());

      Button save = new Button("Save & Close");
      save.setOnAction(e -> {
        try {
          editor.save();
          if (onSaved != null) {
            onSaved.run();
          }
        } catch (Exception ex) {
          error(ex);
        }
        stage.close();
      });
      Button close = new Button("Close");
      close.setOnAction(e -> stage.close());

      HBox bar = new HBox(8, save, close);
      bar.setAlignment(Pos.CENTER_RIGHT);
      bar.setPadding(new Insets(8));
      BorderPane pane = new BorderPane(editor.getNode());
      pane.setBottom(bar);

      stage.setScene(new Scene(pane, 880, 700));
      stage.show();
    } catch (Exception ex) {
      error(ex);
    }
  }

  private static void error(Throwable ex) {
    ex.printStackTrace();
    Alert a = new Alert(Alert.AlertType.ERROR,
        ex.getMessage() == null ? ex.toString() : ex.getMessage(), ButtonType.OK);
    a.showAndWait();
  }
}
