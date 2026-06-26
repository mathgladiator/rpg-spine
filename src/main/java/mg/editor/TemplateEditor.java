package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import mg.editor.dungeon.Dungeon;
import mg.editor.dungeon.Template;
import mg.editor.dungeon.WallRenderer;

import java.io.File;

/**
 * Editor for {@code .template} files — reusable drawing stamps shared between
 * dungeons. A template is pure occupancy (wall / open / skip), with no floor
 * material or features. It previews exactly how walls will render (via the shared
 * {@link WallRenderer} marching-squares pass) so a stamp looks the same here and
 * when placed in a dungeon. {@code SKIP} cells (rendered as a checker) are left
 * untouched on stamp; {@code OPEN} carves floor; {@code WALL} draws solid.
 */
public class TemplateEditor implements Editor {

  private enum Paint { WALL, OPEN, SKIP }

  private static final Color WALLC = Color.web("#7d7d7d");
  private static final Color OPENC = Color.web("#c2a86a");

  private final File file;
  private final Label status;
  private Template template;
  private final BorderPane root = new BorderPane();
  private final Canvas canvas = new Canvas();
  private boolean dirty;

  private int cellSize = 22;
  private Paint paint = Paint.WALL;
  private final ComboBox<String> brushShape = new ComboBox<>();
  private final Spinner<Integer> brushSize = new Spinner<>(1, 9, 1);

  // brush hover preview
  private boolean hovering;
  private int hoverX, hoverY;

  public TemplateEditor(File file, Label status) throws Exception {
    this.file = file;
    this.status = status;
    this.template = Template.load(file);
    buildUi();
    redraw();
  }

  private void buildUi() {
    VBox tools = new VBox(8);
    tools.setPadding(new Insets(10));
    tools.setPrefWidth(190);
    ToggleGroup tg = new ToggleGroup();
    tools.getChildren().add(label("Paint"));
    tools.getChildren().add(paintButton(tg, Paint.WALL, "Wall"));
    tools.getChildren().add(paintButton(tg, Paint.OPEN, "Open floor"));
    tools.getChildren().add(paintButton(tg, Paint.SKIP, "Skip (erase)"));

    brushShape.getItems().setAll("square", "circle", "diamond");
    brushShape.getSelectionModel().selectFirst();
    tools.getChildren().add(new VBox(2, new Label("brush shape"), brushShape));
    tools.getChildren().add(new VBox(2, new Label("brush size"), brushSize));

    Button resize = new Button("Resize…");
    resize.setOnAction(e -> resize());
    tools.getChildren().add(resize);

    canvas.setOnMousePressed(e -> paintAt(e.getX(), e.getY()));
    canvas.setOnMouseDragged(e -> { hover(e.getX(), e.getY()); paintAt(e.getX(), e.getY()); });
    canvas.setOnMouseMoved(e -> hover(e.getX(), e.getY()));
    canvas.setOnMouseExited(e -> { if (hovering) { hovering = false; redraw(); } });

    root.setLeft(new ScrollPane(tools) {{ setFitToWidth(true); }});
    root.setCenter(new ScrollPane(canvas));
  }

  private ToggleButton paintButton(ToggleGroup tg, Paint p, String text) {
    ToggleButton b = new ToggleButton(text);
    b.setToggleGroup(tg);
    b.setMaxWidth(Double.MAX_VALUE);
    if (p == Paint.WALL) {
      b.setSelected(true);
    }
    b.setOnAction(e -> { if (b.isSelected()) { paint = p; } else { b.setSelected(true); } });
    return b;
  }

  private void hover(double px, double py) {
    hoverX = (int) (px / cellSize);
    hoverY = (int) (py / cellSize);
    hovering = true;
    redraw();
  }

  private void paintAt(double px, double py) {
    int cx = (int) (px / cellSize);
    int cy = (int) (py / cellSize);
    byte v = paint == Paint.WALL ? Template.WALL : (paint == Paint.OPEN ? Template.OPEN : Template.SKIP);
    int r = brushSize.getValue() / 2;
    String shape = brushShape.getValue();
    for (int dx = -r; dx <= r; dx++) {
      for (int dy = -r; dy <= r; dy++) {
        if (!inBrush(dx, dy, r, shape)) {
          continue;
        }
        int x = cx + dx, y = cy + dy;
        if (template.inBounds(x, y)) {
          template.cells[x][y] = v;
        }
      }
    }
    markDirty();
    redraw();
  }

  /** whether offset (dx,dy) is inside a brush of radius {@code r} and the given shape. */
  public static boolean inBrush(int dx, int dy, int r, String shape) {
    if (r <= 0) {
      return dx == 0 && dy == 0;
    }
    return switch (shape == null ? "square" : shape) {
      case "circle" -> dx * dx + dy * dy <= r * r;
      case "diamond" -> Math.abs(dx) + Math.abs(dy) <= r;
      default -> true; // square
    };
  }

