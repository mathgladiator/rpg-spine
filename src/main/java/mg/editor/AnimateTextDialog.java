package mg.editor;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Options for PixelLab text animation: the action ("walk") and how strongly to
 * follow the reference image (PixelLab's {@code image_guidance_scale}, 1..20).
 * The API default of 1.4 ignores the reference, so this defaults higher.
 */
public final class AnimateTextDialog {
  private AnimateTextDialog() {}

  public record Result(String action, double guidance) {}

  public static Optional<Result> ask(Window owner, String header, String defaultAction) {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Animate");
    dialog.setHeaderText(header);
    ButtonType ok = new ButtonType("Animate", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

    TextField action = new TextField(defaultAction == null ? "walk" : defaultAction);
    Slider guidance = new Slider(1, 20, 6);
    guidance.setShowTickMarks(true);
    guidance.setMajorTickUnit(5);
    Label gval = new Label("6");
    guidance.valueProperty().addListener((o, a, b) -> gval.setText(String.valueOf(b.intValue())));

    GridPane g = new GridPane();
    g.setHgap(8);
    g.setVgap(8);
    g.setPadding(new Insets(10));
    g.addRow(0, new Label("action"), action);
    g.addRow(1, new Label("follow reference"), guidance, gval);
    g.add(new Label("(low = invent from text · high = stick to the reference image)"), 1, 2, 2, 1);
    dialog.getDialogPane().setContent(g);

    Optional<ButtonType> r = dialog.showAndWait();
    if (r.isPresent() && r.get() == ok && !action.getText().isBlank()) {
      return Optional.of(new Result(action.getText().strip(), guidance.getValue()));
    }
    return Optional.empty();
  }
}
