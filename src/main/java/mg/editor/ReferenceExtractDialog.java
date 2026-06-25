package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import mg.assets.BwCodec;
import mg.assets.Dither;
import mg.assets.Mono;
import mg.editor.asset.ExtractSettings;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Optional;

import javax.imageio.ImageIO;

/**
 * The reference → black-and-white extraction step. Given a full-colour reference
 * image, drag-select a region, resample it to a target resolution (Java2D
 * bilinear), then convert to 1-bit with a chosen algorithm (threshold or dither)
 * — with a live preview so you can "play with the black and white transform."
 * Saves the result as a real 1-bit PNG inside the document's folder and returns
 * its path, ready to slot into an animation or image field.
 */
public final class ReferenceExtractDialog {
  private ReferenceExtractDialog() {}

  public static Optional<File> open(Window owner, File refFile, File baseDir, int defaultSize,
                                    ExtractSettings settings, Runnable onSettings) {
    BufferedImage ref;
    try {
      ref = ImageIO.read(refFile);
    } catch (Exception ex) {
      Log.error("could not read reference " + refFile.getName(), ex);
      return Optional.empty();
    }
    if (ref == null) {
      return Optional.empty();
    }
    int iw = ref.getWidth();
    int ih = ref.getHeight();
    double scale = Math.min(1.0, 520.0 / Math.max(iw, ih));
    double dw = iw * scale;
    double dh = ih * scale;

    ImageView view = new ImageView(new Image(refFile.toURI().toString(), dw, dh, true, true));
    view.setFitWidth(dw);
    view.setFitHeight(dh);
    Canvas overlay = new Canvas(dw, dh);
    Pane stack = new Pane(view, overlay);
    stack.setPrefSize(dw, dh);

    double[] sel = {0, 0, iw, ih}; // x,y,w,h in image coords; default = whole image
    double[] start = {0, 0};
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
      drawSel(overlay, scale, sel);
    });
    drawSel(overlay, scale, sel);

    // reseed from the last-used settings on this document
    int seedW = settings != null && settings.width > 0 ? settings.width : defaultSize;
    int seedH = settings != null && settings.height > 0 ? settings.height : defaultSize;
    Spinner<Integer> tw = new Spinner<>(1, 1024, seedW);
    tw.setEditable(true);
    Spinner<Integer> th = new Spinner<>(1, 1024, seedH);
    th.setEditable(true);
    ChoiceBox<Dither.Algo> method = new ChoiceBox<>();
    method.getItems().setAll(Dither.Algo.values());
    method.setValue(enumOr(Dither.Algo.class, settings == null ? null : settings.algo, Dither.Algo.FLOYD));
    Spinner<Integer> level = new Spinner<>(1, 254, settings != null ? settings.threshold : 128);
    level.setEditable(true);
    ChoiceBox<Dither.AlphaMode> alpha = new ChoiceBox<>();
    alpha.getItems().setAll(Dither.AlphaMode.values());
    alpha.setValue(enumOr(Dither.AlphaMode.class, settings == null ? null : settings.alpha, Dither.AlphaMode.WHITE));
    ChoiceBox<String> background = new ChoiceBox<>();
    background.getItems().setAll("none", "edge scan");
    background.setValue(settings != null && "edge scan".equals(settings.background) ? "edge scan" : "none");

    ImageView preview = new ImageView();
    preview.setPreserveRatio(true);
    preview.setSmooth(false);
    preview.setFitWidth(160);
    preview.setFitHeight(160);

    // default extracted frames into an ext/ subfolder to keep things tidy
    TextField outName = new TextField("ext/" + baseName(refFile) + "-frame.png");

    Label sizeLabel = new Label();
    Runnable doPreview = () -> {
      BufferedImage mono = render(ref, sel, tw.getValue(), th.getValue(), method.getValue(), level.getValue(), alpha.getValue());
      boolean[][] bg = "edge scan".equals(background.getValue()) ? Mono.backgroundMask(mono) : null;
      preview.setImage(previewImage(mono, bg));
      sizeLabel.setText(sizeSummary(mono, bg));
    };
    Button previewBtn = new Button("Preview");
    previewBtn.setOnAction(e -> doPreview.run());

    Button wholeImage = new Button("Whole image");
    wholeImage.setOnAction(e -> {
      sel[0] = 0;
      sel[1] = 0;
      sel[2] = iw;
      sel[3] = ih;
      drawSel(overlay, scale, sel);
      doPreview.run();
    });

    GridPane controls = new GridPane();
    controls.setHgap(8);
    controls.setVgap(8);
    controls.setPadding(new Insets(8));
    int r = 0;
    controls.addRow(r++, new Label("target W"), tw, new Label("H"), th);
    controls.addRow(r++, new Label("B&W"), method, new Label("threshold"), level);
    controls.addRow(r++, new Label("transparent px"), alpha, new Label("background"), background);
    controls.addRow(r++, new Label("save as"), outName);
    controls.add(previewBtn, 0, r);
    controls.add(preview, 1, r, 3, 1);
    controls.add(sizeLabel, 0, r + 1, 4, 1);
    Label legend = new Label("preview: green = transparent · blue = detected background");
    legend.setStyle("-fx-opacity: 0.7;");
    controls.add(legend, 0, r + 2, 4, 1);

    Stage stage = new Stage();
    stage.initOwner(owner);
    stage.initModality(Modality.WINDOW_MODAL);
    stage.setTitle("Extract region → black & white — " + refFile.getName());

    Optional<File>[] result = new Optional[] {Optional.empty()};
    Button save = new Button("Save frame");
    save.setOnAction(e -> {
      String name = outName.getText().strip();
      if (name.isEmpty()) {
        return;
      }
      if (!name.toLowerCase().endsWith(".png")) {
        name = name + ".png";
      }
      try {
        BufferedImage mono = render(ref, sel, tw.getValue(), th.getValue(), method.getValue(), level.getValue(), alpha.getValue());
        if ("edge scan".equals(background.getValue())) {
          boolean[][] bg = Mono.backgroundMask(mono);
          for (int yy = 0; yy < mono.getHeight(); yy++) {
            for (int xx = 0; xx < mono.getWidth(); xx++) {
              if (bg[xx][yy]) {
                Mono.setTransparent(mono, xx, yy);
              }
            }
          }
        }
        File out = new File(baseDir, name);
        Mono.savePng(mono, out);
        Log.info("extracted " + out.getName() + " (" + mono.getWidth() + "×" + mono.getHeight() + ") from " + refFile.getName());
        if (settings != null) {
          settings.used = true;
          settings.width = tw.getValue();
          settings.height = th.getValue();
          settings.algo = method.getValue().name();
          settings.threshold = level.getValue();
          settings.alpha = alpha.getValue().name();
          settings.background = background.getValue();
          if (onSettings != null) {
            onSettings.run();
          }
        }
        result[0] = Optional.of(out);
        stage.close();
      } catch (Exception ex) {
        Log.error("extract failed", ex);
      }
    });
    Button cancel = new Button("Cancel");
    cancel.setOnAction(e -> stage.close());
    HBox bar = new HBox(8, save, cancel);
    bar.setAlignment(Pos.CENTER_RIGHT);
    bar.setPadding(new Insets(8));

    VBox imageCol = new VBox(6, stack, wholeImage);
    BorderPane center = new BorderPane(imageCol);
    center.setRight(new VBox(new Label("drag a region on the reference"), controls));
    BorderPane rootPane = new BorderPane(center);
    rootPane.setBottom(bar);
    stage.setScene(new Scene(rootPane));
    doPreview.run();
    stage.showAndWait();
    return result[0];
  }

  /**
   * Compare the PNG size of the extracted frame against the single-byte RLE/detail
   * bank format ({@link BwCodec}), so you can judge whether the new format is a win
   * before saving. Measures exactly what would be written, transparency included.
   */
  private static String sizeSummary(BufferedImage mono, boolean[][] bg) {
    BufferedImage out = Mono.copy(mono);
    if (bg != null) {
      for (int y = 0; y < out.getHeight(); y++) {
        for (int x = 0; x < out.getWidth(); x++) {
          if (bg[x][y]) {
            Mono.setTransparent(out, x, y);
          }
        }
      }
    }
    int rle = BwCodec.encodedSize(BwCodec.pixelsOf(out));
    long png = -1;
    try {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      ImageIO.write(out, "png", bo);
      png = bo.size();
    } catch (Exception ignored) {
      // png stays -1 (unavailable)
    }
    if (png < 0) {
      return "size — format " + rle + " B";
    }
    int pct = (int) Math.round(100.0 * rle / png);
    return "size — png " + png + " B · format " + rle + " B (" + pct + "% of png)";
  }

  /** crop+scale the region (bilinear, alpha-preserving) then convert to 1-bit. */
  private static BufferedImage render(BufferedImage ref, double[] sel, int tw, int th,
                                      Dither.Algo method, int level, Dither.AlphaMode alpha) {
    int x = (int) Math.round(sel[0]);
    int y = (int) Math.round(sel[1]);
    int w = Math.max(1, (int) Math.round(sel[2]));
    int h = Math.max(1, (int) Math.round(sel[3]));
    w = Math.min(w, ref.getWidth() - x);
    h = Math.min(h, ref.getHeight() - y);
    // ARGB so a transparent reference keeps its alpha through the resize
    BufferedImage scaled = new BufferedImage(Math.max(1, tw), Math.max(1, th), BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = scaled.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(ref, 0, 0, scaled.getWidth(), scaled.getHeight(), x, y, x + w, y + h, null);
    g.dispose();
    return Dither.apply(scaled, method, level, alpha);
  }

  /** preview: black/white normally, transparent green, detected background blue. */
  private static javafx.scene.image.WritableImage previewImage(BufferedImage mono, boolean[][] bg) {
    int w = mono.getWidth();
    int h = mono.getHeight();
    javafx.scene.image.WritableImage out = new javafx.scene.image.WritableImage(w, h);
    javafx.scene.image.PixelWriter pw = out.getPixelWriter();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        Color c;
        if (bg != null && bg[x][y]) {
          c = Color.web("#1565c0");
        } else {
          c = switch (Mono.state(mono, x, y)) {
            case Mono.BLACK -> Color.BLACK;
            case Mono.TRANSPARENT -> Color.LIMEGREEN;
            default -> Color.WHITE;
          };
        }
        pw.setColor(x, y, c);
      }
    }
    return out;
  }

  private static void drawSel(Canvas overlay, double scale, double[] sel) {
    GraphicsContext g = overlay.getGraphicsContext2D();
    g.clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
    g.setStroke(Color.web("#1565c0"));
    g.setLineWidth(1.5);
    g.setFill(Color.color(0.1, 0.4, 0.8, 0.2));
    g.fillRect(sel[0] * scale, sel[1] * scale, sel[2] * scale, sel[3] * scale);
    g.strokeRect(sel[0] * scale, sel[1] * scale, sel[2] * scale, sel[3] * scale);
  }

  private static String baseName(File f) {
    String n = f.getName();
    if (n.toLowerCase().endsWith(".ref.png")) {
      return n.substring(0, n.length() - ".ref.png".length());
    }
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E dflt) {
    if (name == null) {
      return dflt;
    }
    try {
      return Enum.valueOf(type, name);
    } catch (Exception e) {
      return dflt;
    }
  }
}
