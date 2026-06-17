package mg.editor;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import mg.assets.Mono;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A zoomable 1-bit paint surface backed by a {@link Mono} {@code BufferedImage}.
 * Left-drag paints with the active tool; the surface stays strictly black/white
 * so it can only ever produce a valid 1-bit image.
 *
 * <p>Rendering draws a cached {@link WritableImage} scaled onto a {@link Canvas}
 * with smoothing off (one {@code drawImage} call), so large images — e.g. a
 * 896&times;1200 Meshy result — render instantly instead of issuing a million
 * per-pixel fills. The initial zoom is chosen to fit a sensible viewport.
 */
public class BwCanvas extends Pane {

  /** the paint tools. */
  public enum Tool { DRAW, ERASE, FILL, TRANSPARENT }

  /** transparent pixels are shown in this colour in the editor. */
  private static final Color TRANSPARENT_VIEW = Color.LIMEGREEN;

  /** target longest-edge pixels for the auto-fit initial zoom. */
  private static final int FIT_TARGET = 512;
  private static final int MAX_ZOOM = 24;

  /** how many prior states the undo buffer keeps. */
  private static final int HISTORY = 5;

  private BufferedImage img;
  private WritableImage fx;
  private int zoom;
  private Tool tool = Tool.DRAW;
  private Runnable onChange;
  private Runnable onHistoryChange;
  private final Canvas canvas = new Canvas();

  // undo: snapshots of prior states, eldest first. A paint stroke is one step:
  // strokeBefore is captured on mouse-press and pushed on the first real change.
  private final Deque<BufferedImage> history = new ArrayDeque<>();
  private BufferedImage strokeBefore;
  private boolean strokeChanged;

  public BwCanvas(BufferedImage img) {
    this.img = img;
    this.zoom = fitZoom(img);
    getChildren().add(canvas);
    canvas.setOnMousePressed(e -> { beginStroke(); apply(e.getX(), e.getY()); });
    canvas.setOnMouseDragged(e -> { if (tool != Tool.FILL) apply(e.getX(), e.getY()); });
    rebuildFx();
    sizeToImage();
    redraw();
  }

  /** an integer zoom that fits the image's longest edge near {@link #FIT_TARGET}. */
  private static int fitZoom(BufferedImage img) {
    int max = Math.max(img.getWidth(), img.getHeight());
    int z = Math.max(1, Math.round((float) FIT_TARGET / max));
    return Math.min(MAX_ZOOM, z);
  }

  // ----------------------------------------------------------------- accessors

  public BufferedImage image() {
    return img;
  }

  public void setImage(BufferedImage next) {
    pushHistory(Mono.copy(img)); // a whole-image transform is one undo step
    this.img = next;
    this.zoom = fitZoom(next);
    rebuildFx();
    sizeToImage();
    redraw();
    fireChanged();
  }

  public void setZoom(int z) {
    this.zoom = Math.max(1, Math.min(MAX_ZOOM, z));
    sizeToImage();
    redraw();
  }

  public int zoom() {
    return zoom;
  }

  public void setTool(Tool t) {
    this.tool = t;
  }

  public void setOnChange(Runnable r) {
    this.onChange = r;
  }

  /** notified whenever the undo depth changes (to refresh an Undo button). */
  public void setOnHistoryChange(Runnable r) {
    this.onHistoryChange = r;
  }

  // ---------------------------------------------------------------------- undo

  public boolean canUndo() {
    return !history.isEmpty();
  }

  public int historyDepth() {
    return history.size();
  }

  /** roll back to the most recent prior state; returns false if none. */
  public boolean undo() {
    if (history.isEmpty()) {
      return false;
    }
    BufferedImage prev = history.removeLast();
    boolean sameDims = prev.getWidth() == img.getWidth() && prev.getHeight() == img.getHeight();
    this.img = prev;
    if (!sameDims) {
      this.zoom = fitZoom(prev); // a resize/crop was undone; refit
    }
    rebuildFx();
    sizeToImage();
    redraw();
    fireChanged();
    fireHistoryChange();
    return true;
  }

  private void beginStroke() {
    strokeBefore = Mono.copy(img);
    strokeChanged = false;
  }

  /** on the first real change of a stroke, commit the pre-stroke snapshot. */
  private void markStrokeChange() {
    if (!strokeChanged) {
      pushHistory(strokeBefore);
      strokeChanged = true;
    }
  }

  private void pushHistory(BufferedImage snapshot) {
    history.addLast(snapshot);
    while (history.size() > HISTORY) {
      history.removeFirst();
    }
    fireHistoryChange();
  }

