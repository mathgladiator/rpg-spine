package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import mg.editor.dungeon.Dungeon;
import mg.editor.dungeon.Dungeon.Feature;
import mg.editor.dungeon.Dungeon.FeatureType;
import mg.editor.dungeon.Dungeon.Fill;
import mg.editor.dungeon.Dungeon.Kind;
import mg.editor.dungeon.Dungeon.Level;
import mg.editor.dungeon.Dungeon.Material;
import mg.editor.dungeon.Dungeon.MonsterPlacement;
import mg.editor.dungeon.Template;
import mg.editor.dungeon.WallRenderer;
import mg.editor.monster.Monster;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Grid map editor for .dungeon files. Paint micro cells with palette materials
 * (floor = open, wall = occupied); walls are inferred by {@link WallRenderer}'s
 * dual-grid marching squares (diagonals included) and drawn with a purple dotted
 * boundary. Painting supports a brush (shape + size) and a line tool with a live
 * preview, plus stampable {@link Template}s (built-ins + project {@code .template}
 * files) previewed under the cursor. Ladders/holes/portals send the party to a
 * named {@code TARGET} (any macro cell) by id, not coordinates. See
 * {@code documents/DUNGEON_WALLS.md}.
 */
public class DungeonEditor implements Editor {

  private static final int MACRO = Dungeon.MACRO;
  private static final Color ROCK = Color.web("#5a5a5a");
  private static final Color PURPLE = Color.web("#9c27b0");

  private enum Tool { SELECT, PAINT, ERASE, LINE, FEATURE, MONSTER, TEMPLATE }

  private final File file;
  private final Label status;
  private final Dungeon dungeon;
  private final BorderPane root = new BorderPane();
  private final Canvas canvas = new Canvas();
  private boolean dirty;
  private boolean populating;

  private int levelIndex = 0;
  private int cellSize = 16;
  private Tool tool = Tool.SELECT;
  private int selX = -1, selY = -1;

  // brush / line
  private final ComboBox<String> brushShape = new ComboBox<>();
  private final Spinner<Integer> brushSize = new Spinner<>(1, 9, 1);
  private final Spinner<Integer> lineWidth = new Spinner<>(1, 9, 1);

  // line preview
  private boolean lineActive;
  private int lx0, ly0, lx1, ly1;

  // template preview
  private final ComboBox<Template> templatePick = new ComboBox<>();
  private boolean hovering;
  private int hoverX, hoverY;

  // palette
  private final ListView<Material> paletteList = new ListView<>();

  // placement pickers
  private final ComboBox<FeatureType> featurePick = new ComboBox<>();
  private final ComboBox<String> monsterPick = new ComboBox<>();
  private final Map<String, Integer> monsterSizes = new TreeMap<>();

  private final ComboBox<String> levelPick = new ComboBox<>();
  private final VBox propsBox = new VBox(6);

  public DungeonEditor(File file, Label status) throws Exception {
    this.file = file;
    this.status = status;
    this.dungeon = Dungeon.load(file);
    discoverMonsters();
    buildUi();
    redraw();
  }

  // ---------------------------------------------------------------- UI assembly

  private void buildUi() {
    root.setLeft(buildToolPanel());
    root.setCenter(buildCanvasPane());
    root.setRight(buildSidePanel());
  }

  private Node buildToolPanel() {
    VBox box = new VBox(8);
    box.setPadding(new Insets(10));
    box.setPrefWidth(250);

    ToggleGroup tg = new ToggleGroup();
    box.getChildren().add(section("Tools"));
    box.getChildren().add(toolButton(tg, Tool.SELECT, "Select / Inspect"));
    box.getChildren().add(toolButton(tg, Tool.PAINT, "Paint material"));
    box.getChildren().add(toolButton(tg, Tool.ERASE, "Erase → solid"));
    box.getChildren().add(toolButton(tg, Tool.LINE, "Line (drag, preview)"));
    box.getChildren().add(toolButton(tg, Tool.TEMPLATE, "Stamp template"));
    box.getChildren().add(toolButton(tg, Tool.FEATURE, "Place feature (macro)"));
    box.getChildren().add(toolButton(tg, Tool.MONSTER, "Place monster (micro)"));

    brushShape.getItems().setAll("square", "circle", "diamond");
    brushShape.getSelectionModel().selectFirst();
    box.getChildren().add(new HBox(8,
        new VBox(2, new Label("brush"), brushShape),
        new VBox(2, new Label("size"), brushSize),
        new VBox(2, new Label("line w"), lineWidth)));

    box.getChildren().add(section("Palette  (floor = open, wall = solid)"));
    paletteList.getItems().setAll(dungeon.palette);
    paletteList.setPrefHeight(150);
    paletteList.setCellFactory(lv -> new ListCell<>() {
      @Override protected void updateItem(Material m, boolean empty) {
        super.updateItem(m, empty);
        if (empty || m == null) {
          setText(null);
          setGraphic(null);
        } else {
          setText(m.name + "  (" + (m.isWall() ? "wall w" + m.weight : "floor") + ")");
          Region sw = new Region();
          sw.setPrefSize(16, 16);
          sw.setStyle("-fx-background-color: " + m.color + "; -fx-border-color: #333;");
          setGraphic(sw);
        }
      }
    });
    paletteList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> populateMaterialForm(b));
    paletteList.getSelectionModel().select(dungeon.defaultFloorIndex());
    box.getChildren().add(paletteList);
    Button addMat = new Button("Add");
    addMat.setOnAction(e -> addMaterial());
    Button delMat = new Button("Remove");
    delMat.setOnAction(e -> removeMaterial());
    box.getChildren().add(new HBox(6, addMat, delMat));
    box.getChildren().add(buildMaterialForm());

