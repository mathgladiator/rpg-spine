package mg.editor;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

import java.util.Optional;

/**
 * A prompt input with a roomy multi-line text area (AI prompts are often several
 * sentences). Returns the entered text, or empty if cancelled.
 */
public final class PromptDialog {
  private PromptDialog() {}

  public static Optional<String> ask(Window owner, String header, String initial) {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Prompt");
    dialog.setHeaderText(header);
    ButtonType ok = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

    TextArea area = new TextArea(initial == null ? "" : initial);
    area.setWrapText(true);
    area.setPrefRowCount(6);
    area.setPrefColumnCount(48);
    dialog.getDialogPane().setContent(area);
    dialog.setResizable(true);
    javafx.application.Platform.runLater(area::requestFocus);

    Optional<ButtonType> r = dialog.showAndWait();
    if (r.isPresent() && r.get() == ok && !area.getText().isBlank()) {
      return Optional.of(area.getText().strip());
    }
    return Optional.empty();
  }
}
