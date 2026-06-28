package mg.editor;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import mg.assets.BwCodec;
import mg.assets.Dither;
import mg.assets.Mono;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Optional;

import javax.imageio.ImageIO;

/**
 * A simple black-and-white PNG editor: paint tools, import/threshold/dither,
 * half/quarter resize, and an animation stepper that treats the image as a
 * horizontal strip of equal cells so you can step or play through frames to
 * check alignment. Everything routes through {@link Mono}, so the file written
 * is always a valid 1-bit PNG.
 */
public class BwImageEditor implements Editor {
  private final File file;
  private final Label status;
  private final BorderPane root = new BorderPane();
  private BwCanvas canvas;
  private boolean dirty;

  // animation stepper
  private Spinner<Integer> frames;
  private Spinner<Integer> fps;
  private final ImageView preview = new ImageView();
  private int frameIndex;
  private Timeline player;

  // resize threshold selector
  private final ChoiceBox<String> resizeMode = new ChoiceBox<>();

  // The encoded-size readout (RLE + PNG) is expensive, so it is opt-in and only
  // recomputed on a short debounce, never per painted pixel.
  private final ToggleButton showSize = new ToggleButton("size");
  private final javafx.animation.PauseTransition previewDebounce =
      new javafx.animation.PauseTransition(Duration.millis(120));

  public BwImageEditor(File file, Label status) throws Exception {
    this.file = file;
    this.status = status;
    BufferedImage img = openOrCreate(file);
    this.canvas = new BwCanvas(img);
    // A paint stroke fires onChange once per pixel; mark dirty immediately but
    // coalesce the (heavier) preview/status refresh so a drag stays smooth.
    this.canvas.setOnChange(() -> { markDirty(); schedulePreview(); });
    previewDebounce.setOnFinished(e -> refreshPreview());
    buildUi();
    refreshPreview();
  }

  private void schedulePreview() {
    previewDebounce.playFromStart();
  }

  /** load an existing 1-bit PNG, or prompt for dimensions and start blank. */
  private BufferedImage openOrCreate(File f) throws Exception {
    if (f.exists() && f.length() > 0) {
      return Mono.load(f);
    }
    int[] dim = promptDimensions();
    return Mono.blank(dim[0], dim[1], false);
  }

