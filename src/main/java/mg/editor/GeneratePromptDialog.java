package mg.editor;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.geometry.Insets;
import javafx.stage.Window;

import java.util.Optional;

/**
 * A generation prompt with parameters: a multi-line prompt plus the output size
 * (square, in pixels) with a sane default. Used for PixelLab text-to-image
 * (pixflux) and style (bitforge). The size is clamped to {@code maxSize} (the
 * endpoint's limit).
 */
public final class GeneratePromptDialog {
  private GeneratePromptDialog() {}

  public record Result(String prompt, int size) {}

  public static Optional<Result> ask(Window owner, String header, String prompt, int defaultSize, int maxSize) {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Generate");
    dialog.setHeaderText(header);
    ButtonType ok = new ButtonType("Generate", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

    TextArea area = new TextArea(prompt == null ? "" : prompt);
    area.setWrapText(true);
    area.setPrefRowCount(5);
    area.setPrefColumnCount(46);
    Spinner<Integer> size = new Spinner<>(16, maxSize, Math.min(defaultSize, maxSize));
    size.setEditable(true);

    GridPane g = new GridPane();
    g.setHgap(8);
    g.setVgap(8);
    g.setPadding(new Insets(10));
    g.add(new Label("prompt"), 0, 0);
    g.add(area, 1, 0);
    g.add(new Label("size (px)"), 0, 1);
    g.add(size, 1, 1);
    dialog.getDialogPane().setContent(g);
    dialog.setResizable(true);
    javafx.application.Platform.runLater(area::requestFocus);

    Optional<ButtonType> r = dialog.showAndWait();
    if (r.isPresent() && r.get() == ok && !area.getText().isBlank()) {
      return Optional.of(new Result(area.getText().strip(), size.getValue()));
    }
    return Optional.empty();
  }
}
