package mg.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * A project-scoped image picker. Unlike a native {@code FileChooser}, it is
 * rooted at a base directory (the editing file's folder) and <b>cannot navigate
 * above it</b>, so every image reference is guaranteed to live in the file's
 * folder or a subfolder — which keeps stored paths relative and inside the
 * project. Shows a tree of folders + image files with a preview.
 */
public final class ImagePicker {
  private static final String[] EXTS = {".png", ".jpg", ".jpeg", ".gif", ".bmp"};

  private ImagePicker() {}

  /** true if the name looks like an image we accept. */
  public static boolean isImage(File f) {
    String n = f.getName().toLowerCase();
    for (String e : EXTS) {
      if (n.endsWith(e)) {
        return true;
      }
    }
    return false;
  }

  /** pick an image within {@code baseDir} (inclusive subtree); empty if cancelled. */
  public static Optional<File> pick(Window owner, File baseDir, String title) {
    if (baseDir == null || !baseDir.isDirectory()) {
      return Optional.empty();
    }
    TreeView<File> tree = new TreeView<>(buildTree(baseDir));
    tree.setShowRoot(true);
    tree.setCellFactory(tv -> new TreeCell<>() {
      @Override
      protected void updateItem(File item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? null
            : (item.equals(baseDir) ? item.getName() + "/" : item.getName()));
      }
    });

    ImageView preview = new ImageView();
    preview.setPreserveRatio(true);
    preview.setFitWidth(260);
    preview.setFitHeight(260);
    Label info = new Label("select an image");

    Stage stage = new Stage();
    stage.initOwner(owner);
    stage.initModality(Modality.WINDOW_MODAL);
    stage.setTitle(title == null ? "Choose image" : title);

    Optional<File>[] result = new Optional[] {Optional.empty()};
    Button ok = new Button("Choose");
    ok.setDisable(true);
    Button cancel = new Button("Cancel");

    tree.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
      File f = now == null ? null : now.getValue();
      boolean img = f != null && f.isFile() && isImage(f);
      ok.setDisable(!img);
      if (img) {
        try {
          preview.setImage(new Image(f.toURI().toString(), 260, 260, true, true));
          info.setText(f.getName());
        } catch (Exception ex) {
          preview.setImage(null);
          info.setText("(cannot preview " + f.getName() + ")");
        }
      } else {
        preview.setImage(null);
        info.setText(f != null && f.isDirectory() ? f.getName() + "/" : "select an image");
      }
    });

    Runnable choose = () -> {
      TreeItem<File> sel = tree.getSelectionModel().getSelectedItem();
      if (sel != null && sel.getValue().isFile() && isImage(sel.getValue())) {
        result[0] = Optional.of(sel.getValue());
        stage.close();
      }
    };
    ok.setOnAction(e -> choose.run());
    cancel.setOnAction(e -> stage.close());
    tree.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2) {
        choose.run();
      }
    });

    HBox bar = new HBox(8, info, new javafx.scene.layout.Region(), ok, cancel);
    HBox.setHgrow(bar.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.setPadding(new Insets(8));

    SplitPane split = new SplitPane(tree, new BorderPane(preview));
    split.setDividerPositions(0.5);
    BorderPane rootPane = new BorderPane(split);
    rootPane.setBottom(bar);
    stage.setScene(new Scene(rootPane, 720, 520));
    stage.showAndWait();
    return result[0];
  }

  /** a tree of folders + image files under {@code dir}; folders without images are dropped. */
  private static TreeItem<File> buildTree(File dir) {
    TreeItem<File> item = new TreeItem<>(dir);
    File[] kids = dir.listFiles();
    if (kids != null) {
      Arrays.sort(kids, Comparator
          .comparing((File f) -> f.isFile())
          .thenComparing(f -> f.getName().toLowerCase()));
      for (File k : kids) {
        if (k.getName().startsWith(".") && !k.getName().toLowerCase().endsWith(".ref.png")) {
          // hide dotfiles, but .ref.png references are visible
          if (!isImage(k)) {
            continue;
          }
        }
        if (k.getName().equals("target")) {
          continue;
        }
        if (k.isDirectory()) {
          TreeItem<File> sub = buildTree(k);
          if (!sub.getChildren().isEmpty()) {
            item.getChildren().add(sub);
          }
        } else if (isImage(k)) {
          item.getChildren().add(new TreeItem<>(k));
        }
      }
    }
    item.setExpanded(true);
    return item;
  }
}
