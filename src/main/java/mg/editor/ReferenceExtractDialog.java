package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
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

  public static Optional<File> open(Window owner, File refFile, File baseDir, String extDirName,
                                    int defaultSize, ExtractSettings settings, Runnable onSettings) {
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
    final int iw = ref.getWidth();
    final int ih = ref.getHeight();
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

    // reseed conversion controls from the last-used settings on this document
    int seedW = settings != null && settings.width > 0 ? settings.width : defaultSize;
    int seedH = settings != null && settings.height > 0 ? settings.height : defaultSize;
    Spinner<Integer> tw = intSpinner(1, 1024, seedW);
    Spinner<Integer> th = intSpinner(1, 1024, seedH);
    ChoiceBox<Dither.Algo> method = new ChoiceBox<>();
    method.getItems().setAll(Dither.Algo.values());
    method.setValue(enumOr(Dither.Algo.class, settings == null ? null : settings.algo, Dither.Algo.FLOYD));
    Spinner<Integer> level = intSpinner(1, 254, settings != null ? settings.threshold : 128);
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
    Label sizeLabel = new Label();

    // default extracted frames into the document's <base>.ext/ subfolder to keep things tidy
    TextField outName = new TextField(extDirName + "/" + baseName(refFile) + "-frame.png");

    // numeric crop-region editors (commit on focus loss / Enter)
    Spinner<Integer> cropX = intSpinner(0, iw, 0);
    Spinner<Integer> cropY = intSpinner(0, ih, 0);
    Spinner<Integer> cropW = intSpinner(1, iw, iw);
    Spinner<Integer> cropH = intSpinner(1, ih, ih);

    boolean[] syncing = {false};

    Runnable doPreview = () -> {
      BufferedImage mono = render(ref, sel, tw.getValue(), th.getValue(), method.getValue(), level.getValue(), alpha.getValue());
      boolean[][] bg = "edge scan".equals(background.getValue()) ? Mono.backgroundMask(mono) : null;
      preview.setImage(previewImage(mono, bg));
      sizeLabel.setText(sizeSummary(mono, bg));
    };

    // any programmatic change to sel -> clamp, mirror into the spinners, redraw, preview
    Runnable applySel = () -> {
      clampSel(sel, iw, ih);
      syncing[0] = true;
      cropX.getValueFactory().setValue((int) Math.round(sel[0]));
      cropY.getValueFactory().setValue((int) Math.round(sel[1]));
      cropW.getValueFactory().setValue((int) Math.round(sel[2]));
      cropH.getValueFactory().setValue((int) Math.round(sel[3]));
      syncing[0] = false;
      drawSel(overlay, scale, sel);
      doPreview.run();
    };

    // spinner edit -> sel (guarded so applySel's own writes don't recurse)
    Runnable selFromSpinners = () -> {
      if (syncing[0]) {
        return;
      }
      sel[0] = cropX.getValue();
      sel[1] = cropY.getValue();
      sel[2] = cropW.getValue();
      sel[3] = cropH.getValue();
      applySel.run();
    };

    // live preview: dropdowns immediate, numeric fields on value change / focus-loss commit
    method.valueProperty().addListener((o, a, b) -> doPreview.run());
    alpha.valueProperty().addListener((o, a, b) -> doPreview.run());
    background.valueProperty().addListener((o, a, b) -> doPreview.run());
    liveSpinner(tw, doPreview);
    liveSpinner(th, doPreview);
    liveSpinner(level, doPreview);
    liveSpinner(cropX, selFromSpinners);
    liveSpinner(cropY, selFromSpinners);
    liveSpinner(cropW, selFromSpinners);
    liveSpinner(cropH, selFromSpinners);

    // selection mode: draw a new region vs. drag the existing one around
    ToggleGroup modes = new ToggleGroup();
    ToggleButton selectMode = new ToggleButton("Select");
    ToggleButton moveMode = new ToggleButton("Move");
    selectMode.setToggleGroup(modes);
    moveMode.setToggleGroup(modes);
    selectMode.setSelected(true);
    selectMode.setOnAction(e -> selectMode.setSelected(true)); // keep one always selected
    moveMode.setOnAction(e -> moveMode.setSelected(true));

    // when checked, drawing a region is constrained to the target W:H aspect ratio
    CheckBox lockAspect = new CheckBox("Lock to target aspect");
    lockAspect.setOnAction(e -> {
      if (lockAspect.isSelected() && th.getValue() > 0) {
        sel[3] = sel[2] * th.getValue() / tw.getValue(); // conform current height to aspect
        applySel.run();
      }
    });

    double[] start = {0, 0};       // select: anchor corner
    double[] dragFrom = {0, 0};    // move: mouse-down point
    double[] selOrigin = {0, 0};   // move: sel x,y at mouse-down
    overlay.setOnMousePressed(e -> {
      double mx = clamp(e.getX() / scale, 0, iw);
      double my = clamp(e.getY() / scale, 0, ih);
      if (moveMode.isSelected()) {
        dragFrom[0] = mx;
        dragFrom[1] = my;
        selOrigin[0] = sel[0];
        selOrigin[1] = sel[1];
      } else {
        start[0] = mx;
        start[1] = my;
      }
    });
    overlay.setOnMouseDragged(e -> {
      double mx = clamp(e.getX() / scale, 0, iw);
      double my = clamp(e.getY() / scale, 0, ih);
      if (moveMode.isSelected()) {
        sel[0] = selOrigin[0] + (mx - dragFrom[0]);
        sel[1] = selOrigin[1] + (my - dragFrom[1]);
      } else {
        double w = Math.abs(mx - start[0]);
        double h = Math.abs(my - start[1]);
        if (lockAspect.isSelected() && th.getValue() > 0) {
          double aspect = (double) tw.getValue() / th.getValue();
          w = Math.max(w, h * aspect); // grow the box to cover the cursor, then fix h
          h = w / aspect;
        }
        sel[0] = mx >= start[0] ? start[0] : start[0] - w;
        sel[1] = my >= start[1] ? start[1] : start[1] - h;
        sel[2] = w;
        sel[3] = h;
      }
      applySel.run();
    });

    // selection manipulation buttons (common conventions)
    Button whole = new Button("Whole");
    whole.setOnAction(e -> { sel[0] = 0; sel[1] = 0; sel[2] = iw; sel[3] = ih; applySel.run(); });
    Button centerBtn = new Button("Center");
    centerBtn.setOnAction(e -> { sel[0] = (iw - sel[2]) / 2.0; sel[1] = (ih - sel[3]) / 2.0; applySel.run(); });
    Button halfCenter = new Button("Half & center");
    halfCenter.setOnAction(e -> {
      sel[2] = Math.max(1, Math.round(sel[2] / 2.0));
      sel[3] = Math.max(1, Math.round(sel[3] / 2.0));
      sel[0] = (iw - sel[2]) / 2.0;
      sel[1] = (ih - sel[3]) / 2.0;
      applySel.run();
    });
    Button square = new Button("Square");
    square.setOnAction(e -> {
      double s = Math.min(sel[2], sel[3]);
      double midX = sel[0] + sel[2] / 2.0;
      double midY = sel[1] + sel[3] / 2.0;
      sel[2] = s;
      sel[3] = s;
      sel[0] = midX - s / 2.0;
      sel[1] = midY - s / 2.0;
      applySel.run();
    });
    Button expand = new Button("Expand +1");
    expand.setOnAction(e -> { sel[0] -= 1; sel[1] -= 1; sel[2] += 2; sel[3] += 2; applySel.run(); });
    Button shrink = new Button("Shrink −1");
    shrink.setOnAction(e -> {
      sel[0] += 1;
      sel[1] += 1;
      sel[2] = Math.max(1, sel[2] - 2);
      sel[3] = Math.max(1, sel[3] - 2);
      applySel.run();
    });
    Button doubleCenter = new Button("Double & center");
    doubleCenter.setOnAction(e -> {
      sel[2] = sel[2] * 2;
      sel[3] = sel[3] * 2;
      sel[0] = (iw - sel[2]) / 2.0;
      sel[1] = (ih - sel[3]) / 2.0;
      applySel.run();
    });
    Button cropBlack = new Button("Crop black");
    cropBlack.setOnAction(e -> snap(inkBounds(ref, sel, iw, ih), sel, applySel));
    Button fitContent = new Button("Fit content");
    fitContent.setOnAction(e -> snap(contentBounds(ref, sel, iw, ih), sel, applySel));
    Button previewBtn = new Button("Preview");
    previewBtn.setOnAction(e -> doPreview.run());

    // crop region: position (X/Y) and size (W/H) grouped into one compact block
    GridPane cropGrid = new GridPane();
    cropGrid.setHgap(8);
    cropGrid.setVgap(6);
    cropGrid.addRow(0, new Label("X"), cropX, new Label("W"), cropW);
    cropGrid.addRow(1, new Label("Y"), cropY, new Label("H"), cropH);

    HBox modeBar = new HBox(8, new Label("mode:"), selectMode, moveMode, lockAspect);
    modeBar.setAlignment(Pos.CENTER_LEFT);
    FlowPane selButtons = new FlowPane(6, 6,
        whole, centerBtn, square, halfCenter, doubleCenter, expand, shrink, cropBlack, fitContent);

    Label cropTitle = new Label("Crop region");
    cropTitle.setStyle("-fx-font-weight: bold;");

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
        ProjectRefresh.fire(); // a new extracted frame appeared on disk
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

    VBox imageCol = new VBox(8, stack, cropTitle, modeBar, cropGrid, selButtons);
    imageCol.setPadding(new Insets(8));
    BorderPane centerPane = new BorderPane(imageCol);
    centerPane.setRight(controls);
    BorderPane rootPane = new BorderPane(centerPane);
    rootPane.setBottom(bar);
    stage.setScene(new Scene(rootPane));
    applySel.run(); // initial overlay draw + spinner sync + preview
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

  // --------------------------------------------------------- selection helpers

  /** an editable integer spinner. */
  private static Spinner<Integer> intSpinner(int min, int max, int init) {
    Spinner<Integer> sp = new Spinner<>(min, max, Math.max(min, Math.min(max, init)));
    sp.setEditable(true);
    sp.setPrefWidth(80);
    return sp;
  }

  /** wire a spinner to fire {@code onChange} on value change and commit typed text on focus loss / Enter. */
  private static void liveSpinner(Spinner<Integer> sp, Runnable onChange) {
    sp.valueProperty().addListener((o, a, b) -> onChange.run());
    sp.getEditor().setOnAction(e -> commit(sp));
    sp.focusedProperty().addListener((o, was, now) -> {
      if (!now) {
        commit(sp);
      }
    });
  }

  /** parse and clamp the spinner's editor text into its value (reverting on garbage). */
  private static void commit(Spinner<Integer> sp) {
    SpinnerValueFactory.IntegerSpinnerValueFactory f =
        (SpinnerValueFactory.IntegerSpinnerValueFactory) sp.getValueFactory();
    try {
      int v = Integer.parseInt(sp.getEditor().getText().trim());
      f.setValue(Math.max(f.getMin(), Math.min(f.getMax(), v)));
    } catch (NumberFormatException ex) {
      sp.getEditor().setText(String.valueOf(sp.getValue()));
    }
  }

  /** keep {x,y,w,h} a valid rectangle inside the {@code iw}×{@code ih} image. */
  private static void clampSel(double[] sel, int iw, int ih) {
    sel[2] = Math.max(1, Math.min(sel[2], iw));
    sel[3] = Math.max(1, Math.min(sel[3], ih));
    sel[0] = Math.max(0, Math.min(sel[0], iw - sel[2]));
    sel[1] = Math.max(0, Math.min(sel[1], ih - sel[3]));
  }

  /**
   * The bounding box of "ink" (opaque, dark) pixels within the current selection,
   * for the Crop black button — snaps the region to the actual content. Returns
   * {x,y,w,h} or null if the selection holds no ink.
   */
  private static double[] inkBounds(BufferedImage ref, double[] sel, int iw, int ih) {
    int x0 = (int) Math.round(sel[0]);
    int y0 = (int) Math.round(sel[1]);
    int sw = (int) Math.round(sel[2]);
    int sh = (int) Math.round(sel[3]);
    x0 = Math.max(0, Math.min(x0, iw - 1));
    y0 = Math.max(0, Math.min(y0, ih - 1));
    int x1 = Math.min(iw, x0 + Math.max(1, sw));
    int y1 = Math.min(ih, y0 + Math.max(1, sh));
    int minX = x1, minY = y1, maxX = -1, maxY = -1;
    for (int y = y0; y < y1; y++) {
      for (int x = x0; x < x1; x++) {
        int argb = ref.getRGB(x, y);
        int a = (argb >>> 24) & 0xFF;
        int lum = (int) Math.round(0.299 * ((argb >> 16) & 0xFF)
            + 0.587 * ((argb >> 8) & 0xFF) + 0.114 * (argb & 0xFF));
        if (a >= 128 && lum < 128) {
          if (x < minX) minX = x;
          if (y < minY) minY = y;
          if (x > maxX) maxX = x;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (maxX < 0) {
      return null;
    }
    return new double[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
  }

  /**
   * The bounding box of opaque (non-transparent) pixels within the current
   * selection, for the Fit content button — trims away transparent margins
   * regardless of colour. Returns {x,y,w,h} or null if nothing is opaque.
   */
  private static double[] contentBounds(BufferedImage ref, double[] sel, int iw, int ih) {
    int x0 = Math.max(0, Math.min((int) Math.round(sel[0]), iw - 1));
    int y0 = Math.max(0, Math.min((int) Math.round(sel[1]), ih - 1));
    int x1 = Math.min(iw, x0 + Math.max(1, (int) Math.round(sel[2])));
    int y1 = Math.min(ih, y0 + Math.max(1, (int) Math.round(sel[3])));
    int minX = x1, minY = y1, maxX = -1, maxY = -1;
    for (int y = y0; y < y1; y++) {
      for (int x = x0; x < x1; x++) {
        if (((ref.getRGB(x, y) >>> 24) & 0xFF) >= 128) {
          if (x < minX) minX = x;
          if (y < minY) minY = y;
          if (x > maxX) maxX = x;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (maxX < 0) {
      return null;
    }
    return new double[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
  }

  /** copy a computed bounds (if any) into the selection and refresh. */
  private static void snap(double[] bounds, double[] sel, Runnable applySel) {
    if (bounds != null) {
      System.arraycopy(bounds, 0, sel, 0, 4);
      applySel.run();
    }
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
