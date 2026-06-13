package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
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
import javafx.scene.text.TextAlignment;
import mg.editor.dungeon.Dungeon;
import mg.editor.dungeon.Dungeon.Cell;
import mg.editor.dungeon.Dungeon.CellState;
import mg.editor.dungeon.Dungeon.Level;
import mg.editor.dungeon.Dungeon.MonsterDef;
import mg.editor.dungeon.Dungeon.MonsterGroup;
import mg.editor.dungeon.Dungeon.Side;
import mg.editor.dungeon.Dungeon.Special;
import mg.editor.dungeon.Dungeon.WallType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A grid map editor for .dungeon files in the spirit of Wizardry / Bard's Tale:
 * paint floor & wall textures, carve the per-edge walls/doors/secret-doors that
 * define corridors, drop special tiles (spinners, teleporters, chutes, fountains,
 * anti-magic zones, darkness), and place fixed/random encounters drawn from a
 * shared bestiary. Multiple stacked levels with stairs/holes connect into a
 * full dungeon.
 */
public class DungeonEditor implements Editor {
  private enum Tool {
    SELECT, FLOOR, ROCK, HOLE, LADDER_UP, LADDER_DOWN, WALLTEX, EDGE, SPECIAL, ENCOUNTER
  }

  private final File file;
  private final Label status;
  private final Dungeon dungeon;
  private final BorderPane root = new BorderPane();
  private final Canvas canvas = new Canvas();
  private boolean dirty;

  private int levelIndex = 0;
  private int cellSize = 30;
  private Tool tool = Tool.SELECT;
  private int selX = -1, selY = -1;
  private boolean populating;

  // palette selections
  private final ComboBox<String> floorPick = new ComboBox<>();
  private final ComboBox<String> wallPick = new ComboBox<>();
  private final ComboBox<Side> sidePick = new ComboBox<>();
  private final ComboBox<WallType> edgePick = new ComboBox<>();
  private final ComboBox<Special> specialPick = new ComboBox<>();
  private final ComboBox<String> encounterPick = new ComboBox<>();
  private final Spinner<Integer> encPctPick = new Spinner<>(0, 100, 50, 5);

  // level controls
  private final ComboBox<String> levelPick = new ComboBox<>();

  // cell properties form
  private final VBox propsBox = new VBox(6);

  // bestiary
  private final ListView<MonsterDef> monsterList = new ListView<>();
  private final ListView<MonsterGroup> groupList = new ListView<>();

  public DungeonEditor(File file, Label status) throws Exception {
    this.file = file;
    this.status = status;
    this.dungeon = Dungeon.load(file);
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
    box.setPrefWidth(220);

    ToggleGroup tg = new ToggleGroup();
    box.getChildren().add(sectionLabel("Tools"));
    box.getChildren().add(toolButton(tg, Tool.SELECT, "Select / Inspect"));
    box.getChildren().add(toolButton(tg, Tool.FLOOR, "Paint Floor"));
    box.getChildren().add(toolButton(tg, Tool.WALLTEX, "Paint Wall Texture"));
    box.getChildren().add(toolButton(tg, Tool.ROCK, "Solid Rock (closed)"));
    box.getChildren().add(toolButton(tg, Tool.HOLE, "Hole / Pit"));
    box.getChildren().add(toolButton(tg, Tool.LADDER_UP, "Ladder Up"));
    box.getChildren().add(toolButton(tg, Tool.LADDER_DOWN, "Ladder Down"));
    box.getChildren().add(toolButton(tg, Tool.EDGE, "Edge (wall/door)"));
    box.getChildren().add(toolButton(tg, Tool.SPECIAL, "Special Tile"));
    box.getChildren().add(toolButton(tg, Tool.ENCOUNTER, "Encounter"));

    box.getChildren().add(new Region() {{ setPrefHeight(6); }});
    box.getChildren().add(sectionLabel("Palettes"));

    floorPick.getItems().setAll(dungeon.floorPalette);
    floorPick.getSelectionModel().selectFirst();
    wallPick.getItems().setAll(dungeon.wallPalette);
    wallPick.getSelectionModel().selectFirst();
    box.getChildren().add(labeled("Floor texture", floorPick));
    box.getChildren().add(labeled("Wall texture", wallPick));

    sidePick.getItems().setAll(Side.values());
    sidePick.getSelectionModel().select(Side.N);
    edgePick.getItems().setAll(WallType.values());
    edgePick.getSelectionModel().select(WallType.WALL);
    box.getChildren().add(labeled("Edge side", sidePick));
    box.getChildren().add(labeled("Edge type", edgePick));

    specialPick.getItems().setAll(Special.values());
    specialPick.getSelectionModel().select(Special.SPINNER);
    box.getChildren().add(labeled("Special", specialPick));

    refreshEncounterPick();
    box.getChildren().add(labeled("Encounter group", encounterPick));
    box.getChildren().add(labeled("Encounter %", encPctPick));

    return new ScrollPane(box) {{
      setFitToWidth(true);
    }};
  }

