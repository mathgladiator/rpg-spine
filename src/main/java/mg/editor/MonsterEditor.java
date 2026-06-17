package mg.editor;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;
import mg.editor.monster.Monster;
import mg.editor.monster.Monster.LevelRow;
import mg.editor.monster.Monster.Skill;

import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Editor for .monster files. Art is assembled from explicit image files —
 * references, single images (battle stance / damaged stance), and animations
 * (battle attack; dungeon idle/walk in four orientations) — with no in-place
 * editing or generation on the frames themselves: you select images and build
 * sequences. Numeric design lives in the stats and skills tables.
 */
public class MonsterEditor implements Editor {
  private final File file;
  private final Label status;
  private final Monster monster;
  private final BorderPane root = new BorderPane();
  private boolean dirty;

  private final TableView<LevelRow> levelTable = new TableView<>();
  private final ObservableList<LevelRow> levelRows = FXCollections.observableArrayList();
  private final TableView<Skill> skillTable = new TableView<>();
  private final ObservableList<Skill> skillRows = FXCollections.observableArrayList();

  public MonsterEditor(File file, Label status) throws Exception {
    this.file = file;
    this.status = status;
    this.monster = Monster.load(file);
    buildUi();
  }

  private void buildUi() {
    root.setTop(buildIdentity());
    TabPane tabs = new TabPane(
        new Tab("Art", buildArt()),
        new Tab("Stats & skills", buildTables()));
    tabs.getTabs().forEach(t -> t.setClosable(false));
    root.setCenter(tabs);
  }

  // ------------------------------------------------------------------ identity

  private Node buildIdentity() {
    GridPane id = new GridPane();
    id.setHgap(8);
    id.setVgap(6);
    id.setPadding(new Insets(10));
    TextField idField = new TextField(monster.id);
    idField.textProperty().addListener((o, a, b) -> { monster.id = b; markDirty(); });
    TextField nameField = new TextField(monster.name);
    nameField.textProperty().addListener((o, a, b) -> { monster.name = b; markDirty(); status(); });
    TextField familyField = new TextField(monster.family);
    familyField.textProperty().addListener((o, a, b) -> { monster.family = b; markDirty(); });
    id.addRow(0, new Label("id"), idField, new Label("name"), nameField, new Label("family"), familyField);
    GridPane.setHgrow(nameField, Priority.ALWAYS);
    return new VBox(4, section("Monster"), id);
  }

  // ----------------------------------------------------------------------- art

  private Node buildArt() {
    ReferencePanel refs = new ReferencePanel(
        monster.references, baseDir(), monster.id,
        ProjectSettings.current().animCellW, monster.skeletons, monster.extract, this::markDirty);

    VBox battle = new VBox(6,
        imageRow("stance", () -> monster.battleStance, v -> monster.battleStance = v),
        imageRow("damage", () -> monster.battleDamage, v -> monster.battleDamage = v),
        new AnimationPanel("attack (animation)", monster.battleAttack, baseDir(), this::markDirty, false));
    battle.setPadding(new Insets(6));

    VBox content = new VBox(8,
        new TitledPane("References", refs),
        new TitledPane("Battle art (2D)", battle),
        new TitledPane("Dungeon art (first-person)", buildDungeon()));
    content.setPadding(new Insets(8));
    ScrollPane scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    return scroll;
  }

  private Node buildDungeon() {
    TabPane orient = new TabPane(
        new Tab("idle", orientationPanels(monster.dungeonIdle)),
        new Tab("walk", orientationPanels(monster.dungeonWalk)));
    orient.getTabs().forEach(t -> t.setClosable(false));
    return orient;
  }

  private Node orientationPanels(java.util.Map<String, mg.editor.asset.Animation> set) {
    VBox box = new VBox(6);
    for (String o : Monster.ORIENTATIONS) {
      box.getChildren().add(new TitledPane(o,
          new AnimationPanel(o, set.get(o), baseDir(), this::markDirty, false)));
    }
    return box;
  }