  private void fireHistoryChange() {
    if (onHistoryChange != null) {
      onHistoryChange.run();
    }
  }

  // -------------------------------------------------------------------- editing

  /** invert every pixel (black<->white); transparency is left untouched. */
  public void invert() {
    pushHistory(Mono.copy(img));
    for (int y = 0; y < img.getHeight(); y++) {
      for (int x = 0; x < img.getWidth(); x++) {
        int s = Mono.state(img, x, y);
        if (s == Mono.BLACK) {
          Mono.set(img, x, y, false);
        } else if (s == Mono.WHITE) {
          Mono.set(img, x, y, true);
        }
      }
    }
    rebuildFx();
    redraw();
    fireChanged();
  }

  /** clear to all white (paper). */
  public void clear() {
    setImage(Mono.blank(img.getWidth(), img.getHeight(), false));
  }

  private void apply(double px, double py) {
    int x = (int) (px / zoom);
    int y = (int) (py / zoom);
    if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
      return;
    }
    switch (tool) {
      case DRAW -> paint(x, y, Mono.BLACK);
      case ERASE -> paint(x, y, Mono.WHITE);
      case TRANSPARENT -> paint(x, y, Mono.TRANSPARENT);
      case FILL -> floodFill(x, y);
    }
  }

  private void paint(int x, int y, int newState) {
    if (Mono.state(img, x, y) == newState) {
      return;
    }
    markStrokeChange();
    Mono.setState(img, x, y, newState);
    fx.getPixelWriter().setColor(x, y, colorFor(newState));
    redraw();
    fireChanged();
  }

  private static Color colorFor(int state) {
    return switch (state) {
      case Mono.BLACK -> Color.BLACK;
      case Mono.TRANSPARENT -> TRANSPARENT_VIEW;
      default -> Color.WHITE;
    };
  }

  /** 4-way flood fill (with black) of the contiguous region matching the click. */
  private void floodFill(int sx, int sy) {
    int target = Mono.state(img, sx, sy);
    int replacement = Mono.BLACK;
    if (target == replacement) {
      return;
    }
    markStrokeChange();
    Deque<int[]> stack = new ArrayDeque<>();
    stack.push(new int[] {sx, sy});
    while (!stack.isEmpty()) {
      int[] p = stack.pop();
      int x = p[0];
      int y = p[1];
      if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
        continue;
      }
      if (Mono.state(img, x, y) != target) {
        continue;
      }
      Mono.setState(img, x, y, replacement);
      stack.push(new int[] {x + 1, y});
      stack.push(new int[] {x - 1, y});
      stack.push(new int[] {x, y + 1});
      stack.push(new int[] {x, y - 1});
    }
    rebuildFx();
    redraw();
    fireChanged();
  }

  private void fireChanged() {
    if (onChange != null) {
      onChange.run();
    }
  }

  // ------------------------------------------------------------------- drawing

  private void rebuildFx() {
    fx = toFxImage(img);
  }

  private void sizeToImage() {
    canvas.setWidth(img.getWidth() * (double) zoom);
    canvas.setHeight(img.getHeight() * (double) zoom);
    setPrefSize(canvas.getWidth(), canvas.getHeight());
  }

  private void redraw() {
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.setImageSmoothing(false);
    g.setFill(Color.WHITE);
    g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    g.drawImage(fx, 0, 0, canvas.getWidth(), canvas.getHeight());
    // a pixel grid is only legible (and cheap) on small images at high zoom
    if (zoom >= 6 && img.getWidth() <= 128 && img.getHeight() <= 128) {
      g.setStroke(Color.color(0.6, 0.6, 0.6, 0.4));
      g.setLineWidth(0.5);
      for (int x = 0; x <= img.getWidth(); x++) {
        g.strokeLine(x * (double) zoom, 0, x * (double) zoom, canvas.getHeight());
      }
      for (int y = 0; y <= img.getHeight(); y++) {
        g.strokeLine(0, y * (double) zoom, canvas.getWidth(), y * (double) zoom);
      }
    }
  }

  // -------------------------------------------------------- preview conversion

  /** convert a 1-bit image to a JavaFX image for previews (no swing module). */
  public static WritableImage toFxImage(BufferedImage bi) {
    int w = bi.getWidth();
    int h = bi.getHeight();
    WritableImage out = new WritableImage(w, h);
    PixelWriter pw = out.getPixelWriter();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        pw.setColor(x, y, colorFor(Mono.state(bi, x, y)));
      }
    }
    return out;
  }
}
