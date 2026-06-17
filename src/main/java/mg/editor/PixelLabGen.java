package mg.editor;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import mg.assets.PixelLab;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

/**
 * Runs PixelLab generation off the UI thread with a small modal progress window
 * and lands the PNG result(s) on disk. PixelLab calls are synchronous, so each
 * action is a single request; the key comes from {@link Settings}.
 */
public final class PixelLabGen {
  private PixelLabGen() {}

  /** text → image reference. */
  public static void pixflux(Window owner, String prompt, File target, int w, int h, Consumer<File> onDone) {
    if (guard()) {
      return;
    }
    run(owner, "Generate (PixelLab)", prompt, () -> {
      write(target, client().pixflux(prompt, w, h, true));
      return target;
    }, onDone);
  }

  /** text + style reference → image reference. */
  public static void bitforge(Window owner, String prompt, File stylePng, File target, int w, int h, Consumer<File> onDone) {
    if (guard()) {
      return;
    }
    run(owner, "Style (PixelLab)", prompt, () -> {
      byte[] style = Files.readAllBytes(stylePng.toPath());
      write(target, client().bitforge(prompt, w, h, style, true));
      return target;
    }, onDone);
  }

  /** rotate a reference to a new facing direction. */
  public static void rotate(Window owner, File fromPng, File target, int w, int h,
                            String fromDir, String toDir, Consumer<File> onDone) {
    if (guard()) {
      return;
    }
    run(owner, "Rotate (PixelLab)", fromDir + " → " + toDir, () -> {
      byte[] from = Files.readAllBytes(fromPng.toPath());
      write(target, client().rotate(from, w, h, fromDir, toDir));
      return target;
    }, onDone);
  }

  /** reference + action → animation frames written as {@code base}-1.png, -2.png, … */
  public static void animate(Window owner, File refPng, String description, String action,
                             int frames, int w, int h, double imageGuidance, File baseDir, String baseName,
                             Consumer<List<File>> onDone) {
    if (guard()) {
      return;
    }
    run(owner, "Animate (PixelLab)", action, () -> {
      byte[] ref = scaledPng(refPng, w, h);
      List<byte[]> imgs = client().animateWithText(ref, description, action, w, h, frames, imageGuidance);
      List<File> out = new ArrayList<>();
      for (int i = 0; i < imgs.size(); i++) {
        File f = new File(baseDir, baseName + "-" + (i + 1) + ".png");
        write(f, imgs.get(i));
        out.add(f);
      }
      return out;
    }, onDone);
  }

  /** reference + skeleton keypoints → animation frames written as {@code base}-1.png, … */
  public static void animateSkeleton(Window owner, File refPng, int w, int h, String keypointsJson,
                                     String view, String direction, int frameCount, File baseDir, String baseName,
                                     Consumer<List<File>> onDone) {
    if (guard()) {
      return;
    }
    run(owner, "Skeleton animate (PixelLab)", direction, () -> {
      byte[] ref = scaledPng(refPng, w, h);
      List<byte[]> imgs = client().animateWithSkeleton(ref, w, h, keypointsJson, view, direction, frameCount);
      List<File> out = new ArrayList<>();
      for (int i = 0; i < imgs.size(); i++) {
        File f = new File(baseDir, baseName + "-" + (i + 1) + ".png");
        write(f, imgs.get(i));
        out.add(f);
      }
      return out;
    }, onDone);
  }

  /** ask PixelLab to detect the skeleton of a reference image. */
  public static void estimateSkeleton(Window owner, File imagePng, Consumer<List<PixelLab.Keypoint>> onDone) {
    if (guard()) {
      return;
    }
    run(owner, "Estimate skeleton (PixelLab)", imagePng.getName(),
        () -> client().estimateSkeleton(Files.readAllBytes(imagePng.toPath())), onDone);
  }

  /** load and rescale a PNG to {@code w}×{@code h} (PixelLab needs the reference
   *  to match the requested image_size). Alpha preserved. */
  private static byte[] scaledPng(File f, int w, int h) throws Exception {
    BufferedImage src = ImageIO.read(f);
    if (src == null) {
      throw new java.io.IOException("not a readable image: " + f);
    }
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(src, 0, 0, w, h, null);
    g.dispose();
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ImageIO.write(out, "png", bos);
    return bos.toByteArray();
  }

  // -------------------------------------------------------------------- runner

  private static <T> void run(Window owner, String title, String subtitle, Callable<T> work, Consumer<T> onDone) {
    Task<T> task = new Task<>() {
      @Override
      protected T call() throws Exception {
        return work.call();
      }
    };
    Stage dialog = new Stage();
    dialog.initOwner(owner);
    dialog.initModality(Modality.WINDOW_MODAL);
    dialog.setTitle(title);
    Label sub = new Label(subtitle);
    sub.setWrapText(true);
    sub.setMaxWidth(360);
    ProgressBar bar = new ProgressBar();
    bar.setPrefWidth(360);
    VBox box = new VBox(10, sub, bar, new Label("Contacting PixelLab…"));
    box.setPadding(new Insets(16));
    dialog.setScene(new Scene(box));

    task.setOnSucceeded(e -> {
      dialog.close();
      if (onDone != null) {
        onDone.accept(task.getValue());
      }
    });
    task.setOnFailed(e -> {
      dialog.close();
      Throwable ex = task.getException();
      Log.error("PixelLab generation failed", ex);
      Alert a = new Alert(Alert.AlertType.ERROR,
          ex != null && ex.getMessage() != null ? ex.getMessage() : "unknown error", ButtonType.OK);
      a.setHeaderText("PixelLab");
      a.showAndWait();
    });
    Thread thread = new Thread(task, "pixellab-gen");
    thread.setDaemon(true);
    thread.start();
    dialog.show();
  }

  private static PixelLab client() {
    return new PixelLab(Settings.pixellabApiKey());
  }

  /** returns true (and warns) when there is no key, to abort the action. */
  private static boolean guard() {
    if (Settings.hasPixellabApiKey()) {
      return false;
    }
    Alert a = new Alert(Alert.AlertType.ERROR,
        "No PixelLab API key set.\nAdd one in Settings ▸ Grok API Key… (the dialog holds both keys).",
        ButtonType.OK);
    a.setHeaderText("PixelLab");
    a.showAndWait();
    return true;
  }

  private static void write(File target, byte[] png) throws Exception {
    File parent = target.getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    Files.write(target.toPath(), png);
  }
}