  private void resize() {
    TextInputDialog d = new TextInputDialog(template.macroW() + "x" + template.macroH());
    d.setHeaderText("Resize room (WxH, in macro cells — 1 macro = 5×5 micro)");
    d.showAndWait().ifPresent(v -> {
      try {
        String[] p = v.toLowerCase().split("x");
        int mw = Integer.parseInt(p[0].strip());
        int mh = Integer.parseInt(p[1].strip());
        if (mw > 0 && mh > 0 && mw <= 16 && mh <= 16) {
          template.resizeMacro(mw, mh);
          markDirty();
          redraw();
        }
      } catch (Exception ignore) {
        // ignore malformed input
      }
    });
  }

  private void redraw() {
    int w = template.width, h = template.height;
    canvas.setWidth(w * (double) cellSize);
    canvas.setHeight(h * (double) cellSize);
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

    // cells: skip = checker, open = floor
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        byte v = template.cells[x][y];
        double px = x * cellSize, py = y * cellSize;
        if (v == Template.SKIP) {
          g.setFill(((x + y) & 1) == 0 ? Color.web("#e8e8e8") : Color.web("#d4d4d4"));
        } else {
          g.setFill(OPENC);
        }
        g.fillRect(px, py, cellSize, cellSize);
      }
    }
    // walls via the shared marching-squares renderer
    WallRenderer.Cells cells = new WallRenderer.Cells() {
      @Override public boolean occupied(int x, int y) {
        return template.inBounds(x, y) && template.cells[x][y] == Template.WALL;
      }
      @Override public Color color(int x, int y) { return WALLC; }
      @Override public int weight(int x, int y) { return 100; }
    };
    WallRenderer.fill(g, Dungeon.Fill.DIAGONAL, cellSize, 0, 0, w, h, cells);
    g.setStroke(Color.web("#9c27b0"));
    g.setLineWidth(1.4);
    g.setLineDashes(2, 3);
    WallRenderer.boundary(g, Dungeon.Fill.DIAGONAL, cellSize, 0, 0, w, h, cells);
    g.setLineDashes();

    // micro grid (thin) + macro grid (thick)
    g.setStroke(Color.rgb(0, 0, 0, 0.13));
    g.setLineWidth(1);
    for (int x = 0; x <= w; x++) {
      g.strokeLine(x * cellSize + 0.5, 0, x * cellSize + 0.5, h * cellSize);
    }
    for (int y = 0; y <= h; y++) {
      g.strokeLine(0, y * cellSize + 0.5, w * cellSize, y * cellSize + 0.5);
    }
    g.setStroke(Color.rgb(0, 0, 0, 0.55));
    g.setLineWidth(2.5);
    int macro = mg.editor.dungeon.Dungeon.MACRO;
    for (int x = 0; x <= template.macroW(); x++) {
      g.strokeLine(x * macro * cellSize + 0.5, 0, x * macro * cellSize + 0.5, h * cellSize);
    }
    for (int y = 0; y <= template.macroH(); y++) {
      g.strokeLine(0, y * macro * cellSize + 0.5, w * cellSize, y * macro * cellSize + 0.5);
    }
    drawBrushGhost(g);

    status.setText("room " + template.name + " — " + template.macroW() + "×" + template.macroH()
        + " macro (" + w + "×" + h + " micro)" + (dirty ? " *" : ""));
  }

  /** preview the brush footprint under the cursor, tinted by the active paint. */
  private void drawBrushGhost(GraphicsContext g) {
    if (!hovering) {
      return;
    }
    int r = brushSize.getValue() / 2;
    String shape = brushShape.getValue();
    Color tint = switch (paint) {
      case WALL -> WALLC;
      case OPEN -> OPENC;
      case SKIP -> Color.web("#d4d4d4");
    };
    g.setFill(Color.color(tint.getRed(), tint.getGreen(), tint.getBlue(), 0.5));
    g.setStroke(Color.web("#1565c0"));
    g.setLineWidth(1);
    g.setLineDashes();
    for (int dx = -r; dx <= r; dx++) {
      for (int dy = -r; dy <= r; dy++) {
        if (!inBrush(dx, dy, r, shape)) {
          continue;
        }
        int x = hoverX + dx, y = hoverY + dy;
        if (!template.inBounds(x, y)) {
          continue;
        }
        double px = x * cellSize, py = y * cellSize;
        g.fillRect(px, py, cellSize, cellSize);
        g.strokeRect(px + 0.5, py + 0.5, cellSize - 1, cellSize - 1);
      }
    }
  }

  private static Label label(String t) {
    Label l = new Label(t);
    l.setStyle("-fx-font-weight: bold;");
    return l;
  }

  private void markDirty() {
    dirty = true;
  }

  @Override public Node getNode() {
    return root;
  }

  @Override public void save() throws Exception {
    template.save(file);
    dirty = false;
    redraw();
  }

  @Override public boolean isDirty() {
    return dirty;
  }

  @Override public String title() {
    return "template";
  }

  // unused but keeps the spacer helper symmetric with other editors
  private static Region grow() {
    Region r = new Region();
    HBox.setHgrow(r, Priority.ALWAYS);
    return r;
  }
}