  private int[] promptDimensions() {
    TextInputDialog d = new TextInputDialog("32x32");
    d.setHeaderText("New image size (width x height)");
    d.setContentText("size:");
    Optional<String> r = d.showAndWait();
    int w = 32;
    int h = 32;
    if (r.isPresent()) {
      String[] parts = r.get().toLowerCase().split("x");
      try {
        w = Math.max(1, Integer.parseInt(parts[0].strip()));
        h = parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1].strip())) : w;
      } catch (Exception ignore) {
        // keep defaults
      }
    }
    return new int[] {w, h};
  }

  // ----------------------------------------------------------------------- UI

  private void buildUi() {
    ToggleGroup tools = new ToggleGroup();
    Button undo = new Button("Undo");
    undo.setDisable(!canvas.canUndo());
    undo.setOnAction(e -> canvas.undo());
    canvas.setOnHistoryChange(() -> undo.setDisable(!canvas.canUndo()));

    ToggleButton draw = toolButton("Pencil", BwCanvas.Tool.DRAW, tools);
    ToggleButton erase = toolButton("Eraser", BwCanvas.Tool.ERASE, tools);
    ToggleButton fill = toolButton("Fill", BwCanvas.Tool.FILL, tools);
    ToggleButton transparent = toolButton("Transparent", BwCanvas.Tool.TRANSPARENT, tools);
    draw.setSelected(true);

    Button zoomIn = new Button("+");
    zoomIn.setOnAction(e -> canvas.setZoom(canvas.zoom() + 1));
    Button zoomOut = new Button("−");
    zoomOut.setOnAction(e -> canvas.setZoom(canvas.zoom() - 1));

    Button invert = new Button("Invert");
    invert.setOnAction(e -> canvas.invert());
    Button clear = new Button("Clear");
    clear.setOnAction(e -> canvas.clear());
    Button importImg = new Button("Import…");
    importImg.setOnAction(e -> importImage());

    resizeMode.getItems().setAll("Ink", "Majority", "Paper");
    resizeMode.setValue("Majority");
    Button half = new Button("½");
    half.setOnAction(e -> resize(false));
    Button quarter = new Button("¼");
    quarter.setOnAction(e -> resize(true));

    ToolBar bar = new ToolBar(undo, sep(), draw, erase, fill, transparent, sep(), zoomOut, zoomIn, sep(),
        invert, clear, importImg, sep(), new Label("reduce:"), resizeMode, half, quarter);

    Button trimWhite = new Button("Trim white");
    trimWhite.setOnAction(e -> canvas.setImage(Mono.trim(canvas.image(), false)));
    Button trimBlack = new Button("Trim black");
    trimBlack.setOnAction(e -> canvas.setImage(Mono.trim(canvas.image(), true)));
    Button canvasSize = new Button("Canvas…");
    canvasSize.setOnAction(e -> canvasResize());
    Button scaleSize = new Button("Resize…");
    scaleSize.setOnAction(e -> scaleResize());
    Button crop = new Button("Crop region…");
    crop.setOnAction(e -> cropRegion());

    ProjectSettings ps = ProjectSettings.current();
    Button toIcon = new Button("→ icon " + ps.iconSize);
    toIcon.setOnAction(e -> canvas.setImage(Mono.scaleTo(canvas.image(), ps.iconSize, ps.iconSize)));
    Button toCell = new Button("→ cell " + ps.animCellW + "×" + ps.animCellH);
    toCell.setOnAction(e -> canvas.setImage(Mono.scaleTo(canvas.image(), ps.animCellW, ps.animCellH)));

    showSize.setOnAction(e -> refreshPreview()); // recompute the readout on demand

    ToolBar bar2 = new ToolBar(trimWhite, trimBlack, sep(), canvasSize, scaleSize, crop,
        sep(), toIcon, toCell, sep(), showSize);

    ScrollPane scroller = new ScrollPane(centered(canvas));
    scroller.setPannable(true);

    root.setTop(new VBox(bar, bar2));
    root.setCenter(scroller);
    root.setBottom(buildStepper());

    // Ctrl/Cmd-Z undo when focus is anywhere in the editor
    root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (e.isShortcutDown() && e.getCode() == KeyCode.Z) {
        canvas.undo();
        e.consume();
      }
    });
  }

  private void canvasResize() {
    int[] wh = promptSize("Resize canvas (width x height) — content centered", canvas.image().getWidth(), canvas.image().getHeight());
    if (wh != null) {
      canvas.setImage(Mono.resizeCanvas(canvas.image(), wh[0], wh[1], true));
    }
  }

  private void scaleResize() {
    int[] wh = promptSize("Scale image to (width x height)", canvas.image().getWidth(), canvas.image().getHeight());
    if (wh != null) {
      canvas.setImage(Mono.scaleTo(canvas.image(), wh[0], wh[1]));
    }
  }

  private void cropRegion() {
    RegionSelectDialog.show(root.getScene() != null ? root.getScene().getWindow() : null, canvas.image())
        .ifPresent(r -> canvas.setImage(Mono.region(canvas.image(), r[0], r[1], r[2], r[3])));
  }

  /** prompt for a "WxH" size; returns {w,h} or null if cancelled/invalid. */
  private int[] promptSize(String header, int defW, int defH) {
    TextInputDialog d = new TextInputDialog(defW + "x" + defH);
    d.setHeaderText(header);
    d.setContentText("size:");
    Optional<String> r = d.showAndWait();
    if (r.isEmpty()) {
      return null;
    }
    String[] parts = r.get().toLowerCase().split("x");
    try {
      int w = Math.max(1, Integer.parseInt(parts[0].strip()));
      int h = parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1].strip())) : w;
      return new int[] {w, h};
    } catch (Exception ex) {
      return null;
    }
  }

  private ToggleButton toolButton(String label, BwCanvas.Tool tool, ToggleGroup g) {
    ToggleButton b = new ToggleButton(label);
    b.setToggleGroup(g);
    b.setOnAction(e -> { if (b.isSelected()) canvas.setTool(tool); });
    return b;
  }

  private Node buildStepper() {
    frames = new Spinner<>(1, 64, 1);
    frames.setEditable(true);
    frames.setPrefWidth(70);
    frames.valueProperty().addListener((o, a, b) -> { frameIndex = 0; refreshPreview(); });

    fps = new Spinner<>(1, 30, 6);
    fps.setPrefWidth(70);
    fps.valueProperty().addListener((o, a, b) -> { if (player != null) startPlayer(); });

    Button prev = new Button("◀");
    prev.setOnAction(e -> step(-1));
    Button next = new Button("▶");
    next.setOnAction(e -> step(1));
    ToggleButton play = new ToggleButton("Play");
    play.setOnAction(e -> {
      if (play.isSelected()) {
        startPlayer();
      } else {
        stopPlayer();
      }
    });

    preview.setSmooth(false);
    preview.setFitHeight(96);
    preview.setPreserveRatio(true);

    HBox controls = new HBox(8, new Label("frames"), frames, new Label("fps"), fps, prev, next, play);
    controls.setAlignment(Pos.CENTER_LEFT);
    controls.setPadding(new Insets(6));

    VBox box = new VBox(4, new Label("Animation preview"), preview, controls);
    box.setPadding(new Insets(8));
    return box;
  }

  // ------------------------------------------------------------------- actions

  private void importImage() {
    FileChooser fc = new FileChooser();
    fc.setTitle("Import image (converted to black & white)");
    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
    File base = file.getParentFile();
    if (base != null && base.isDirectory()) {
      fc.setInitialDirectory(base);
    }
    File picked = fc.showOpenDialog(root.getScene() != null ? root.getScene().getWindow() : null);
    if (picked == null) {
      return;
    }
    try {
      BufferedImage src = ImageIO.read(picked);
      if (src == null) {
        error("Not a readable image: " + picked.getName());
        return;
      }
      ChoiceDialog<Dither.Algo> d = new ChoiceDialog<>(Dither.Algo.FLOYD, Dither.Algo.values());
      d.setHeaderText("Convert to black & white");
      d.setContentText("algorithm:");
      d.showAndWait().ifPresent(algo -> canvas.setImage(Dither.apply(src, algo, 128)));
    } catch (Exception ex) {
      error(ex.getMessage());
    }
  }

  private void resize(boolean quarter) {
    int t = switch (resizeMode.getValue()) {
      case "Ink" -> Mono.PRESERVE_INK;
      case "Paper" -> Mono.PRESERVE_PAPER;
      default -> Mono.MAJORITY;
    };
    BufferedImage src = canvas.image();
    canvas.setImage(quarter ? Mono.reduceQuarter(src, t) : Mono.reduceHalf(src, t));
  }

  // -------------------------------------------------------------- anim stepper

  private int frameCount() {
    return frames == null ? 1 : Math.max(1, frames.getValue());
  }

  private void step(int delta) {
    stopPlayerToggleSafe();
    frameIndex = Math.floorMod(frameIndex + delta, frameCount());
    refreshPreview();
  }

  private void refreshPreview() {
    BufferedImage img = canvas.image();
    int n = frameCount();
    int cellW = Math.max(1, img.getWidth() / n);
    if (frameIndex >= n) {
      frameIndex = 0;
    }
    BufferedImage frame = Mono.region(img, frameIndex * cellW, 0, cellW, img.getHeight());
    preview.setImage(BwCanvas.toFxImage(frame));
    String base = file.getName() + " — " + img.getWidth() + "×" + img.getHeight()
        + " — frame " + (frameIndex + 1) + "/" + n;
    status.setText(showSize.isSelected() ? base + " — " + sizePreview(img) : base);
  }

  /** encoded size in the bank's RLE/detail scheme vs the PNG, so they can be compared. */
  private static String sizePreview(BufferedImage img) {
    int rle = BwCodec.encodedSize(BwCodec.pixelsOf(img));
    long png = -1;
    try {
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      ImageIO.write(img, "png", bo);
      png = bo.size();
    } catch (Exception ignored) {
      // leave png as -1 (unavailable)
    }
    return png < 0
        ? "rle " + rle + " B"
        : "rle " + rle + " B / png " + png + " B";
  }

  private void startPlayer() {
    stopPlayer();
    player = new Timeline(new KeyFrame(Duration.seconds(1.0 / fps.getValue()), e -> {
      frameIndex = (frameIndex + 1) % frameCount();
      refreshPreview();
    }));
    player.setCycleCount(Animation.INDEFINITE);
    player.play();
  }

  private void stopPlayer() {
    if (player != null) {
      player.stop();
      player = null;
    }
  }

  private void stopPlayerToggleSafe() {
    stopPlayer();
  }

  // -------------------------------------------------------------------- helpers

  private static Node sep() {
    return new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);
  }

  private static Node centered(Node n) {
    HBox box = new HBox(n);
    box.setAlignment(Pos.CENTER);
    box.setPadding(new Insets(12));
    return box;
  }

  private void error(String msg) {
    Alert a = new Alert(Alert.AlertType.ERROR, msg == null ? "" : msg, ButtonType.OK);
    a.showAndWait();
  }

  private void markDirty() {
    dirty = true;
  }

  // --------------------------------------------------------------------- Editor

  @Override
  public Node getNode() {
    return root;
  }

  @Override
  public void save() throws Exception {
    Mono.savePng(canvas.image(), file);
    dirty = false;
  }

  @Override
  public boolean isDirty() {
    return dirty;
  }

  @Override
  public String title() {
    return "image";
  }
}
