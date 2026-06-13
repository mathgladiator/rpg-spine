package mg.editor;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * The editor shell: a left file tree over the chosen directory and a center
 * pane that swaps in the editor matching the selected file's extension.
 */
public class EditorApp extends Application {
  private File root;
  private final StackPane center = new StackPane();
  private final Label status = new Label("ready");
  private TreeView<File> tree;
  private Editor current;
  private File currentFile;

  @Override
  public void start(Stage stage) {
    root = EditorLauncher.ROOT;
    if (root == null) {
      // double-clicked with no directory: ask for one
      javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
      chooser.setTitle("Choose a content folder to edit");
      File home = new File(System.getProperty("user.home", "."));
      if (home.isDirectory()) {
        chooser.setInitialDirectory(home);
      }
      File picked = chooser.showDialog(stage);
      if (picked == null) {
        Platform.exit();
        return;
      }
      root = picked.getAbsoluteFile();
    }

    tree = new TreeView<>(buildTree(root));
    tree.setShowRoot(true);
    tree.setCellFactory(tv -> new TreeCell<>() {
      @Override
      protected void updateItem(File item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText(null);
        } else {
          setText(item.equals(root) ? item.getName() + "/" : item.getName());
        }
      }
    });
    tree.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
      if (now != null && now.getValue().isFile()) {
        openFile(now.getValue());
      }
    });

    Button newFile = new Button("New File");
    newFile.setOnAction(e -> promptNewFile());
    Button refresh = new Button("Refresh");
    refresh.setOnAction(e -> tree.setRoot(buildTree(root)));
    Button save = new Button("Save");
    save.setOnAction(e -> saveCurrent());

    ToolBar toolbar = new ToolBar(newFile, refresh, save);

    BorderPane left = new BorderPane(tree);
    left.setTop(toolbar);

    SplitPane split = new SplitPane(left, center);
    split.setDividerPositions(0.24);
    SplitPane.setResizableWithParent(left, false);

    BorderPane rootPane = new BorderPane(split);
    BorderPane statusBar = new BorderPane(status);
    statusBar.setPadding(new Insets(3, 8, 3, 8));
    rootPane.setBottom(statusBar);

    Label hint = new Label("Select a file on the left to edit.\n\n"
        + ".rpg     schema, validated by the spine parser\n"
        + ".dungeon Wizardry-style grid map editor\n"
        + ".world   location/path graph + scene editor");
    hint.setPadding(new Insets(20));
    center.getChildren().add(hint);

    Scene scene = new Scene(rootPane, 1180, 760);
    KeyCombination saveKey = new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN);
    scene.getAccelerators().put(saveKey, this::saveCurrent);

    stage.setTitle("rpg-spine editor — " + root.getAbsolutePath());
    stage.setScene(scene);
    stage.setOnCloseRequest(e -> {
      if (!confirmDiscardIfDirty()) {
        e.consume();
      }
    });
    stage.show();
  }

  private TreeItem<File> buildTree(File dir) {
    TreeItem<File> item = new TreeItem<>(dir);
    File[] kids = dir.listFiles();
    if (kids != null) {
      Arrays.sort(kids, Comparator
          .comparing((File f) -> f.isFile())  // directories first
          .thenComparing(f -> f.getName().toLowerCase()));
      for (File k : kids) {
        if (k.getName().startsWith(".") || k.getName().equals("target")) {
          continue;
        }
        if (k.isDirectory()) {
          TreeItem<File> sub = buildTree(k);
          if (!sub.getChildren().isEmpty()) {
            item.getChildren().add(sub);
          }
        } else {
          item.getChildren().add(new TreeItem<>(k));
        }
      }
    }
    item.setExpanded(true);
    return item;
  }

  private void openFile(File file) {
    if (file.equals(currentFile)) {
      return;
    }
    if (!confirmDiscardIfDirty()) {
      // re-select the previous file so the tree reflects reality
      return;
    }
    try {
      Editor editor = makeEditor(file);
      current = editor;
      currentFile = file;
      center.getChildren().setAll(editor.getNode());
      status.setText(editor.title() + " — " + file.getName());
    } catch (Exception ex) {
      ex.printStackTrace();
      error("Could not open " + file.getName(), ex.getMessage());
    }
  }

  private Editor makeEditor(File file) throws Exception {
    String name = file.getName().toLowerCase();
    if (name.endsWith(".rpg")) {
      return new RpgEditor(file, status);
    }
    if (name.endsWith(".dungeon")) {
      return new DungeonEditor(file, status);
    }
    if (name.endsWith(".world")) {
      return new WorldEditor(file, status);
    }
    return new PlainTextEditor(file, status);
  }

  private void saveCurrent() {
    if (current == null) {
      return;
    }
    try {
      current.save();
      status.setText("saved " + (currentFile != null ? currentFile.getName() : ""));
    } catch (Exception ex) {
      ex.printStackTrace();
      error("Save failed", ex.getMessage());
    }
  }

  private boolean confirmDiscardIfDirty() {
    if (current == null || !current.isDirty()) {
      return true;
    }
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
        "Discard unsaved changes to " + (currentFile != null ? currentFile.getName() : "the file") + "?",
        ButtonType.YES, ButtonType.NO);
    alert.setHeaderText("Unsaved changes");
    Optional<ButtonType> r = alert.showAndWait();
    return r.isPresent() && r.get() == ButtonType.YES;
  }

  private void promptNewFile() {
    TextInputDialog dialog = new TextInputDialog("untitled.dungeon");
    dialog.setHeaderText("New file (relative to " + root.getName() + ")");
    dialog.setContentText("name:");
    dialog.showAndWait().ifPresent(rawName -> {
      String fileName = rawName.strip();
      if (fileName.isEmpty()) {
        return;
      }
      File f = new File(root, fileName);
      try {
        File parent = f.getParentFile();
        if (parent != null) {
          parent.mkdirs();
        }
        if (!f.exists()) {
          f.createNewFile();
        }
        tree.setRoot(buildTree(root));
        Platform.runLater(() -> openFile(f));
      } catch (Exception ex) {
        error("Could not create file", ex.getMessage());
      }
    });
  }

  private void error(String header, String message) {
    Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "" : message, ButtonType.OK);
    alert.setHeaderText(header);
    alert.showAndWait();
  }
}
