package mg.editor;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import mg.editor.asset.Animation;

import java.io.File;
import java.util.ArrayList;

/**
 * Editor for one {@link Animation}: an ordered, explicit list of frame image
 * files (added via the project-scoped {@link ImagePicker}, reorderable), a frame
 * speed and loop count, and a play/visualize preview. Each frame is a real file;
 * nothing is implicit. Used for monster attack, dungeon idle/walk orientations,
 * and item usage animations.
 */
public class AnimationPanel extends VBox {
  private final Animation anim;
  private final File baseDir;
  private final Runnable onChange;
  private final ListView<String> list = new ListView<>();
  private final ImageView preview = new ImageView();

  private Timeline player;
  private int idx;

  public AnimationPanel(String title, Animation anim, File baseDir, Runnable onChange, boolean showLoop) {
    super(6);
    this.anim = anim;
    this.baseDir = baseDir;
    this.onChange = onChange;
    setPadding(new Insets(6));

    list.getItems().setAll(anim.frames);
    list.setPrefHeight(120);
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
    list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showFrame(list.getSelectionModel().getSelectedIndex()));

    Button add = new Button("Add frame…");
    add.setOnAction(e -> addFrame());
    Button remove = new Button("Remove");
    remove.setOnAction(e -> removeSelected());
    Button up = new Button("↑");
    up.setOnAction(e -> move(-1));
    Button down = new Button("↓");
    down.setOnAction(e -> move(1));
    HBox listButtons = new HBox(6, add, remove, up, down);

    Spinner<Integer> fps = new Spinner<>(1, 30, anim.fps);
    fps.setPrefWidth(70);
    fps.setEditable(true);
    fps.valueProperty().addListener((o, a, b) -> { anim.fps = b; if (player != null) play(); changed(); });

    HBox speed = new HBox(6, new Label("fps"), fps);
    speed.setAlignment(Pos.CENTER_LEFT);
    if (showLoop) {
      Spinner<Integer> loop = new Spinner<>(0, 999, anim.loop);
      loop.setPrefWidth(70);
      loop.setEditable(true);
      loop.valueProperty().addListener((o, a, b) -> { anim.loop = b; changed(); });
      speed.getChildren().addAll(new Label("loop (0=∞)"), loop);
    }

    ToggleButton playBtn = new ToggleButton("Play");
    playBtn.setOnAction(e -> {
      if (playBtn.isSelected()) {
        play();
      } else {
        stop();
      }
    });
    preview.setPreserveRatio(true);
    preview.setSmooth(false);
    preview.setFitHeight(96);
    preview.setFitWidth(96);

    HBox controls = new HBox(10, speed, playBtn);
    controls.setAlignment(Pos.CENTER_LEFT);

    Label header = new Label(title);
    HBox body = new HBox(10, new VBox(4, list, listButtons), new VBox(4, new Label("preview"), preview));
    HBox.setHgrow(body.getChildren().get(0), Priority.ALWAYS);
    getChildren().addAll(header, body, controls);
    showFrame(0);
  }

  // ------------------------------------------------------------------- editing

  private void addFrame() {
    ImagePicker.pick(getScene() != null ? getScene().getWindow() : null, baseDir, "Add animation frame")
        .ifPresent(f -> {
          list.getItems().add(relativize(f));
          commit();
        });
  }

  private void removeSelected() {
    int i = list.getSelectionModel().getSelectedIndex();
    if (i >= 0) {
      list.getItems().remove(i);
      commit();
    }
  }

  private void move(int delta) {
    int i = list.getSelectionModel().getSelectedIndex();
    int j = i + delta;
    if (i < 0 || j < 0 || j >= list.getItems().size()) {
      return;
    }
    String item = list.getItems().remove(i);
    list.getItems().add(j, item);
    list.getSelectionModel().select(j);
    commit();
  }

  /** mirror the list back into the model and notify. */
  private void commit() {
    anim.frames.clear();
    anim.frames.addAll(new ArrayList<>(list.getItems()));
    changed();
  }

  private void changed() {
    if (onChange != null) {
      onChange.run();
    }
  }

  // ------------------------------------------------------------------- preview

  private void showFrame(int i) {
    if (i < 0 || i >= list.getItems().size()) {
      preview.setImage(null);
      return;
    }
    File f = resolve(list.getItems().get(i));
    if (f.isFile()) {
      try {
        preview.setImage(new Image(f.toURI().toString()));
        return;
      } catch (Exception ignore) {
        // fall through
      }
    }
    preview.setImage(null);
  }

  private void play() {
    stop();
    if (list.getItems().isEmpty()) {
      return;
    }
    idx = 0;
    player = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1.0 / Math.max(1, anim.fps)), e -> {
      showFrame(idx);
      idx = (idx + 1) % list.getItems().size();
    }));
    player.setCycleCount(javafx.animation.Animation.INDEFINITE);
    player.play();
  }

  private void stop() {
    if (player != null) {
      player.stop();
      player = null;
    }
  }

  // -------------------------------------------------------------------- paths

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
}