  private ToggleButton toolButton(ToggleGroup tg, Tool t, String label) {
    ToggleButton b = new ToggleButton(label);
    b.setToggleGroup(tg);
    b.setMaxWidth(Double.MAX_VALUE);
    b.setUserData(t);
    if (t == Tool.SELECT) {
      b.setSelected(true);
    }
    b.setOnAction(e -> {
      if (b.isSelected()) {
        tool = t;
      }
    });
    return b;
  }

  private Node buildCanvasPane() {
    canvas.setOnMousePressed(e -> handlePaint(e.getX(), e.getY()));
    canvas.setOnMouseDragged(e -> {
      if (tool != Tool.SELECT) {
        handlePaint(e.getX(), e.getY());
      }
    });

    ScrollPane sp = new ScrollPane(canvas);
    sp.setPannable(false);

    Slider zoom = new Slider(16, 56, cellSize);
    zoom.setPrefWidth(160);
    zoom.valueProperty().addListener((o, was, now) -> {
      cellSize = now.intValue();
      redraw();
    });

    levelPick.setOnAction(e -> {
      int idx = levelPick.getSelectionModel().getSelectedIndex();
      if (idx >= 0 && idx < dungeon.levels.size()) {
        levelIndex = idx;
        selX = selY = -1;
        rebuildProps();
        redraw();
      }
    });
    refreshLevelPick();

    Button addLevel = new Button("+ Level");
    addLevel.setOnAction(e -> addLevel());
    Button resize = new Button("Resize…");
    resize.setOnAction(e -> resizeLevel());
    Button renameLevel = new Button("Rename…");
    renameLevel.setOnAction(e -> renameLevel());
    CheckBox darkLevel = new CheckBox("dark level");
    darkLevel.setSelected(level().dark);
    darkLevel.setOnAction(e -> {
      level().dark = darkLevel.isSelected();
      markDirty();
      redraw();
    });
    levelPick.getProperties().put("darkBox", darkLevel);

    HBox bar = new HBox(8,
        new Label("Level:"), levelPick, addLevel, renameLevel, resize, darkLevel,
        spacer(), new Label("Zoom:"), zoom);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.setPadding(new Insets(8));

    BorderPane pane = new BorderPane(sp);
    pane.setTop(bar);
    return pane;
  }

  private Node buildSidePanel() {
    rebuildProps();
    TitledPane props = new TitledPane("Cell", new ScrollPane(propsBox) {{ setFitToWidth(true); }});
    props.setCollapsible(false);

    Accordion accordion = new Accordion(buildBestiaryPane(), buildGroupsPane());

    VBox box = new VBox(8, props, accordion);
    box.setPadding(new Insets(10));
    box.setPrefWidth(320);
    VBox.setVgrow(accordion, Priority.ALWAYS);
    return box;
  }