    box.getChildren().add(section("Placement"));
    featurePick.getItems().setAll(FeatureType.values());
    featurePick.getSelectionModel().select(FeatureType.LADDER_DOWN);
    box.getChildren().add(labeled("Feature type", featurePick));
    refreshMonsterPick();
    box.getChildren().add(labeled("Monster", monsterPick));
    discoverTemplates();
    templatePick.setCellFactory(lv -> templateCell());
    templatePick.setButtonCell(templateCell());
    if (!templatePick.getItems().isEmpty()) {
      templatePick.getSelectionModel().selectFirst();
    }
    box.getChildren().add(labeled("Template", templatePick));
    Button rescan = new Button("Rescan project");
    rescan.setOnAction(e -> { discoverMonsters(); refreshMonsterPick(); discoverTemplates(); redraw(); });
    box.getChildren().add(rescan);

    ScrollPane sp = new ScrollPane(box);
    sp.setFitToWidth(true);
    return sp;
  }

  // material editor fields
  private final TextField matName = new TextField();
  private final TextField matColor = new TextField();
  private final ComboBox<Kind> matKind = new ComboBox<>();
  private final Slider matWeight = new Slider(0, 100, 100);

  private Node buildMaterialForm() {
    matKind.getItems().setAll(Kind.values());
    matWeight.setMajorTickUnit(25);
    Runnable commit = () -> {
      Material m = paletteList.getSelectionModel().getSelectedItem();
      if (m == null || populating) {
        return;
      }
      m.name = matName.getText();
      m.color = matColor.getText().isBlank() ? "#808080" : matColor.getText().trim();
      m.kind = matKind.getValue() == null ? Kind.WALL : matKind.getValue();
      m.weight = (int) Math.round(matWeight.getValue());
      paletteList.refresh();
      markDirty();
      redraw();
    };
    matName.textProperty().addListener((o, a, b) -> commit.run());
    matColor.textProperty().addListener((o, a, b) -> commit.run());
    matKind.valueProperty().addListener((o, a, b) -> commit.run());
    matWeight.valueProperty().addListener((o, a, b) -> commit.run());

    GridPane g = new GridPane();
    g.setHgap(6);
    g.setVgap(4);
    g.addRow(0, new Label("name"), matName);
    g.addRow(1, new Label("color"), matColor);
    g.addRow(2, new Label("kind"), matKind);
    g.addRow(3, new Label("weight"), matWeight);
    return new TitledPane("Material", g);
  }

  private void populateMaterialForm(Material m) {
    populating = true;
    if (m != null) {
      matName.setText(m.name);
      matColor.setText(m.color);
      matKind.setValue(m.kind);
      matWeight.setValue(m.weight);
    }
    populating = false;
  }

  private ToggleButton toolButton(ToggleGroup tg, Tool t, String label) {
    ToggleButton b = new ToggleButton(label);
    b.setToggleGroup(tg);
    b.setMaxWidth(Double.MAX_VALUE);
    if (t == Tool.SELECT) {
      b.setSelected(true);
    }
    b.setOnAction(e -> {
      if (b.isSelected()) {
        tool = t;
        lineActive = false;
        redraw();
      } else {
        b.setSelected(true);
      }
    });
    return b;
  }

  private Node buildCanvasPane() {
    canvas.setOnMouseMoved(e -> onMove(e.getX(), e.getY()));
    canvas.setOnMousePressed(e -> onPress(e.getX(), e.getY()));
    canvas.setOnMouseDragged(e -> onDrag(e.getX(), e.getY()));
    canvas.setOnMouseReleased(e -> onRelease());
    canvas.setOnMouseExited(e -> { if (hovering) { hovering = false; redraw(); } });
    ScrollPane sp = new ScrollPane(canvas);

    Slider zoom = new Slider(8, 30, cellSize);
    zoom.setPrefWidth(140);
    zoom.valueProperty().addListener((o, was, now) -> { cellSize = now.intValue(); redraw(); });

    levelPick.setOnAction(e -> {
      int idx = levelPick.getSelectionModel().getSelectedIndex();
      if (idx >= 0 && idx < dungeon.levels.size() && idx != levelIndex) {
        levelIndex = idx;
        selX = selY = -1;
        rebuildProps();
        redraw();
      }
    });
    refreshLevelPick();

    Button addLevel = new Button("+ Level");
    addLevel.setOnAction(e -> addLevel());
    Button delLevel = new Button("Delete level");
    delLevel.setOnAction(e -> deleteLevel());
    Button rename = new Button("Rename…");
    rename.setOnAction(e -> renameLevel());
    Button resize = new Button("Resize…");
    resize.setOnAction(e -> resizeLevel());

    HBox bar = new HBox(8, new Label("Level:"), levelPick, addLevel, rename, resize, delLevel,
        spacer(), new Label("Zoom:"), zoom);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.setPadding(new Insets(8));

    BorderPane pane = new BorderPane(sp);
    pane.setTop(bar);
    return pane;
  }

  private Node buildSidePanel() {
    rebuildProps();
    TitledPane props = new TitledPane("Inspector", new ScrollPane(propsBox) {{ setFitToWidth(true); }});
    props.setCollapsible(false);
    VBox box = new VBox(8, props);
    box.setPadding(new Insets(10));
    box.setPrefWidth(300);
    VBox.setVgrow(props, Priority.ALWAYS);
    return box;
  }

  // ------------------------------------------------------------- project scan

  private void discoverMonsters() {
    monsterSizes.clear();
    File scanRoot = scanRoot();
    if (scanRoot != null) {
      collect(scanRoot, ".monster", f -> {
        try {
          Monster m = Monster.load(f);
          if (m.id != null && !m.id.isBlank()) {
            monsterSizes.put(m.id, Math.max(1, Math.min(5, m.size)));
          }
        } catch (Exception ex) {
          Log.error("could not read monster " + f.getName(), ex);
        }
      });
    }
  }

  private void discoverTemplates() {
    Template prior = templatePick.getValue();
    List<Template> all = new ArrayList<>(Template.builtins());
    File scanRoot = scanRoot();
    if (scanRoot != null) {
      collect(scanRoot, ".template", f -> {
        try {
          all.add(Template.load(f));
        } catch (Exception ex) {
          Log.error("could not read template " + f.getName(), ex);
        }
      });
    }
    templatePick.getItems().setAll(all);
    if (prior != null) {
      for (Template t : all) {
        if (t.name.equals(prior.name)) {
          templatePick.setValue(t);
          return;
        }
      }
    }
    if (!all.isEmpty()) {
      templatePick.getSelectionModel().selectFirst();
    }
  }

  private File scanRoot() {
    File r = ProjectSettings.root();
    return r != null ? r : file.getParentFile();
  }

  private interface FileSink { void accept(File f); }

  private void collect(File dir, String ext, FileSink sink) {
    File[] kids = dir.listFiles();
    if (kids == null) {
      return;
    }
    for (File k : kids) {
      if (k.getName().startsWith(".")) {
        continue;
      }
      if (k.isDirectory()) {
        collect(k, ext, sink);
      } else if (k.getName().toLowerCase().endsWith(ext)) {
        sink.accept(k);
      }
    }
  }

  private void refreshMonsterPick() {
    String prior = monsterPick.getValue();
    monsterPick.getItems().setAll(monsterSizes.keySet());
    if (prior != null && monsterSizes.containsKey(prior)) {
      monsterPick.setValue(prior);
    } else if (!monsterPick.getItems().isEmpty()) {
      monsterPick.getSelectionModel().selectFirst();
    }
  }

  private ListCell<Template> templateCell() {
    return new ListCell<>() {
      @Override protected void updateItem(Template t, boolean empty) {
        super.updateItem(t, empty);
        setText(empty || t == null ? null : t.name + " (" + t.width + "×" + t.height + ")");
      }
    };
  }

  // ------------------------------------------------------------- mouse / paint

  private void onMove(double px, double py) {
    if (tool == Tool.TEMPLATE) {
      hoverX = (int) (px / cellSize);
      hoverY = (int) (py / cellSize);
      hovering = true;
      redraw();
    }
  }

  private void onPress(double px, double py) {
    Level lv = level();
    int x = (int) (px / cellSize), y = (int) (py / cellSize);
    if (!lv.inBounds(x, y)) {
      return;
    }
    switch (tool) {
      case SELECT -> { selX = x; selY = y; rebuildProps(); redraw(); }
      case PAINT -> { brush(lv, x, y, selectedIndex(), brushShape.getValue(), brushSize.getValue()); afterEdit(x, y); }
      case ERASE -> { brush(lv, x, y, dungeon.defaultWallIndex(), brushShape.getValue(), brushSize.getValue()); afterEdit(x, y); }
      case LINE -> { lineActive = true; lx0 = lx1 = x; ly0 = ly1 = y; redraw(); }
      case FEATURE -> { placeFeature(lv, x / MACRO, y / MACRO); afterEdit(x, y); }
      case MONSTER -> { placeMonster(lv, x, y); afterEdit(x, y); }
      case TEMPLATE -> { stampTemplate(lv, x, y); afterEdit(x, y); }
    }
  }

  private void onDrag(double px, double py) {
    Level lv = level();
    int x = (int) (px / cellSize), y = (int) (py / cellSize);
    if (tool == Tool.PAINT && lv.inBounds(x, y)) {
      brush(lv, x, y, selectedIndex(), brushShape.getValue(), brushSize.getValue());
      afterEdit(x, y);
    } else if (tool == Tool.ERASE && lv.inBounds(x, y)) {
      brush(lv, x, y, dungeon.defaultWallIndex(), brushShape.getValue(), brushSize.getValue());
      afterEdit(x, y);
    } else if (tool == Tool.LINE && lineActive) {
      lx1 = clampX(x);
      ly1 = clampY(y);
      redraw();
    }
  }

  private void onRelease() {
    if (tool == Tool.LINE && lineActive) {
      Level lv = level();
      for (int[] p : linePoints(lx0, ly0, lx1, ly1)) {
        brush(lv, p[0], p[1], selectedIndex(), "square", lineWidth.getValue());
      }
      lineActive = false;
      markDirty();
      rebuildProps();
      redraw();
    }
  }

  private void afterEdit(int x, int y) {
    selX = x;
    selY = y;
    markDirty();
    rebuildProps();
    redraw();
  }

  private int selectedIndex() {
    int i = paletteList.getSelectionModel().getSelectedIndex();
    return i < 0 ? dungeon.defaultFloorIndex() : i;
  }

  private int selectedWallIndex() {
    Material m = paletteList.getSelectionModel().getSelectedItem();
    return (m != null && m.isWall()) ? dungeon.palette.indexOf(m) : dungeon.defaultWallIndex();
  }

  private int selectedFloorIndex() {
    Material m = paletteList.getSelectionModel().getSelectedItem();
    return (m != null && !m.isWall()) ? dungeon.palette.indexOf(m) : dungeon.defaultFloorIndex();
  }

  /** stamp a brush of {@code shape}/{@code size} (cells) of {@code idx} centred on (cx,cy). */
  private void brush(Level lv, int cx, int cy, int idx, String shape, int size) {
    int r = size / 2;
    for (int dx = -r; dx <= r; dx++) {
      for (int dy = -r; dy <= r; dy++) {
        if (TemplateEditor.inBrush(dx, dy, r, shape) && lv.inBounds(cx + dx, cy + dy)) {
          lv.cells[cx + dx][cy + dy] = idx;
        }
      }
    }
  }

  private int clampX(int x) {
    return Math.max(0, Math.min(level().width - 1, x));
  }

  private int clampY(int y) {
    return Math.max(0, Math.min(level().height - 1, y));
  }

  /** Bresenham cell line. */
  private static List<int[]> linePoints(int x0, int y0, int x1, int y1) {
    List<int[]> out = new ArrayList<>();
    int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
    int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
    while (true) {
      out.add(new int[] {x0, y0});
      if (x0 == x1 && y0 == y1) {
        break;
      }
      int e2 = 2 * err;
      if (e2 >= dy) { err += dy; x0 += sx; }
      if (e2 <= dx) { err += dx; y0 += sy; }
    }
    return out;
  }

  private void placeFeature(Level lv, int mx, int my) {
    FeatureType type = featurePick.getValue();
    Feature existing = featureAt(lv, mx, my);
    if (existing != null) {
      if (existing.type == type) {
        lv.features.remove(existing);
      } else {
        existing.type = type;
      }
      return;
    }
    Feature f = new Feature();
    f.type = type == null ? FeatureType.LADDER_DOWN : type;
    f.mx = mx;
    f.my = my;
    lv.features.add(f);
  }

  private void placeMonster(Level lv, int x, int y) {
    String id = monsterPick.getValue();
    if (id == null || id.isBlank()) {
      return;
    }
    for (MonsterPlacement mp : lv.monsters) {
      if (mp.x == x && mp.y == y && mp.monsterId.equals(id)) {
        return;
      }
    }
    MonsterPlacement mp = new MonsterPlacement();
    mp.monsterId = id;
    mp.x = x;
    mp.y = y;
    lv.monsters.add(mp);
  }

  private void stampTemplate(Level lv, int cx, int cy) {
    Template t = templatePick.getValue();
    if (t == null) {
      return;
    }
    int ox = cx - t.width / 2, oy = cy - t.height / 2;
    int wIdx = selectedWallIndex(), fIdx = selectedFloorIndex();
    for (int tx = 0; tx < t.width; tx++) {
      for (int ty = 0; ty < t.height; ty++) {
        byte v = t.cells[tx][ty];
        if (v == Template.SKIP) {
          continue;
        }
        int x = ox + tx, y = oy + ty;
        if (lv.inBounds(x, y)) {
          lv.cells[x][y] = (v == Template.WALL) ? wIdx : fIdx;
        }
      }
    }
  }

  private Feature featureAt(Level lv, int mx, int my) {
    for (Feature f : lv.features) {
      if (f.mx == mx && f.my == my) {
        return f;
      }
    }
    return null;
  }

  // ------------------------------------------------------------- inspector

  private void rebuildProps() {
    propsBox.getChildren().clear();
    propsBox.setPadding(new Insets(8));
    Level lv = level();
    if (!lv.inBounds(selX, selY)) {
      propsBox.getChildren().add(new Label("No cell selected.\nUse Select and click a cell."));
      return;
    }
    populating = true;
    int mx = selX / MACRO, my = selY / MACRO;
    propsBox.getChildren().add(new Label("Micro " + selX + "," + selY + "   ·   Macro " + mx + "," + my));

    ComboBox<Material> mat = new ComboBox<>();
    mat.getItems().setAll(dungeon.palette);
    mat.setButtonCell(materialCell());
    mat.setCellFactory(lvw -> materialCell());
    mat.getSelectionModel().select(Math.max(0, Math.min(dungeon.palette.size() - 1, lv.cells[selX][selY])));
    mat.valueProperty().addListener((o, a, b) -> {
      if (!populating && b != null) {
        lv.cells[selX][selY] = dungeon.palette.indexOf(b);
        markDirty();
        redraw();
      }
    });
    propsBox.getChildren().add(labeled("Cell material", mat));

    ComboBox<Fill> fillBox = new ComboBox<>();
    fillBox.getItems().setAll(Fill.values());
    fillBox.setValue(lv.fillAt(mx, my));
    fillBox.valueProperty().addListener((o, a, b) -> {
      if (!populating && b != null && mx < lv.macroW() && my < lv.macroH()) {
        lv.macroFill[mx][my] = b;
        markDirty();
        redraw();
      }
    });
    propsBox.getChildren().add(labeled("Macro fill (" + mx + "," + my + ")", fillBox));

    propsBox.getChildren().add(section("Feature (macro " + mx + "," + my + ")"));
    Feature f = featureAt(lv, mx, my);
    ComboBox<String> ftype = new ComboBox<>();
    ftype.getItems().add("(none)");
    for (FeatureType t : FeatureType.values()) {
      ftype.getItems().add(t.name().toLowerCase());
    }
    ftype.setValue(f == null ? "(none)" : f.type.name().toLowerCase());
    ftype.valueProperty().addListener((o, a, b) -> {
      if (populating) {
        return;
      }
      Feature cur = featureAt(lv, mx, my);
      if ("(none)".equals(b)) {
        if (cur != null) {
          lv.features.remove(cur);
        }
      } else {
        if (cur == null) {
          cur = new Feature();
          cur.mx = mx;
          cur.my = my;
          lv.features.add(cur);
        }
        cur.type = FeatureType.valueOf(b.toUpperCase());
      }
      markDirty();
      rebuildProps();
      redraw();
    });
    propsBox.getChildren().add(labeled("Type", ftype));

    if (f != null) {
      if (f.type == FeatureType.TARGET) {
        TextField id = new TextField(f.id);
        id.setPromptText("destination id, e.g. crypt-entry");
        id.textProperty().addListener((o, a, b) -> { if (!populating) { f.id = b; markDirty(); redraw(); } });
        propsBox.getChildren().add(labeled("Target id", id));
      } else {
        ComboBox<String> dest = new ComboBox<>();
        dest.setEditable(true);
        dest.getItems().setAll(allTargetIds());
        dest.setValue(f.dest);
        dest.valueProperty().addListener((o, a, b) -> { if (!populating) { f.dest = b == null ? "" : b; markDirty(); } });
        propsBox.getChildren().add(labeled("Destination (target id)", dest));
      }
      TextField note = new TextField(f.note);
      note.textProperty().addListener((o, a, b) -> { if (!populating) { f.note = b; markDirty(); } });
      propsBox.getChildren().add(labeled("note", note));
    }

    propsBox.getChildren().add(section("Monsters here"));
    boolean any = false;
    for (MonsterPlacement mp : new ArrayList<>(lv.monsters)) {
      if (mp.x == selX && mp.y == selY) {
        any = true;
        boolean missing = !monsterSizes.containsKey(mp.monsterId);
        Label l = new Label(mp.monsterId + (missing ? "  (missing!)" : "  size " + monsterSizes.get(mp.monsterId)));
        if (missing) {
          l.setStyle("-fx-text-fill: #c62828;");
        }
        Button rm = new Button("Remove");
        rm.setOnAction(e -> { lv.monsters.remove(mp); markDirty(); rebuildProps(); redraw(); });
        HBox row = new HBox(6, l, spacer(), rm);
        row.setAlignment(Pos.CENTER_LEFT);
        propsBox.getChildren().add(row);
      }
    }
    if (!any) {
      propsBox.getChildren().add(new Label("(none — use Place monster)"));
    }
    populating = false;
  }

  private List<String> allTargetIds() {
    Set<String> ids = new LinkedHashSet<>(dungeon.targetIds());
    return new ArrayList<>(ids);
  }

  private ListCell<Material> materialCell() {
    return new ListCell<>() {
      @Override protected void updateItem(Material m, boolean empty) {
        super.updateItem(m, empty);
        setText(empty || m == null ? null : m.name + " (" + (m.isWall() ? "wall" : "floor") + ")");
      }
    };
  }

  // ------------------------------------------------------------------- drawing

  private void redraw() {
    Level lv = level();
    double w = lv.width * (double) cellSize;
    double h = lv.height * (double) cellSize;
    canvas.setWidth(w);
    canvas.setHeight(h);
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.clearRect(0, 0, w, h);

    g.setFill(matColor(dungeon.material(dungeon.defaultFloorIndex())));
    g.fillRect(0, 0, w, h);
    for (int x = 0; x < lv.width; x++) {
      for (int y = 0; y < lv.height; y++) {
        if (!dungeon.isWall(lv.cells[x][y])) {
          g.setFill(matColor(dungeon.material(lv.cells[x][y])));
          g.fillRect(x * cellSize, y * cellSize, cellSize, cellSize);
        }
      }
    }

    WallRenderer.Cells cells = levelCells(lv);
    // fill pass — each macro cell renders its 5×5 region with its own algorithm, clipped
    for (int mx = 0; mx < lv.macroW(); mx++) {
      for (int my = 0; my < lv.macroH(); my++) {
        clipMacro(g, mx, my);
        WallRenderer.fill(g, lv.fillAt(mx, my), cellSize, mx * MACRO, my * MACRO, MACRO, MACRO, cells);
        g.restore();
      }
    }

    drawGrids(g, lv);

    // boundary pass (purple dotted), same per-macro clip
    g.setStroke(PURPLE);
    g.setLineWidth(1.4);
    g.setLineDashes(2, 3);
    for (int mx = 0; mx < lv.macroW(); mx++) {
      for (int my = 0; my < lv.macroH(); my++) {
        clipMacro(g, mx, my);
        WallRenderer.boundary(g, lv.fillAt(mx, my), cellSize, mx * MACRO, my * MACRO, MACRO, MACRO, cells);
        g.restore();
      }
    }
    g.setLineDashes();

    drawFeatures(g, lv);
    drawMonsters(g, lv);
    drawGhost(g, lv);

    if (lv.inBounds(selX, selY)) {
      g.setStroke(Color.DODGERBLUE);
      g.setLineWidth(2);
      g.strokeRect(selX * cellSize + 1, selY * cellSize + 1, cellSize - 2, cellSize - 2);
    }
    status.setText(dungeon.name + " — level " + levelIndex + " (" + lv.name + ") "
        + lv.width + "×" + lv.height + " micro / " + lv.macroW() + "×" + lv.macroH() + " macro"
        + (dirty ? " *" : ""));
  }

  /** save the GC state and clip to macro cell (mx,my); caller must g.restore(). */
  private void clipMacro(GraphicsContext g, int mx, int my) {
    g.save();
    g.beginPath();
    g.rect(mx * MACRO * cellSize, my * MACRO * cellSize, MACRO * (double) cellSize, MACRO * (double) cellSize);
    g.clip();
  }

  private WallRenderer.Cells levelCells(Level lv) {
    return new WallRenderer.Cells() {
      @Override public boolean occupied(int x, int y) {
        return dungeon.occupied(lv, x, y); // out-of-bounds = solid rock
      }
      @Override public Color color(int x, int y) {
        return lv.inBounds(x, y) ? matColor(dungeon.material(lv.cells[x][y])) : ROCK;
      }
      @Override public int weight(int x, int y) {
        if (!lv.inBounds(x, y)) {
          return 100;
        }
        Material m = dungeon.material(lv.cells[x][y]);
        return m == null ? 100 : m.weight;
      }
    };
  }

  private void drawGhost(GraphicsContext g, Level lv) {
    if (tool == Tool.LINE && lineActive) {
      Color c = matColor(paletteList.getSelectionModel().getSelectedItem());
      g.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.5));
      Set<Long> seen = new LinkedHashSet<>();
      for (int[] p : linePoints(lx0, ly0, lx1, ly1)) {
        int r = lineWidth.getValue() / 2;
        for (int dx = -r; dx <= r; dx++) {
          for (int dy = -r; dy <= r; dy++) {
            int x = p[0] + dx, y = p[1] + dy;
            if (lv.inBounds(x, y) && seen.add(((long) x << 20) | y)) {
              g.fillRect(x * cellSize, y * cellSize, cellSize, cellSize);
            }
          }
        }
      }
    } else if (tool == Tool.TEMPLATE && hovering) {
      Template t = templatePick.getValue();
      if (t == null) {
        return;
      }
      int ox = hoverX - t.width / 2, oy = hoverY - t.height / 2;
      Color wc = matColor(dungeon.material(selectedWallIndex()));
      Color fc = matColor(dungeon.material(selectedFloorIndex()));
      for (int tx = 0; tx < t.width; tx++) {
        for (int ty = 0; ty < t.height; ty++) {
          byte v = t.cells[tx][ty];
          if (v == Template.SKIP) {
            continue;
          }
          int x = ox + tx, y = oy + ty;
          if (!lv.inBounds(x, y)) {
            continue;
          }
          Color c = v == Template.WALL ? wc : fc;
          g.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.55));
          g.fillRect(x * cellSize, y * cellSize, cellSize, cellSize);
        }
      }
      g.setStroke(Color.color(0.61, 0.15, 0.69, 0.9));
      g.setLineWidth(1.5);
      g.strokeRect(Math.max(0, ox) * cellSize, Math.max(0, oy) * cellSize,
          t.width * cellSize, t.height * cellSize);
    }
  }

  private void drawGrids(GraphicsContext g, Level lv) {
    double w = lv.width * (double) cellSize, h = lv.height * (double) cellSize;
    g.setLineDashes();
    g.setStroke(Color.rgb(0, 0, 0, 0.12));
    g.setLineWidth(1);
    for (int x = 0; x <= lv.width; x++) {
      g.strokeLine(x * cellSize + 0.5, 0, x * cellSize + 0.5, h);
    }
    for (int y = 0; y <= lv.height; y++) {
      g.strokeLine(0, y * cellSize + 0.5, w, y * cellSize + 0.5);
    }
    g.setStroke(Color.rgb(0, 0, 0, 0.55));
    g.setLineWidth(2.5);
    for (int x = 0; x <= lv.macroW(); x++) {
      g.strokeLine(x * MACRO * cellSize + 0.5, 0, x * MACRO * cellSize + 0.5, h);
    }
    for (int y = 0; y <= lv.macroH(); y++) {
      g.strokeLine(0, y * MACRO * cellSize + 0.5, w, y * MACRO * cellSize + 0.5);
    }
  }

  private void drawFeatures(GraphicsContext g, Level lv) {
    g.setTextAlign(TextAlignment.CENTER);
    g.setFont(Font.font(Math.max(10, MACRO * cellSize * 0.32)));
    for (Feature f : lv.features) {
      double cx = (f.mx * MACRO + MACRO / 2.0) * cellSize;
      double cy = (f.my * MACRO + MACRO / 2.0) * cellSize;
      double rr = cellSize * 1.4;
      g.setFill(Color.rgb(0, 0, 0, 0.55));
      g.fillOval(cx - rr, cy - rr, rr * 2, rr * 2);
      g.setFill(f.type == FeatureType.TARGET ? Color.web("#80deea") : Color.web("#ffd54f"));
      g.fillText(featureGlyph(f.type), cx, cy + cellSize * 0.45);
      if (f.type == FeatureType.TARGET && !f.id.isEmpty()) {
        g.setFont(Font.font(Math.max(8, cellSize * 0.6)));
        g.fillText(f.id, cx, cy + rr + cellSize * 0.6);
        g.setFont(Font.font(Math.max(10, MACRO * cellSize * 0.32)));
      }
    }
  }

  private static String featureGlyph(FeatureType t) {
    return switch (t) {
      case LADDER_UP -> "▲";
      case LADDER_DOWN -> "▼";
      case HOLE -> "◍";
      case PORTAL -> "◈";
      case TARGET -> "⌖";
    };
  }

  private void drawMonsters(GraphicsContext g, Level lv) {
    g.setTextAlign(TextAlignment.CENTER);
    g.setFont(Font.font(Math.max(8, cellSize * 0.7)));
    for (MonsterPlacement mp : lv.monsters) {
      Integer sz = monsterSizes.get(mp.monsterId);
      int size = sz == null ? 1 : sz;
      double box = size * cellSize;
      double px = mp.x * cellSize, py = mp.y * cellSize;
      g.setFill(Color.rgb(229, 57, 53, 0.30));
      g.fillRect(px, py, box, box);
      g.setStroke(sz == null ? Color.web("#c62828") : Color.web("#e53935"));
      g.setLineWidth(2);
      g.strokeRect(px + 1, py + 1, box - 2, box - 2);
      g.setFill(Color.WHITE);
      g.fillText(mp.monsterId.isEmpty() ? "?" : mp.monsterId.substring(0, 1).toUpperCase(),
          px + box / 2, py + box / 2 + cellSize * 0.25);
    }
  }

  private Color matColor(Material m) {
    if (m == null) {
      return ROCK;
    }
    try {
      return Color.web(m.color);
    } catch (Exception e) {
      return ROCK;
    }
  }

  // -------------------------------------------------------------- level ops

  private void addLevel() {
    Level base = level();
    Level lv = new Level("Level " + (dungeon.levels.size() + 1), base.width, base.height, dungeon.defaultWallIndex());
    dungeon.levels.add(lv);
    levelIndex = dungeon.levels.size() - 1;
    selX = selY = -1;
    refreshLevelPick();
    markDirty();
    rebuildProps();
    redraw();
  }

  private void deleteLevel() {
    if (dungeon.levels.size() <= 1) {
      error("A dungeon must keep at least one level.");
      return;
    }
    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
        "Delete level " + levelIndex + " (" + level().name + ")?", ButtonType.YES, ButtonType.NO);
    a.setHeaderText("Delete level");
    a.showAndWait().ifPresent(bt -> {
      if (bt == ButtonType.YES) {
        dungeon.levels.remove(levelIndex);
        levelIndex = Math.max(0, levelIndex - 1);
        selX = selY = -1;
        refreshLevelPick();
        markDirty();
        rebuildProps();
        redraw();
      }
    });
  }

  private void resizeLevel() {
    TextInputDialog d = new TextInputDialog(level().width + "x" + level().height);
    d.setHeaderText("Resize level — micro cells, snapped to multiples of 5 (WxH)");
    d.setContentText("size:");
    d.showAndWait().ifPresent(v -> {
      try {
        String[] parts = v.toLowerCase().split("x");
        int w = Integer.parseInt(parts[0].strip());
        int hh = Integer.parseInt(parts[1].strip());
        if (w > 0 && hh > 0 && w <= 400 && hh <= 400) {
          level().resize(w, hh, dungeon.defaultWallIndex());
          markDirty();
          redraw();
        }
      } catch (Exception ignore) {
        // ignore malformed input
      }
    });
  }

  private void renameLevel() {
    TextInputDialog d = new TextInputDialog(level().name);
    d.setHeaderText("Rename level");
    d.setContentText("name:");
    d.showAndWait().ifPresent(v -> {
      level().name = v;
      refreshLevelPick();
      markDirty();
      redraw();
    });
  }

  private void refreshLevelPick() {
    List<String> labels = new ArrayList<>();
    for (int i = 0; i < dungeon.levels.size(); i++) {
      labels.add(i + ": " + dungeon.levels.get(i).name);
    }
    levelPick.getItems().setAll(labels);
    levelPick.getSelectionModel().select(levelIndex);
  }

  // ------------------------------------------------------------- material ops

  private void addMaterial() {
    Material m = new Material("material", "#8888aa", Kind.WALL, 100);
    dungeon.palette.add(m);
    paletteList.getItems().setAll(dungeon.palette);
    paletteList.getSelectionModel().select(m);
    markDirty();
  }

  private void removeMaterial() {
    if (dungeon.palette.size() <= 1) {
      return;
    }
    int removed = paletteList.getSelectionModel().getSelectedIndex();
    if (removed < 0) {
      return;
    }
    dungeon.palette.remove(removed);
    int fallback = dungeon.defaultWallIndex();
    for (Level lv : dungeon.levels) {
      for (int x = 0; x < lv.width; x++) {
        for (int y = 0; y < lv.height; y++) {
          int v = lv.cells[x][y];
          if (v == removed) {
            lv.cells[x][y] = fallback;
          } else if (v > removed) {
            lv.cells[x][y] = v - 1;
          }
        }
      }
    }
    paletteList.getItems().setAll(dungeon.palette);
    paletteList.getSelectionModel().selectFirst();
    markDirty();
    rebuildProps();
    redraw();
  }

  // ------------------------------------------------------------------- helpers

  private Level level() {
    if (levelIndex < 0 || levelIndex >= dungeon.levels.size()) {
      levelIndex = 0;
    }
    return dungeon.levels.get(levelIndex);
  }

  private void error(String msg) {
    new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK).showAndWait();
  }

  private static Label section(String text) {
    Label l = new Label(text);
    l.setFont(Font.font("System", FontWeight.BOLD, 12));
    return l;
  }

  private static Node labeled(String label, Node control) {
    if (control instanceof Region r) {
      r.setMaxWidth(Double.MAX_VALUE);
    }
    return new VBox(2, new Label(label), control);
  }

  private static Region spacer() {
    Region r = new Region();
    HBox.setHgrow(r, Priority.ALWAYS);
    return r;
  }

  private void markDirty() {
    dirty = true;
  }

  // -------------------------------------------------------------------- Editor

  @Override public Node getNode() {
    return root;
  }

  @Override public void save() throws Exception {
    dungeon.save(file);
    dirty = false;
    redraw();
  }

  @Override public boolean isDirty() {
    return dirty;
  }

  @Override public String title() {
    return "dungeon";
  }
}