  /** a select-only single-image slot: path field + thumbnail + Select/Clear. */
  private Node imageRow(String label, Supplier<String> get, Consumer<String> set) {
    Label l = new Label(label);
    l.setMinWidth(60);
    TextField tf = new TextField(get.get());
    tf.setEditable(false);
    tf.setPrefWidth(240);
    ImageView thumb = new ImageView();
    thumb.setFitWidth(40);
    thumb.setFitHeight(40);
    thumb.setPreserveRatio(true);
    updateThumb(thumb, get.get());
    markMissing(tf, get.get());
    Button select = new Button("Select…");
    select.setOnAction(e -> ImagePicker.pick(window(), baseDir(), "Select " + label).ifPresent(f -> {
      String rel = relativize(f);
      tf.setText(rel);
      set.accept(rel);
      updateThumb(thumb, rel);
      markMissing(tf, rel);
      markDirty();
    }));
    Button clear = new Button("Clear");
    clear.setOnAction(e -> {
      tf.setText("");
      set.accept("");
      updateThumb(thumb, "");
      markMissing(tf, "");
      markDirty();
    });
    HBox row = new HBox(6, l, tf, thumb, select, clear);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  /** red text when a non-blank path points at a missing file (a lost image). */
  private void markMissing(TextField tf, String rel) {
    boolean missing = rel != null && !rel.isBlank() && !resolve(rel).isFile();
    tf.setStyle(missing ? "-fx-text-fill: #c62828;" : "");
  }

  private void updateThumb(ImageView thumb, String rel) {
    if (rel == null || rel.isBlank()) {
      thumb.setImage(null);
      return;
    }
    File f = resolve(rel);
    thumb.setImage(f.isFile() ? new Image(f.toURI().toString(), 40, 40, true, true) : null);
  }

  // ------------------------------------------------------------------- tables

  private Node buildTables() {
    SplitPane split = new SplitPane(buildLevelPane(), buildSkillPane());
    split.setOrientation(javafx.geometry.Orientation.VERTICAL);
    split.setDividerPositions(0.55);
    return split;
  }

  private Node buildLevelPane() {
    levelTable.setEditable(true);
    levelRows.setAll(monster.levels);
    levelTable.setItems(levelRows);
    rebuildLevelColumns();

    Button addLevel = new Button("Add Level");
    addLevel.setOnAction(e -> { LevelRow r = monster.addLevel(); levelRows.add(r); markDirty(); });
    Button delLevel = new Button("Remove Level");
    delLevel.setOnAction(e -> {
      LevelRow sel = levelTable.getSelectionModel().getSelectedItem();
      if (sel != null) { monster.levels.remove(sel); levelRows.remove(sel); markDirty(); }
    });
    Button addStat = new Button("Add Stat…");
    addStat.setOnAction(e -> {
      TextInputDialog d = new TextInputDialog("LUCK");
      d.setHeaderText("New stat column");
      d.setContentText("name:");
      d.showAndWait().ifPresent(name -> {
        String col = name.strip();
        if (!col.isEmpty()) { monster.addStatColumn(col); rebuildLevelColumns(); markDirty(); }
      });
    });
    Button delStat = new Button("Remove Stat…");
    delStat.setOnAction(e -> {
      if (monster.statColumns.isEmpty()) return;
      ChoiceDialog<String> d = new ChoiceDialog<>(monster.statColumns.get(0), monster.statColumns);
      d.setHeaderText("Remove which stat column?");
      d.setContentText("column:");
      d.showAndWait().ifPresent(col -> { monster.removeStatColumn(col); rebuildLevelColumns(); markDirty(); });
    });

    HBox bar = new HBox(8, addLevel, delLevel, gap(), addStat, delStat);
    bar.setPadding(new Insets(6));
    BorderPane pane = new BorderPane(levelTable);
    pane.setTop(new VBox(section("Levels & stats"), bar));
    return pane;
  }

  private void rebuildLevelColumns() {
    levelTable.getColumns().clear();
    levelTable.getColumns().add(col("Lv", 60,
        r -> String.valueOf(r.level), (r, v) -> r.level = parseInt(v, r.level)));
    for (String c : monster.statColumns) {
      final String col = c;
      levelTable.getColumns().add(col(col, 80,
          r -> String.valueOf(r.get(col)), (r, v) -> r.stats.put(col, parseInt(v, 0))));
    }
  }

  private Node buildSkillPane() {
    skillTable.setEditable(true);
    skillRows.setAll(monster.skills);
    skillTable.setItems(skillRows);
    skillTable.getColumns().add(col("Name", 180, s -> s.name, (s, v) -> s.name = v));
    skillTable.getColumns().add(col("Learn@", 70, s -> String.valueOf(s.learnLevel), (s, v) -> s.learnLevel = parseInt(v, 1)));
    skillTable.getColumns().add(col("MP", 60, s -> String.valueOf(s.mp), (s, v) -> s.mp = parseInt(v, 0)));
    skillTable.getColumns().add(col("Power", 70, s -> String.valueOf(s.power), (s, v) -> s.power = parseInt(v, 0)));
    skillTable.getColumns().add(col("Type", 120, s -> s.type, (s, v) -> s.type = v));

    Button add = new Button("Add Skill");
    add.setOnAction(e -> { Skill s = new Skill(); monster.skills.add(s); skillRows.add(s); markDirty(); });
    Button del = new Button("Remove Skill");
    del.setOnAction(e -> {
      Skill sel = skillTable.getSelectionModel().getSelectedItem();
      if (sel != null) { monster.skills.remove(sel); skillRows.remove(sel); markDirty(); }
    });
    HBox bar = new HBox(8, add, del);
    bar.setPadding(new Insets(6));
    BorderPane pane = new BorderPane(skillTable);
    pane.setTop(new VBox(section("Skills"), bar));
    return pane;
  }

  private <T> TableColumn<T, String> col(String title, double width, Function<T, String> get, BiConsumer<T, String> set) {
    TableColumn<T, String> c = new TableColumn<>(title);
    c.setPrefWidth(width);
    c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(get.apply(cd.getValue())));
    c.setCellFactory(TextFieldTableCell.forTableColumn());
    c.setOnEditCommit(ev -> { set.accept(ev.getRowValue(), ev.getNewValue()); markDirty(); ev.getTableView().refresh(); });
    c.setEditable(true);
    return c;
  }

