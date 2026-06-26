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
import mg.editor.dungeon.Dungeon.Kind;
import mg.editor.dungeon.Dungeon.Level;
import mg.editor.dungeon.Dungeon.Material;
import mg.editor.dungeon.Dungeon.MonsterPlacement;
import mg.editor.monster.Monster;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A grid map editor for .dungeon files built on a simple occupancy model: paint
 * micro cells with palette materials (floor = open, wall = occupied) and the wall
 * surface is <em>inferred</em> by a per-cell marching-squares/metaball pass — each
 * wall cell rounds its convex corners by its material weight (stone sharp, dirt
 * smooth), stitching across the macro grid automatically. The inferred boundary
 * is drawn as a purple dotted line for coordination with the C ray caster (see
 * {@code documents/DUNGEON_WALLS.md}). Player movement is on the macro grid
 * (5&times;5 micro cells); ladders/holes/portals anchor to macro centers and
 * individual monsters (from the project's .monster files) sit on micro cells.
 */
public class DungeonEditor implements Editor {

  private static final int MACRO = Dungeon.MACRO;
  private static final Color ROCK = Color.web("#5a5a5a"); // out-of-bounds / unknown wall
  private static final Color PURPLE = Color.web("#9c27b0");

  private enum Tool { SELECT, PAINT, FEATURE, MONSTER, ERASE }

  private final File file;
  private final Label status;
  private final Dungeon dungeon;
  private final BorderPane root = new BorderPane();
  private final Canvas canvas = new Canvas();
  private boolean dirty;
  private boolean populating;

  private int levelIndex = 0;
  private int cellSize = 16;            // micro-cell pixels
  private Tool tool = Tool.SELECT;
  private int selX = -1, selY = -1;     // selected micro cell

  // palette
  private final ListView<Material> paletteList = new ListView<>();

  // placement pickers
  private final ComboBox<FeatureType> featurePick = new ComboBox<>();
  private final ComboBox<String> monsterPick = new ComboBox<>();

  // available monsters discovered from the project (id -> size)
  private final Map<String, Integer> monsterSizes = new TreeMap<>();

  // level + inspector
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
    box.setPrefWidth(240);

    ToggleGroup tg = new ToggleGroup();
    box.getChildren().add(section("Tools"));
    box.getChildren().add(toolButton(tg, Tool.SELECT, "Select / Inspect"));
    box.getChildren().add(toolButton(tg, Tool.PAINT, "Paint material"));
    box.getChildren().add(toolButton(tg, Tool.ERASE, "Erase → solid"));
    box.getChildren().add(toolButton(tg, Tool.FEATURE, "Place feature (macro)"));
    box.getChildren().add(toolButton(tg, Tool.MONSTER, "Place monster (micro)"));

    box.getChildren().add(section("Palette  (floor = open, wall = solid)"));
    paletteList.getItems().setAll(dungeon.palette);
    paletteList.setPrefHeight(170);
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
    Button rescan = new Button("Rescan monsters");
    rescan.setOnAction(e -> { discoverMonsters(); refreshMonsterPick(); redraw(); });
    box.getChildren().add(labeled("Monster", monsterPick));
    box.getChildren().add(rescan);

    ScrollPane sp = new ScrollPane(box);
    sp.setFitToWidth(true);
    return sp;
  }

  // material editor fields (bound to the selected palette entry)
  private final TextField matName = new TextField();
  private final TextField matColor = new TextField();
  private final ComboBox<Kind> matKind = new ComboBox<>();
  private final Slider matWeight = new Slider(0, 100, 100);

  private Node buildMaterialForm() {
    matKind.getItems().setAll(Kind.values());
    matWeight.setShowTickLabels(false);
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
      } else {
        b.setSelected(true);
      }
    });
    return b;
  }

  private Node buildCanvasPane() {
    canvas.setOnMousePressed(e -> handlePaint(e.getX(), e.getY()));
    canvas.setOnMouseDragged(e -> {
      if (tool == Tool.PAINT || tool == Tool.ERASE) {
        handlePaint(e.getX(), e.getY());
      }
    });
    ScrollPane sp = new ScrollPane(canvas);

    Slider zoom = new Slider(8, 30, cellSize);
    zoom.setPrefWidth(140);
    zoom.valueProperty().addListener((o, was, now) -> {
      cellSize = now.intValue();
      redraw();
    });

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

  // ------------------------------------------------------------- monster scan

  private void discoverMonsters() {
    monsterSizes.clear();
    File scanRoot = ProjectSettings.root();
    if (scanRoot == null) {
      scanRoot = file.getParentFile();
    }
    if (scanRoot != null) {
      collectMonsters(scanRoot);
    }
  }

  private void collectMonsters(File dir) {
    File[] kids = dir.listFiles();
    if (kids == null) {
      return;
    }
    for (File k : kids) {
      if (k.getName().startsWith(".")) {
        continue;
      }
      if (k.isDirectory()) {
        collectMonsters(k);
      } else if (k.getName().toLowerCase().endsWith(".monster")) {
        try {
          Monster m = Monster.load(k);
          if (m.id != null && !m.id.isBlank()) {
            monsterSizes.put(m.id, Math.max(1, Math.min(5, m.size)));
          }
        } catch (Exception ex) {
          Log.error("could not read monster " + k.getName(), ex);
        }
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

  // ------------------------------------------------------------- cell painting

  private void handlePaint(double px, double py) {
    Level lv = level();
    int x = (int) (px / cellSize);
    int y = (int) (py / cellSize);
    if (!lv.inBounds(x, y)) {
      return;
    }
    switch (tool) {
      case SELECT -> { selX = x; selY = y; rebuildProps(); redraw(); return; }
      case PAINT -> {
        int idx = paletteList.getSelectionModel().getSelectedIndex();
        lv.cells[x][y] = idx < 0 ? dungeon.defaultFloorIndex() : idx;
      }
      case ERASE -> lv.cells[x][y] = dungeon.defaultWallIndex();
      case FEATURE -> placeFeature(lv, x / MACRO, y / MACRO);
      case MONSTER -> placeMonster(lv, x, y);
    }
    selX = x;
    selY = y;
    markDirty();
    rebuildProps();
    redraw();
  }

  private void placeFeature(Level lv, int mx, int my) {
    FeatureType type = featurePick.getValue();
    Feature existing = featureAt(lv, mx, my);
    if (existing != null) {
      if (existing.type == type) {
        lv.features.remove(existing); // toggle off
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
        return; // already there
      }
    }
    MonsterPlacement mp = new MonsterPlacement();
    mp.monsterId = id;
    mp.x = x;
    mp.y = y;
    lv.monsters.add(mp);
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

    // cell material
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

    // feature for this macro cell
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
      Spinner<Integer> tl = new Spinner<>(-1, 99, f.targetLevel);
      Spinner<Integer> tmx = new Spinner<>(-1, 999, f.targetMX);
      Spinner<Integer> tmy = new Spinner<>(-1, 999, f.targetMY);
      tl.valueProperty().addListener((o, a, b) -> { if (!populating) { f.targetLevel = b; markDirty(); } });
      tmx.valueProperty().addListener((o, a, b) -> { if (!populating) { f.targetMX = b; markDirty(); } });
      tmy.valueProperty().addListener((o, a, b) -> { if (!populating) { f.targetMY = b; markDirty(); } });
      TextField note = new TextField(f.note);
      note.textProperty().addListener((o, a, b) -> { if (!populating) { f.note = b; markDirty(); } });
      propsBox.getChildren().addAll(
          new Label("target (level / macroX / macroY):"),
          new HBox(6, tl, tmx, tmy), labeled("note", note));
    }

    // monsters on this micro cell
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

    // base + open floors
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
    // smooth wall blobs (rounded by weight)
    for (int x = 0; x < lv.width; x++) {
      for (int y = 0; y < lv.height; y++) {
        if (dungeon.isWall(lv.cells[x][y])) {
          drawWallBlob(g, lv, x, y);
        }
      }
    }
    drawGrids(g, lv);
    // purple dotted inferred boundary
    g.setStroke(PURPLE);
    g.setLineWidth(1.4);
    g.setLineDashes(2, 3);
    for (int x = 0; x < lv.width; x++) {
      for (int y = 0; y < lv.height; y++) {
        if (dungeon.isWall(lv.cells[x][y])) {
          drawBoundary(g, lv, x, y);
        }
      }
    }
    g.setLineDashes();

    drawFeatures(g, lv);
    drawMonsters(g, lv);

    // selection
    if (lv.inBounds(selX, selY)) {
      g.setStroke(Color.DODGERBLUE);
      g.setLineWidth(2);
      g.strokeRect(selX * cellSize + 1, selY * cellSize + 1, cellSize - 2, cellSize - 2);
    }
    status.setText(dungeon.name + " — level " + levelIndex + " (" + lv.name + ") "
        + lv.width + "×" + lv.height + " micro / " + lv.macroW() + "×" + lv.macroH() + " macro"
        + (dirty ? " *" : ""));
  }

  /** convex-corner rounding radius for a wall material's weight (sharp→0, smooth→s/2). */
  private double radius(int matIdx) {
    Material m = dungeon.material(matIdx);
    int weight = m == null ? 100 : m.weight;
    return (1.0 - weight / 100.0) * (cellSize * 0.5);
  }

  /**
   * Fill one wall micro cell as a rounded blob: each corner is rounded only when
   * both of its edge-neighbours are open (a convex outer corner), by the cell's
   * weight radius. Uses only the 4 edge neighbours, so it is computable in
   * isolation and stitches seamlessly across macro boundaries.
   */
  private void drawWallBlob(GraphicsContext g, Level lv, int x, int y) {
    double px = x * cellSize, py = y * cellSize, s = cellSize;
    double r = Math.min(radius(lv.cells[x][y]), s / 2);
    boolean openN = !dungeon.occupied(lv, x, y - 1);
    boolean openS = !dungeon.occupied(lv, x, y + 1);
    boolean openE = !dungeon.occupied(lv, x + 1, y);
    boolean openW = !dungeon.occupied(lv, x - 1, y);
    boolean cTL = openN && openW;
    boolean cTR = openN && openE;
    boolean cBR = openS && openE;
    boolean cBL = openS && openW;

    g.setFill(matColor(dungeon.material(lv.cells[x][y])));
    g.beginPath();
    g.moveTo(px + (cTL ? r : 0), py);
    // top edge → TR
    if (cTR) { g.lineTo(px + s - r, py); g.quadraticCurveTo(px + s, py, px + s, py + r); }
    else { g.lineTo(px + s, py); }
    // right edge → BR
    if (cBR) { g.lineTo(px + s, py + s - r); g.quadraticCurveTo(px + s, py + s, px + s - r, py + s); }
    else { g.lineTo(px + s, py + s); }
    // bottom edge → BL
    if (cBL) { g.lineTo(px + r, py + s); g.quadraticCurveTo(px, py + s, px, py + s - r); }
    else { g.lineTo(px, py + s); }
    // left edge → TL
    if (cTL) { g.lineTo(px, py + r); g.quadraticCurveTo(px, py, px + (r), py); }
    else { g.lineTo(px, py); }
    g.closePath();
    g.fill();
  }

  /** draw the purple boundary for a wall cell: every side facing an open cell, with rounded convex corners. */
  private void drawBoundary(GraphicsContext g, Level lv, int x, int y) {
    double px = x * cellSize, py = y * cellSize, s = cellSize;
    double r = Math.min(radius(lv.cells[x][y]), s / 2);
    boolean openN = !dungeon.occupied(lv, x, y - 1);
    boolean openS = !dungeon.occupied(lv, x, y + 1);
    boolean openE = !dungeon.occupied(lv, x + 1, y);
    boolean openW = !dungeon.occupied(lv, x - 1, y);
    boolean cTL = openN && openW, cTR = openN && openE, cBR = openS && openE, cBL = openS && openW;
    if (openN) {
      line(g, px + (cTL ? r : 0), py, px + s - (cTR ? r : 0), py);
    }
    if (openE) {
      line(g, px + s, py + (cTR ? r : 0), px + s, py + s - (cBR ? r : 0));
    }
    if (openS) {
      line(g, px + (cBL ? r : 0), py + s, px + s - (cBR ? r : 0), py + s);
    }
    if (openW) {
      line(g, px, py + (cTL ? r : 0), px, py + s - (cBL ? r : 0));
    }
    if (cTL) { arc(g, px, py + r, px, py, px + r, py); }
    if (cTR) { arc(g, px + s - r, py, px + s, py, px + s, py + r); }
    if (cBR) { arc(g, px + s, py + s - r, px + s, py + s, px + s - r, py + s); }
    if (cBL) { arc(g, px + r, py + s, px, py + s, px, py + s - r); }
  }

  private static void line(GraphicsContext g, double x1, double y1, double x2, double y2) {
    g.strokeLine(x1, y1, x2, y2);
  }

  private static void arc(GraphicsContext g, double x0, double y0, double cx, double cy, double x1, double y1) {
    g.beginPath();
    g.moveTo(x0, y0);
    g.quadraticCurveTo(cx, cy, x1, y1);
    g.stroke();
  }

  private void drawGrids(GraphicsContext g, Level lv) {
    double w = lv.width * (double) cellSize, h = lv.height * (double) cellSize;
    g.setLineDashes();
    // micro grid — thin
    g.setStroke(Color.rgb(0, 0, 0, 0.12));
    g.setLineWidth(1);
    for (int x = 0; x <= lv.width; x++) {
      g.strokeLine(x * cellSize + 0.5, 0, x * cellSize + 0.5, h);
    }
    for (int y = 0; y <= lv.height; y++) {
      g.strokeLine(0, y * cellSize + 0.5, w, y * cellSize + 0.5);
    }
    // macro grid — thick
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
    g.setFont(Font.font(Math.max(10, MACRO * cellSize * 0.35)));
    for (Feature f : lv.features) {
      double cx = (f.mx * MACRO + MACRO / 2.0) * cellSize;
      double cy = (f.my * MACRO + MACRO / 2.0) * cellSize;
      double rr = cellSize * 1.4;
      g.setFill(Color.rgb(0, 0, 0, 0.55));
      g.fillOval(cx - rr, cy - rr, rr * 2, rr * 2);
      g.setFill(Color.web("#ffd54f"));
      g.fillText(featureGlyph(f.type), cx, cy + cellSize * 0.5);
    }
  }

  private static String featureGlyph(FeatureType t) {
    return switch (t) {
      case LADDER_UP -> "▲";
      case LADDER_DOWN -> "▼";
      case HOLE -> "◍";
      case PORTAL -> "◈";
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
        int h = Integer.parseInt(parts[1].strip());
        if (w > 0 && h > 0 && w <= 400 && h <= 400) {
          level().resize(w, h, dungeon.defaultWallIndex());
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
    // keep painted cells consistent: shift higher indices down, drop references to the removed one
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

  @Override
  public Node getNode() {
    return root;
  }

  @Override
  public void save() throws Exception {
    dungeon.save(file);
    dirty = false;
    redraw();
  }

  @Override
  public boolean isDirty() {
    return dirty;
  }

  @Override
  public String title() {
    return "dungeon";
  }
}