  // ----------------------------------------------------------- bestiary panels

  private TitledPane buildBestiaryPane() {
    monsterList.getItems().setAll(dungeon.monsters);
    monsterList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
      @Override protected void updateItem(MonsterDef m, boolean empty) {
        super.updateItem(m, empty);
        setText(empty || m == null ? null : m.id + " — " + m.name);
      }
    });
    monsterList.setPrefHeight(120);

    GridPane form = new GridPane();
    form.setHgap(6);
    form.setVgap(6);
    TextField id = new TextField();
    TextField name = new TextField();
    Spinner<Integer> hp = new Spinner<>(1, 9999, 8);
    Spinner<Integer> ac = new Spinner<>(-20, 20, 6);
    Spinner<Integer> atk = new Spinner<>(0, 999, 3);
    Spinner<Integer> xp = new Spinner<>(0, 999999, 10);
    int r = 0;
    form.addRow(r++, new Label("id"), id);
    form.addRow(r++, new Label("name"), name);
    form.addRow(r++, new Label("hp"), hp);
    form.addRow(r++, new Label("ac"), ac);
    form.addRow(r++, new Label("atk"), atk);
    form.addRow(r++, new Label("xp"), xp);

    Runnable commit = () -> {
      MonsterDef m = monsterList.getSelectionModel().getSelectedItem();
      if (m == null || populating) return;
      m.id = id.getText();
      m.name = name.getText();
      m.hp = hp.getValue();
      m.ac = ac.getValue();
      m.atk = atk.getValue();
      m.xp = xp.getValue();
      monsterList.refresh();
      refreshEncounterPick();
      markDirty();
    };
    id.textProperty().addListener((o, a, b) -> commit.run());
    name.textProperty().addListener((o, a, b) -> commit.run());
    hp.valueProperty().addListener((o, a, b) -> commit.run());
    ac.valueProperty().addListener((o, a, b) -> commit.run());
    atk.valueProperty().addListener((o, a, b) -> commit.run());
    xp.valueProperty().addListener((o, a, b) -> commit.run());

    monsterList.getSelectionModel().selectedItemProperty().addListener((o, a, m) -> {
      populating = true;
      if (m != null) {
        id.setText(m.id);
        name.setText(m.name);
        hp.getValueFactory().setValue(m.hp);
        ac.getValueFactory().setValue(m.ac);
        atk.getValueFactory().setValue(m.atk);
        xp.getValueFactory().setValue(m.xp);
      }
      populating = false;
    });

    Button add = new Button("Add");
    add.setOnAction(e -> {
      MonsterDef m = new MonsterDef();
      m.id = uniqueId("monster", dungeon.monsters.stream().map(x -> x.id).toList());
      dungeon.monsters.add(m);
      monsterList.getItems().setAll(dungeon.monsters);
      monsterList.getSelectionModel().select(m);
      refreshEncounterPick();
      markDirty();
    });
    Button del = new Button("Remove");
    del.setOnAction(e -> {
      MonsterDef m = monsterList.getSelectionModel().getSelectedItem();
      if (m != null) {
        dungeon.monsters.remove(m);
        monsterList.getItems().setAll(dungeon.monsters);
        refreshEncounterPick();
        markDirty();
      }
    });

    VBox box = new VBox(6, monsterList, new HBox(6, add, del), form);
    box.setPadding(new Insets(6));
    TitledPane pane = new TitledPane("Bestiary", box);
    return pane;
  }

  private TitledPane buildGroupsPane() {
    groupList.getItems().setAll(dungeon.groups);
    groupList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
      @Override protected void updateItem(MonsterGroup g, boolean empty) {
        super.updateItem(g, empty);
        setText(empty || g == null ? null : g.id);
      }
    });
    groupList.setPrefHeight(110);

    TextField id = new TextField();
    TextField members = new TextField();
    members.setPromptText("kobold:1-3,goblin:0-2");

    Runnable commit = () -> {
      MonsterGroup g = groupList.getSelectionModel().getSelectedItem();
      if (g == null || populating) return;
      g.id = id.getText();
      g.membersFromText(members.getText());
      groupList.refresh();
      refreshEncounterPick();
      markDirty();
    };
    id.textProperty().addListener((o, a, b) -> commit.run());
    members.textProperty().addListener((o, a, b) -> commit.run());

    groupList.getSelectionModel().selectedItemProperty().addListener((o, a, g) -> {
      populating = true;
      if (g != null) {
        id.setText(g.id);
        members.setText(g.membersToText());
      }
      populating = false;
    });

    Button add = new Button("Add");
    add.setOnAction(e -> {
      MonsterGroup g = new MonsterGroup();
      g.id = uniqueId("group", dungeon.groups.stream().map(x -> x.id).toList());
      dungeon.groups.add(g);
      groupList.getItems().setAll(dungeon.groups);
      groupList.getSelectionModel().select(g);
      refreshEncounterPick();
      markDirty();
    });
    Button del = new Button("Remove");
    del.setOnAction(e -> {
      MonsterGroup g = groupList.getSelectionModel().getSelectedItem();
      if (g != null) {
        dungeon.groups.remove(g);
        groupList.getItems().setAll(dungeon.groups);
        refreshEncounterPick();
        markDirty();
      }
    });

    GridPane form = new GridPane();
    form.setHgap(6);
    form.setVgap(6);
    form.addRow(0, new Label("id"), id);
    form.addRow(1, new Label("members"), members);

    VBox box = new VBox(6, groupList, new HBox(6, add, del), form);
    box.setPadding(new Insets(6));
    return new TitledPane("Encounter Groups", box);
  }

  // ------------------------------------------------------------- cell painting

  private void handlePaint(double px, double py) {
    int x = (int) (px / cellSize);
    int y = (int) (py / cellSize);
    Cell c = level().at(x, y);
    if (c == null) {
      return;
    }
    switch (tool) {
      case SELECT -> { selX = x; selY = y; rebuildProps(); redraw(); return; }
      case FLOOR -> { c.state = CellState.OPEN; c.floor = floorPick.getValue(); }
      case WALLTEX -> c.wall = wallPick.getValue();
      case ROCK -> c.state = CellState.CLOSED;
      case HOLE -> c.state = CellState.HOLE;
      case LADDER_UP -> c.state = CellState.LADDER_UP;
      case LADDER_DOWN -> c.state = CellState.LADDER_DOWN;
      case EDGE -> c.edge(sidePick.getValue(), edgePick.getValue());
      case SPECIAL -> c.special = specialPick.getValue();
      case ENCOUNTER -> {
        c.encounter = encounterPick.getValue() == null ? "" : encounterPick.getValue();
        c.encounterPct = encPctPick.getValue();
      }
    }
    selX = x;
    selY = y;
    markDirty();
    rebuildProps();
    redraw();
  }

  // ------------------------------------------------------------- properties UI

  private void rebuildProps() {
    propsBox.getChildren().clear();
    propsBox.setPadding(new Insets(8));
    Cell c = level().at(selX, selY);
    if (c == null) {
      propsBox.getChildren().add(new Label("No cell selected.\nUse the Select tool and click a cell."));
      return;
    }
    populating = true;
    propsBox.getChildren().add(new Label("Cell " + selX + "," + selY + "  (level " + levelIndex + ")"));

    ComboBox<CellState> state = new ComboBox<>();
    state.getItems().setAll(CellState.values());
    state.setValue(c.state);
    state.setOnAction(e -> { c.state = state.getValue(); commitCell(); });

    ComboBox<String> floor = new ComboBox<>();
    floor.getItems().setAll(dungeon.floorPalette);
    floor.setValue(c.floor);
    floor.setOnAction(e -> { c.floor = floor.getValue(); commitCell(); });

    ComboBox<String> wall = new ComboBox<>();
    wall.getItems().setAll(dungeon.wallPalette);
    wall.setValue(c.wall);
    wall.setOnAction(e -> { c.wall = wall.getValue(); commitCell(); });

    CheckBox dark = new CheckBox("dark cell");
    dark.setSelected(c.dark);
    dark.setOnAction(e -> { c.dark = dark.isSelected(); commitCell(); });

    propsBox.getChildren().addAll(
        labeled("State", state),
        labeled("Floor", floor),
        labeled("Wall", wall),
        dark);

    // edges
    GridPane edges = new GridPane();
    edges.setHgap(6);
    edges.setVgap(6);
    int r = 0;
    for (Side s : Side.values()) {
      ComboBox<WallType> ec = new ComboBox<>();
      ec.getItems().setAll(WallType.values());
      ec.setValue(c.edge(s));
      ec.setOnAction(e -> { c.edge(s, ec.getValue()); commitCell(); });
      edges.addRow(r++, new Label(s.name()), ec);
    }
    propsBox.getChildren().addAll(sectionLabel("Edges"), edges);

    // special + target
    ComboBox<Special> special = new ComboBox<>();
    special.getItems().setAll(Special.values());
    special.setValue(c.special);
    Spinner<Integer> tl = new Spinner<>(-1, 99, c.targetLevel);
    Spinner<Integer> tx = new Spinner<>(-1, 999, c.targetX);
    Spinner<Integer> ty = new Spinner<>(-1, 999, c.targetY);
    special.setOnAction(e -> { c.special = special.getValue(); commitCell(); });
    tl.valueProperty().addListener((o, a, b) -> { if (!populating) { c.targetLevel = b; commitCell(); } });
    tx.valueProperty().addListener((o, a, b) -> { if (!populating) { c.targetX = b; commitCell(); } });
    ty.valueProperty().addListener((o, a, b) -> { if (!populating) { c.targetY = b; commitCell(); } });
    propsBox.getChildren().addAll(sectionLabel("Special"), labeled("Type", special),
        labeled("Target level", tl), labeled("Target x", tx), labeled("Target y", ty));

    // encounter
    ComboBox<String> enc = new ComboBox<>();
    enc.getItems().add("");
    for (MonsterGroup g : dungeon.groups) {
      enc.getItems().add(g.id);
    }
    enc.setValue(c.encounter);
    Spinner<Integer> pct = new Spinner<>(0, 100, Math.max(0, c.encounterPct), 5);
    enc.setOnAction(e -> { c.encounter = enc.getValue() == null ? "" : enc.getValue(); commitCell(); });
    pct.valueProperty().addListener((o, a, b) -> { if (!populating) { c.encounterPct = b; commitCell(); } });
    propsBox.getChildren().addAll(sectionLabel("Encounter"), labeled("Group", enc), labeled("Chance %", pct));

    // message + note
    TextArea msg = new TextArea(c.message);
    msg.setPrefRowCount(2);
    msg.setWrapText(true);
    msg.textProperty().addListener((o, a, b) -> { if (!populating) { c.message = b; commitCell(); } });
    TextArea note = new TextArea(c.note);
    note.setPrefRowCount(2);
    note.setWrapText(true);
    note.textProperty().addListener((o, a, b) -> { if (!populating) { c.note = b; commitCell(); } });
    propsBox.getChildren().addAll(sectionLabel("Trigger message"), msg, sectionLabel("Designer note"), note);

    populating = false;
  }

  private void commitCell() {
    if (populating) {
      return;
    }
    markDirty();
    redraw();
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

    for (int x = 0; x < lv.width; x++) {
      for (int y = 0; y < lv.height; y++) {
        drawCell(g, lv.cells[x][y], x, y);
      }
    }
    // selection highlight
    if (level().at(selX, selY) != null) {
      g.setStroke(Color.DODGERBLUE);
      g.setLineWidth(3);
      g.strokeRect(selX * cellSize + 1.5, selY * cellSize + 1.5, cellSize - 3, cellSize - 3);
    }
    status.setText(dungeon.name + " — level " + levelIndex + " (" + lv.name + ") "
        + lv.width + "x" + lv.height + (dirty ? " *" : ""));
  }

  private void drawCell(GraphicsContext g, Cell c, int x, int y) {
    double px = x * (double) cellSize;
    double py = y * (double) cellSize;
    double s = cellSize;

    // floor fill by state/texture
    Color floorColor;
    switch (c.state) {
      case CLOSED -> floorColor = Color.web("#2b2b2b");
      case HOLE -> floorColor = Color.web("#15171c");
      case LADDER_UP, LADDER_DOWN -> floorColor = textureColor(c.floor).darker();
      default -> floorColor = textureColor(c.floor);
    }
    g.setFill(floorColor);
    g.fillRect(px, py, s, s);

    // dark overlay
    if (c.dark || level().dark) {
      g.setFill(Color.rgb(0, 0, 0, 0.28));
      g.fillRect(px, py, s, s);
    }

    // grid lines (subtle)
    g.setStroke(Color.rgb(0, 0, 0, 0.18));
    g.setLineWidth(1);
    g.strokeRect(px + 0.5, py + 0.5, s, s);

    // state glyphs
    g.setTextAlign(TextAlignment.CENTER);
    g.setFont(Font.font(Math.max(9, s * 0.42)));
    switch (c.state) {
      case HOLE -> { g.setFill(Color.web("#88aaff")); g.fillText("○", px + s / 2, py + s * 0.66); }
      case LADDER_UP -> { g.setFill(Color.WHITE); g.fillText("▲", px + s / 2, py + s * 0.66); }
      case LADDER_DOWN -> { g.setFill(Color.WHITE); g.fillText("▼", px + s / 2, py + s * 0.66); }
      default -> { }
    }

    // special glyph
    if (c.special != Special.NONE) {
      g.setFill(Color.web("#ffd54f"));
      g.setFont(Font.font(Math.max(8, s * 0.36)));
      g.fillText(specialGlyph(c.special), px + s / 2, py + s * 0.42);
    }

    // encounter marker
    if (!c.encounter.isEmpty()) {
      g.setFill(Color.web("#e53935"));
      g.fillOval(px + s - 8, py + 2, 6, 6);
    }
    // note marker
    if (!c.note.isEmpty() || !c.message.isEmpty()) {
      g.setFill(Color.web("#43a047"));
      g.fillOval(px + 2, py + 2, 5, 5);
    }

    // edges
    Color wallTone = textureColor(c.wall).darker().darker();
    drawEdge(g, c.edge(Side.N), wallTone, px, py, px + s, py);
    drawEdge(g, c.edge(Side.E), wallTone, px + s, py, px + s, py + s);
    drawEdge(g, c.edge(Side.S), wallTone, px, py + s, px + s, py + s);
    drawEdge(g, c.edge(Side.W), wallTone, px, py, px, py + s);
  }

  private void drawEdge(GraphicsContext g, WallType w, Color wallTone, double x1, double y1, double x2, double y2) {
    if (w == WallType.NONE) {
      return;
    }
    g.setLineWidth(4);
    switch (w) {
      case WALL -> { g.setLineDashes(); g.setStroke(wallTone); }
      case DOOR -> { g.setLineDashes(); g.setStroke(Color.web("#8d6e63")); }
      case LOCKED -> { g.setLineDashes(); g.setStroke(Color.web("#c62828")); }
      case SECRET -> { g.setLineDashes(3, 4); g.setStroke(Color.web("#6d4c41")); }
      case GRATE -> { g.setLineDashes(2, 3); g.setStroke(Color.web("#1565c0")); }
      case ARCH -> { g.setLineDashes(6, 5); g.setStroke(Color.web("#9e9e9e")); }
      default -> { return; }
    }
    g.strokeLine(x1, y1, x2, y2);
    g.setLineDashes();
  }

  private String specialGlyph(Special s) {
    return switch (s) {
      case SPINNER -> "↻";
      case TELEPORT -> "✦";
      case ANTIMAGIC -> "Ø";
      case PIT_TRAP -> "⊗";
      case STAIRS -> "≣";
      case ELEVATOR -> "⇕";
      case FOUNTAIN -> "♨";
      case DARK_FORCE -> "✖";
      default -> "";
    };
  }

  // -------------------------------------------------------------- level ops

  private void addLevel() {
    Level base = level();
    Level lv = new Level(base.width, base.height, "Level " + (dungeon.levels.size() + 1));
    dungeon.levels.add(lv);
    levelIndex = dungeon.levels.size() - 1;
    refreshLevelPick();
    levelPick.getSelectionModel().select(levelIndex);
    selX = selY = -1;
    markDirty();
    rebuildProps();
    redraw();
  }

  private void resizeLevel() {
    TextInputDialog d = new TextInputDialog(level().width + "x" + level().height);
    d.setHeaderText("Resize level (WxH)");
    d.setContentText("size:");
    d.showAndWait().ifPresent(v -> {
      try {
        String[] parts = v.toLowerCase().split("x");
        int w = Integer.parseInt(parts[0].strip());
        int h = Integer.parseInt(parts[1].strip());
        if (w > 0 && h > 0 && w <= 256 && h <= 256) {
          level().resize(w, h);
          markDirty();
          redraw();
        }
      } catch (Exception ignore) { }
    });
  }

  private void renameLevel() {
    TextInputDialog d = new TextInputDialog(level().name);
    d.setHeaderText("Rename level");
    d.setContentText("name:");
    d.showAndWait().ifPresent(v -> {
      level().name = v;
      refreshLevelPick();
      levelPick.getSelectionModel().select(levelIndex);
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
    Object darkBox = levelPick.getProperties().get("darkBox");
    if (darkBox instanceof CheckBox cb) {
      cb.setSelected(level().dark);
    }
  }

  private void refreshEncounterPick() {
    String prior = encounterPick.getValue();
    encounterPick.getItems().clear();
    encounterPick.getItems().add("");
    for (MonsterGroup g : dungeon.groups) {
      encounterPick.getItems().add(g.id);
    }
    if (prior != null && encounterPick.getItems().contains(prior)) {
      encounterPick.setValue(prior);
    } else {
      encounterPick.getSelectionModel().selectFirst();
    }
  }

  // ------------------------------------------------------------------- helpers

  private Level level() {
    if (levelIndex < 0 || levelIndex >= dungeon.levels.size()) {
      levelIndex = 0;
    }
    return dungeon.levels.get(levelIndex);
  }

  private static String uniqueId(String base, List<String> taken) {
    if (!taken.contains(base)) {
      return base;
    }
    int i = 2;
    while (taken.contains(base + i)) {
      i++;
    }
    return base + i;
  }

  /** deterministic pastel color for a texture name so maps read consistently. */
  static Color textureColor(String tex) {
    if (tex == null || tex.isEmpty()) {
      return Color.web("#6b6b6b");
    }
    int h = tex.hashCode();
    double hue = (Math.floorMod(h, 360));
    return Color.hsb(hue, 0.35, 0.78);
  }

  private static Label sectionLabel(String text) {
    Label l = new Label(text);
    l.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 12));
    return l;
  }

  private static Node labeled(String label, Node control) {
    if (control instanceof Region r) {
      r.setMaxWidth(Double.MAX_VALUE);
    }
    VBox v = new VBox(2, new Label(label), control);
    return v;
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
