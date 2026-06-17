package mg.editor;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * A modal popup for selecting a rectangular region of an image by dragging — used
 * to pull an animation frame or a crop rectangle out of a sheet when the
 * automatic tooling gets it wrong. Returns the selection in image pixel
 * coordinates, or empty if cancelled.
 */
public final class RegionSelectDialog {
  private RegionSelectDialog() {}

  /** show the dialog; returns {@code [x, y, w, h]} in image pixels, or empty. */
  public static Optional<int[]> show(Window owner, BufferedImage img) {
    int iw = img.getWidth();
    int ih = img.getHeight();
    double scale = Math.min(12.0, Math.max(1.0, 640.0 / Math.max(iw, ih)));
    double dw = iw * scale;
    double dh = ih * scale;

    ImageView view = new ImageView(BwCanvas.toFxImage(img));
    view.setFitWidth(dw);
    view.setFitHeight(dh);
    view.setPreserveRatio(false);
    view.setSmooth(false);

    Canvas overlay = new Canvas(dw, dh);
    Pane stack = new Pane(view, overlay);
    stack.setPrefSize(dw, dh);

    double[] sel = {0, 0, 0, 0}; // x,y,w,h in image coords
    double[] start = {0, 0};
    Label readout = new Label("drag to select a region");

    overlay.setOnMousePressed(e -> {
      start[0] = clamp(e.getX() / scale, 0, iw);
      start[1] = clamp(e.getY() / scale, 0, ih);
    });
    overlay.setOnMouseDragged(e -> {
      double cx = clamp(e.getX() / scale, 0, iw);
      double cy = clamp(e.getY() / scale, 0, ih);
      sel[0] = Math.min(start[0], cx);
      sel[1] = Math.min(start[1], cy);
      sel[2] = Math.abs(cx - start[0]);
      sel[3] = Math.abs(cy - start[1]);
      draw(overlay, scale, sel);
      readout.setText(String.format("x=%d y=%d  %d×%d",
          (int) sel[0], (int) sel[1], (int) Math.round(sel[2]), (int) Math.round(sel[3])));
    });

    Stage stage = new Stage();
    stage.initOwner(owner);
    stage.initModality(Modality.WINDOW_MODAL);
    stage.setTitle("Select region");

    Optional<int[]>[] result = new Optional[] {Optional.empty()};
    Button ok = new Button("Use selection");
    ok.setOnAction(e -> {
      int x = (int) Math.round(sel[0]);
      int y = (int) Math.round(sel[1]);
      int w = (int) Math.round(sel[2]);
      int h = (int) Math.round(sel[3]);
      if (w >= 1 && h >= 1) {
        result[0] = Optional.of(new int[] {x, y, Math.min(w, iw - x), Math.min(h, ih - y)});
      }
      stage.close();
    });
    Button cancel = new Button("Cancel");
    cancel.setOnAction(e -> stage.close());
    HBox bar = new HBox(8, readout, new Pane(), ok, cancel);
    HBox.setHgrow(bar.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.setPadding(new Insets(8));

    BorderPane rootPane = new BorderPane(stack);
    rootPane.setBottom(bar);
    stage.setScene(new Scene(rootPane));
    stage.showAndWait();
    return result[0];
  }

  private static void draw(Canvas overlay, double scale, double[] sel) {
    GraphicsContext g = overlay.getGraphicsContext2D();
    g.clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
    g.setStroke(Color.web("#1565c0"));
    g.setLineWidth(1.5);
    g.setFill(Color.color(0.1, 0.4, 0.8, 0.2));
    double x = sel[0] * scale;
    double y = sel[1] * scale;
    double w = sel[2] * scale;
    double h = sel[3] * scale;
    g.fillRect(x, y, w, h);
    g.strokeRect(x, y, w, h);
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
