package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;
import mg.editor.item.Item;
import mg.editor.item.Item.Param;
import mg.editor.item.Item.ParamKind;
import mg.editor.item.Item.Type;

import java.io.File;

/**
 * Editor for .item files. Common fields plus a type-driven properties section.
 * Visuals are explicit selections: three icon slots (one per project icon size)
 * and a usage animation (frame list + speed + loop). No generation, editing, or
 * resizing — images are selected from the project with {@link ImagePicker}.
 */
public class ItemEditor implements Editor {
  private final File file;
  private final Label status;
  private final Item item;
  private final ScrollPane root = new ScrollPane();
  private final VBox typeParams = new VBox(6);
  private boolean dirty;

  public ItemEditor(File file, Label status) throws Exception {
    this.file = file;
    this.status = status;
    this.item = Item.load(file);
    buildUi();
  }

  private void buildUi() {
    GridPane g = new GridPane();
    g.setHgap(8);
    g.setVgap(8);
    g.setPadding(new Insets(12));

    TextField idField = new TextField(item.id);
    idField.textProperty().addListener((o, a, b) -> { item.id = b; markDirty(); });
    TextField nameField = new TextField(item.name);
    nameField.textProperty().addListener((o, a, b) -> { item.name = b; markDirty(); status(); });

    ComboBox<Type> typeBox = new ComboBox<>();
    typeBox.getItems().setAll(Type.values());
    typeBox.setValue(item.type);
    typeBox.setOnAction(e -> {
      item.type = typeBox.getValue();
      item.conformParamsToType();
      rebuildTypeParams();
      markDirty();
    });

    Spinner<Integer> valueSpinner = new Spinner<>(0, 9_999_999, item.value);
    valueSpinner.setEditable(true);
    valueSpinner.valueProperty().addListener((o, a, b) -> { item.value = b; markDirty(); });

    TextArea desc = new TextArea(item.description);
    desc.setPrefRowCount(3);
    desc.setWrapText(true);
    desc.textProperty().addListener((o, a, b) -> { item.description = b; markDirty(); });

    int r = 0;
    g.addRow(r++, new Label("id"), idField);
    g.addRow(r++, new Label("name"), nameField);
    g.addRow(r++, new Label("type"), typeBox);
    g.addRow(r++, new Label("value"), valueSpinner);
    g.addRow(r++, new Label("description"), desc);
    GridPane.setHgrow(nameField, Priority.ALWAYS);
    GridPane.setHgrow(desc, Priority.ALWAYS);

    rebuildTypeParams();

    ProjectSettings ps = ProjectSettings.current();
    VBox icons = new VBox(6,
        iconRow("large (" + ps.iconSize + ")", 0),
        iconRow("medium (" + ps.iconMed + ")", 1),
        iconRow("small (" + ps.iconSmall + ")", 2));
    icons.setPadding(new Insets(6));

    AnimationPanel usage = new AnimationPanel("usage animation", item.usage, baseDir(), this::markDirty, true);

    VBox box = new VBox(8,
        section("Item"), g,
        section("Icons"), icons,
        section("Usage"), usage,
        section("Type properties"), typeParams);
    box.setPadding(new Insets(4));
    root.setContent(box);
    root.setFitToWidth(true);
  }

  /** a select-only icon slot writing to icons[index]. */
  private Node iconRow(String label, int index) {
    Label l = new Label("icon " + label);
    l.setMinWidth(110);
    TextField tf = new TextField(item.icons.get(index));
    tf.setEditable(false);
    tf.setPrefWidth(240);
    ImageView thumb = new ImageView();
    thumb.setFitWidth(40);
    thumb.setFitHeight(40);
    thumb.setPreserveRatio(true);
    updateThumb(thumb, item.icons.get(index));
    markMissing(tf, item.icons.get(index));
    Button select = new Button("Select…");
    select.setOnAction(e -> ImagePicker.pick(window(), baseDir(), "Select icon").ifPresent(f -> {
      String rel = relativize(f);
      tf.setText(rel);
      item.icons.set(index, rel);
      updateThumb(thumb, rel);
      markMissing(tf, rel);
      markDirty();
    }));
    Button clear = new Button("Clear");
    clear.setOnAction(e -> {
      tf.setText("");
      item.icons.set(index, "");
      updateThumb(thumb, "");
      markMissing(tf, "");
      markDirty();
    });
    HBox row = new HBox(6, l, tf, thumb, select, clear);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  private void rebuildTypeParams() {
    typeParams.getChildren().clear();
    typeParams.setPadding(new Insets(0, 12, 12, 12));
    var schema = Item.schemaFor(item.type);
    if (schema.isEmpty()) {
      typeParams.getChildren().add(new Label("(this item type has no extra properties)"));
      return;
    }
    GridPane g = new GridPane();
    g.setHgap(8);
    g.setVgap(8);
    int r = 0;
    for (Param p : schema) {
      String current = item.params.getOrDefault(p.key, p.dflt);
      Node control;
      if (p.kind == ParamKind.INT) {
        Spinner<Integer> sp = new Spinner<>(-9_999_999, 9_999_999, parseInt(current, parseInt(p.dflt, 0)));
        sp.setEditable(true);
        sp.valueProperty().addListener((o, a, b) -> { item.params.put(p.key, String.valueOf(b)); markDirty(); });
        item.params.put(p.key, String.valueOf(sp.getValue()));
        control = sp;
      } else {
        TextField tf = new TextField(current);
        tf.textProperty().addListener((o, a, b) -> { item.params.put(p.key, b); markDirty(); });
        item.params.put(p.key, current);
        control = tf;
      }
      g.addRow(r++, new Label(p.label), control);
    }
    typeParams.getChildren().add(g);
  }

  // ------------------------------------------------------------------- helpers

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

  private void status() {
    status.setText("item — " + item.name);
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
    item.save(file);
    dirty = false;
  }

  @Override
  public boolean isDirty() {
    return dirty;
  }

  @Override
  public String title() {
    return "item";
  }
}
