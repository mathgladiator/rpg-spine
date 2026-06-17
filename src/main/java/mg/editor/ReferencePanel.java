package mg.editor;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.geometry.Insets;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import mg.editor.asset.ExtractSettings;
import mg.editor.asset.SkeletonData;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Manages a monster's full-colour {@code .ref.png} references: create one from a
 * prompt, derive a new one by prompting onto a selected reference, add an
 * existing image, or extract a black-and-white frame from a selected reference
 * (region select + resample). References are the high-fidelity domain the artist
 * works in before sampling down to 1-bit — there is no Gen/Edit on the frames
 * themselves; frames are produced here and then selected into animations.
 */
public class ReferencePanel extends VBox {
  private final List<String> references;
  private final File baseDir;
  private final String id;
  private final int extractSize;
  private final Map<String, SkeletonData> skeletons;
  private final ExtractSettings extractSettings;
  private final Runnable onChange;

  private final ListView<String> list = new ListView<>();
  private final ImageView preview = new ImageView();

  public ReferencePanel(List<String> references, File baseDir, String id, int extractSize,
                        Map<String, SkeletonData> skeletons, ExtractSettings extractSettings, Runnable onChange) {
    super(6);
    this.references = references;
    this.baseDir = baseDir;
    this.id = id;
    this.extractSize = extractSize;
    this.skeletons = skeletons;
    this.extractSettings = extractSettings;
    this.onChange = onChange;
    setPadding(new Insets(6));

    list.getItems().setAll(references);
    list.setPrefHeight(110);
    list.setCellFactory(v -> new ListCell<>() {
      @Override
      protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText(null);
          setStyle("");
        } else {
          boolean missing = !resolve(item).isFile();
          setText((missing ? "* " : "") + item);
          setStyle(missing ? "-fx-text-fill: #c62828;" : "");
        }
      }
    });
    list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showPreview(b));

    preview.setPreserveRatio(true);
    preview.setFitWidth(180);
    preview.setFitHeight(180);

    Button generate = new Button("Generate…");
    generate.setOnAction(e -> generate());
    Button style = new Button("Style onto…");
    style.setOnAction(e -> style());
    Button rotate = new Button("Rotate…");
    rotate.setOnAction(e -> rotate());
    Button animate = new Button("Animate…");
    animate.setOnAction(e -> animate());
    Button skeleton = new Button("Skeleton…");
    skeleton.setOnAction(e -> skeleton());
    Button extract = new Button("Extract → B&W…");
    extract.setOnAction(e -> extract());
    Button addExisting = new Button("Add existing…");
    addExisting.setOnAction(e -> addExisting());
    Button remove = new Button("Remove");
    remove.setOnAction(e -> removeSelected());

    // FlowPane so the buttons wrap instead of being clipped in a narrow panel
    FlowPane buttons = new FlowPane(6, 6, generate, style, rotate, animate, skeleton,
        extract, addExisting, remove);
    HBox body = new HBox(10, list, new VBox(4, new Label("reference preview"), preview));
    HBox.setHgrow(list, Priority.ALWAYS);
    getChildren().addAll(
        new Label("Reference images (PixelLab; stored in ref/ — full colour, view only)"),
        body, buttons);
  }

  // -------------------------------------------------------------------- actions

  private static final int REF_SIZE = 128;

  private void generate() {
    GeneratePromptDialog.ask(window(), "Describe the image to generate (PixelLab)",
        id + ", full body, simple silhouette", REF_SIZE, 400).ifPresent(res -> {
      File target = uniqueRef();
      PixelLabGen.pixflux(window(), res.prompt(), target, res.size(), res.size(), this::added);
    });
  }

  /** generate a new reference styled after the selected one (bitforge). */
  private void style() {
    String sel = requireSel();
    if (sel == null) {
      return;
    }
    GeneratePromptDialog.ask(window(), "Describe the new image, styled after " + sel,
        id + ", side view", REF_SIZE, 200).ifPresent(res -> {
      File target = uniqueRef();
      PixelLabGen.bitforge(window(), res.prompt(), resolve(sel), target, res.size(), res.size(), this::added);
    });
  }

  /** rotate the selected reference to a new facing direction. */
  private void rotate() {
    String sel = requireSel();
    if (sel == null) {
      return;
    }
    ChoiceDialog<String> d = new ChoiceDialog<>("east", List.of("south", "north", "east", "west"));
    d.setHeaderText("Rotate " + sel + " to which facing?");
    d.setContentText("direction:");
    d.showAndWait().ifPresent(dir -> {
      File target = uniqueRef();
      PixelLabGen.rotate(window(), resolve(sel), target, REF_SIZE, REF_SIZE, "south", dir, this::added);
    });
  }

  /** open the skeleton walk dialog for the selected reference (animate-with-skeleton). */
  private void skeleton() {
    String sel = requireSel();
    if (sel == null) {
      return;
    }
    SkeletonAnimateDialog.open(window(), resolve(sel), baseDir, id, skeletons, onChange, files -> {
      for (File f : files) {
        added(f);
      }
    });
  }

  /** animate the selected reference by an action (animate-with-text → frames). */
  private void animate() {
    String sel = requireSel();
    if (sel == null) {
      return;
    }
    AnimateTextDialog.ask(window(), "Animate from " + sel, "walk").ifPresent(res -> {
      String base = id + "-" + res.action().replaceAll("[^a-zA-Z0-9]+", "_");
      PixelLabGen.animate(window(), resolve(sel), id, res.action(), 4, 64, 64, res.guidance(), refDir(), base, files -> {
        for (File f : files) {
          added(f);
        }
      });
    });
  }

  private void extract() {
    String sel = requireSel();
    if (sel == null) {
      return;
    }
    ReferenceExtractDialog.open(window(), resolve(sel), baseDir, extractSize, extractSettings, onChange);
    // the extracted 1-bit frame lands in the folder; select it into an animation
  }

  private void addExisting() {
    ImagePicker.pick(window(), baseDir, "Add reference image").ifPresent(f -> added(f));
  }

  /** the selected reference, or null after warning that one is needed. */
  private String requireSel() {
    String sel = list.getSelectionModel().getSelectedItem();
    if (sel == null) {
      Alert a = new Alert(Alert.AlertType.INFORMATION,
          "Select a reference image in the list first.", ButtonType.OK);
      a.setHeaderText("No reference selected");
      a.showAndWait();
    }
    return sel;
  }

  private void removeSelected() {
    String sel = list.getSelectionModel().getSelectedItem();
    if (sel != null) {
      references.remove(sel);
      list.getItems().remove(sel);
      changed();
    }
  }

  private void added(File f) {
    String rel = relativize(f);
    if (!references.contains(rel)) {
      references.add(rel);
      list.getItems().setAll(references);
    }
    list.getSelectionModel().select(rel);
    changed();
  }

  // -------------------------------------------------------------------- helpers

  private void showPreview(String rel) {
    if (rel == null) {
      preview.setImage(null);
      return;
    }
    File f = resolve(rel);
    preview.setImage(f.isFile() ? new Image(f.toURI().toString(), 180, 180, true, true) : null);
  }

  private File refDir() {
    return new File(baseDir, "ref");
  }

  private File uniqueRef() {
    for (int i = 1; ; i++) {
      String name = (i == 1) ? id + ".ref.png" : id + "-" + i + ".ref.png";
      File f = new File(refDir(), name);
      if (!f.exists()) {
        return f;
      }
    }
  }

  private File resolve(String rel) {
    File f = new File(rel);
    return f.isAbsolute() ? f : new File(baseDir, rel);
  }

  private String relativize(File picked) {
    try {
      return baseDir.toPath().relativize(picked.toPath()).toString().replace('\\', '/');
    } catch (Exception e) {
      return picked.getName();
    }
  }

  private void changed() {
    if (onChange != null) {
      onChange.run();
    }
  }

  private Window window() {
    return getScene() != null ? getScene().getWindow() : null;
  }
}
