package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import mg.assets.PixelLab;
import mg.assets.Skeleton;
import mg.editor.asset.SkeletonData;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Drives PixelLab's {@code animate-with-skeleton} for a Wizardry-style crawler.
 * Pick a viewer direction (towards/away/left/right → PixelLab facing at eye-level
 * "side"), the frame count and output size, then <b>drag the skeleton joints</b>
 * to pose each frame (scrub between frames). The skeleton is cached in the
 * {@code .monster}, keyed to the reference image, so edits persist. Generating
 * sends the posed frames and saves the results to {@code ref/}.
 */
public final class SkeletonAnimateDialog {
  private static final int BOX = 320;
  private static final double HIT = 10;

  private SkeletonAnimateDialog() {}

  private record DirMap(String pixelLab, String view, boolean profile) {}

  private static DirMap map(String dir) {
    return switch (dir) {
      case "away" -> new DirMap("north", "side", false);
      case "left" -> new DirMap("west", "side", true);
      case "right" -> new DirMap("east", "side", true);
      default -> new DirMap("south", "side", false);
    };
  }

  public static void open(Window owner, File refFile, File baseDir, File refDir, String id,
                          Map<String, SkeletonData> store, Runnable onChange, Consumer<List<File>> onDone) {
    String key = key(baseDir, refFile);

    // working pose state: a list of frames, each [18][2] normalized
    List<double[][]> working = new ArrayList<>();
    String[] dir0 = {"towards"};
    SkeletonData cached = store.get(key);
    if (cached != null && !cached.isEmpty()) {
      dir0[0] = cached.direction;
      for (double[][] f : cached.frames) {
        working.add(copy(f));
      }
    } else {
      seedTemplate(working, 4, map("towards").profile());
    }

    ChoiceBox<String> direction = new ChoiceBox<>();
    direction.getItems().setAll("towards", "away", "left", "right");
    direction.setValue(dir0[0]);
    Spinner<Integer> frames = new Spinner<>(2, 8, working.size());
    Spinner<Integer> size = new Spinner<>(16, 256, 64);
    size.setEditable(true);
    Slider scrub = new Slider(0, Math.max(0, working.size() - 1), 0);
    scrub.setBlockIncrement(1);
    scrub.setMajorTickUnit(1);
    scrub.setMinorTickCount(0);
    scrub.setSnapToTicks(true);

    Image refImg = new Image(refFile.toURI().toString(), false); // native size (sync) for dims
    double refW = refImg.getWidth() > 0 ? refImg.getWidth() : BOX;
    double refH = refImg.getHeight() > 0 ? refImg.getHeight() : BOX;
    ImageView view = new ImageView(refImg);
    view.setFitWidth(BOX);
    view.setFitHeight(BOX);
    view.setPreserveRatio(true);
    Canvas overlay = new Canvas(BOX, BOX);
    Pane stack = new Pane(view, overlay);
    stack.setPrefSize(BOX, BOX);

    int[] frameIndex = {0};
    int[] dragJoint = {-1};

    Runnable redraw = () -> {
      int fi = Math.min(frameIndex[0], working.size() - 1);
      drawSkeleton(overlay, working.get(fi));
    };

    scrub.valueProperty().addListener((o, a, b) -> {
      frameIndex[0] = (int) Math.round(b.doubleValue());
      redraw.run();
    });
    overlay.setOnMousePressed(e -> {
      int fi = frameIndex[0];
      double[][] f = working.get(fi);
      dragJoint[0] = -1;
      double best = HIT;
      for (int j = 0; j < f.length; j++) {
        double d = Math.hypot(e.getX() - f[j][0] * BOX, e.getY() - f[j][1] * BOX);
        if (d <= best) {
          best = d;
          dragJoint[0] = j;
        }
      }
    });
    overlay.setOnMouseDragged(e -> {
      if (dragJoint[0] >= 0) {
        double[][] f = working.get(frameIndex[0]);
        f[dragJoint[0]][0] = clamp(e.getX() / BOX);
        f[dragJoint[0]][1] = clamp(e.getY() / BOX);
        redraw.run();
      }
    });
    overlay.setOnMouseReleased(e -> dragJoint[0] = -1);

    Runnable regen = () -> {
      working.clear();
      seedTemplate(working, frames.getValue(), map(direction.getValue()).profile());
      scrub.setMax(Math.max(0, working.size() - 1));
      frameIndex[0] = 0;
      scrub.setValue(0);
      redraw.run();
    };
    frames.valueProperty().addListener((o, a, b) -> {
      if (b != working.size()) {
        regen.run();
      }
    });

    GridPane controls = new GridPane();
    controls.setHgap(8);
    controls.setVgap(8);
    controls.setPadding(new Insets(8));
    int r = 0;
    controls.addRow(r++, new Label("direction"), direction, new Label("frames"), frames);
    controls.addRow(r++, new Label("output size"), size, new Label("frame"), scrub);
    Button reset = new Button("Reset to template");
    reset.setOnAction(e -> regen.run());
    Button estimate = new Button("Estimate from image");
    estimate.setOnAction(e -> PixelLabGen.estimateSkeleton(owner, refFile, kps -> {
      if (kps.isEmpty()) {
        return;
      }
      // auto-detect coordinate space: PixelLab may return 0..1 normalized or
      // pixel coords. If everything is <= ~1.5 it's already normalized.
      double maxc = 0;
      for (PixelLab.Keypoint kp : kps) {
        maxc = Math.max(maxc, Math.max(kp.x(), kp.y()));
      }
      boolean normalized = maxc <= 1.5;
      double divX = normalized ? 1 : (refW > 1 ? refW : Math.max(1, maxc));
      double divY = normalized ? 1 : (refH > 1 ? refH : Math.max(1, maxc));
      Map<String, double[]> byLabel = new HashMap<>();
      for (PixelLab.Keypoint kp : kps) {
        byLabel.put(kp.label(), new double[] {clamp(kp.x() / divX), clamp(kp.y() / divY)});
      }
      for (double[][] frame : working) {
        for (int j = 0; j < Skeleton.LABELS.length; j++) {
          double[] p = byLabel.get(Skeleton.LABELS[j]);
          if (p != null) {
            frame[j][0] = p[0];
            frame[j][1] = p[1];
          }
        }
      }
      redraw.run();
    }));
    Button walkBtn = new Button("Animate walk");
    walkBtn.setOnAction(e -> {
      double[][][] w = Skeleton.walkFromRest(working.get(0), frames.getValue(), map(direction.getValue()).profile());
      working.clear();
      for (double[][] fr : w) {
        working.add(fr);
      }
      scrub.setMax(Math.max(0, working.size() - 1));
      frameIndex[0] = 0;
      scrub.setValue(0);
      redraw.run();
    });
    controls.add(new HBox(8, reset, estimate, walkBtn), 0, r++, 4, 1);
    Label note = new Label("Flow: ‘Estimate from image’ detects the joints (one rest pose), then\n"
        + "‘Animate walk’ swings the limbs into a walk cycle — no hand-posing needed.\n"
        + "Drag joints to fine-tune; scrub frames. Saved in the .monster per reference.");
    note.setWrapText(true);
    note.setStyle("-fx-opacity: 0.7;");
    controls.add(note, 0, r, 4, 1);

    Stage stage = new Stage();
    stage.initOwner(owner);
    stage.initModality(Modality.WINDOW_MODAL);
    stage.setTitle("Skeleton walk — " + refFile.getName());

    Runnable persist = () -> {
      SkeletonData d = new SkeletonData();
      d.direction = direction.getValue();
      for (double[][] f : working) {
        d.frames.add(copy(f));
      }
      store.put(key, d);
      if (onChange != null) {
        onChange.run();
      }
    };

    Button save = new Button("Save skeleton");
    save.setOnAction(e -> persist.run());
    Button generate = new Button("Generate walk");
    generate.setOnAction(e -> {
      persist.run();
      DirMap m = map(direction.getValue());
      int s = size.getValue();
      String json = Skeleton.toPixelJson(working, s);
      String base = id + "-walk-" + direction.getValue();
      PixelLabGen.animateSkeleton(owner, refFile, s, s, json, m.view(), m.pixelLab(), working.size(), refDir, base, onDone);
      stage.close();
    });
    Button cancel = new Button("Cancel");
    cancel.setOnAction(e -> stage.close());
    HBox bar = new HBox(8, save, generate, cancel);
    bar.setAlignment(Pos.CENTER_RIGHT);
    bar.setPadding(new Insets(8));

    BorderPane center = new BorderPane(stack);
    center.setRight(controls);
    BorderPane rootPane = new BorderPane(center);
    rootPane.setBottom(bar);
    stage.setScene(new Scene(rootPane));
    redraw.run();
    stage.show();
  }