  // ------------------------------------------------------------------- helpers

  private File baseDir() {
    return file.getParentFile();
  }

  private File resolve(String rel) {
    File f = new File(rel);
    return f.isAbsolute() ? f : new File(baseDir(), rel);
  }

  private String relativize(File picked) {
    File base = baseDir();
    if (base != null) {
      try {
        return base.toPath().relativize(picked.toPath()).toString().replace('\\', '/');
      } catch (Exception ignore) {
        // different roots
      }
    }
    return picked.getAbsolutePath();
  }

  private Window window() {
    return root.getScene() != null ? root.getScene().getWindow() : null;
  }

  private static int parseInt(String s, int dflt) {
    try { return Integer.parseInt(s.strip()); } catch (Exception e) { return dflt; }
  }

  private static Label section(String text) {
    Label l = new Label(text);
    l.setFont(Font.font("System", FontWeight.BOLD, 12));
    l.setPadding(new Insets(6, 8, 2, 8));
    return l;
  }

  private static Region gap() {
    Region r = new Region();
    r.setMinWidth(16);
    return r;
  }

  private void status() {
    status.setText("monster — " + monster.name);
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
    monster.save(file);
    dirty = false;
  }

  @Override
  public boolean isDirty() {
    return dirty;
  }

  @Override
  public String title() {
    return "monster";
  }
}