  // ------------------------------------------------------------------- helpers

  private static void seedTemplate(List<double[][]> working, int frames, boolean profile) {
    double[][][] t = Skeleton.templateNormalized(frames, profile);
    for (double[][] f : t) {
      working.add(copy(f));
    }
  }

  private static double[][] copy(double[][] f) {
    double[][] c = new double[f.length][2];
    for (int i = 0; i < f.length; i++) {
      c[i][0] = f[i][0];
      c[i][1] = f[i][1];
    }
    return c;
  }

  private static double clamp(double v) {
    return Math.max(0, Math.min(1, v));
  }

  private static void drawSkeleton(Canvas c, double[][] norm) {
    GraphicsContext g = c.getGraphicsContext2D();
    g.clearRect(0, 0, c.getWidth(), c.getHeight());
    Map<String, double[]> joints = new LinkedHashMap<>();
    for (int j = 0; j < Skeleton.LABELS.length && j < norm.length; j++) {
      joints.put(Skeleton.LABELS[j], new double[] {norm[j][0] * BOX, norm[j][1] * BOX});
    }
    g.setStroke(Color.web("#00e5ff"));
    g.setLineWidth(2);
    for (String[] bone : Skeleton.BONES) {
      double[] a = joints.get(bone[0]);
      double[] b = joints.get(bone[1]);
      if (a != null && b != null) {
        g.strokeLine(a[0], a[1], b[0], b[1]);
      }
    }
    g.setFill(Color.web("#ff5252"));
    for (double[] p : joints.values()) {
      g.fillOval(p[0] - 3, p[1] - 3, 6, 6);
    }
  }

  private static String key(File baseDir, File refFile) {
    try {
      return baseDir.toPath().relativize(refFile.toPath()).toString().replace('\\', '/');
    } catch (Exception e) {
      return refFile.getName();
    }
  }
}
