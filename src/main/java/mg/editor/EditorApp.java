package mg.editor;

import javafx.application.Platform;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The editor shell: a menu bar, a left file tree over the open project folder,
 * and a center pane that swaps in the editor matching the selected file's
 * extension. Projects (folders) can be opened from the Project menu, are tracked
 * in a per-user recent list, and the most recent one auto-opens on launch.
 */
public class EditorApp extends Application {
  private Stage stage;
  private File root;
  private final StackPane center = new StackPane();
  private final Label status = new Label("ready");
  private TreeView<File> tree;
  private final Menu recentMenu = new Menu("Recent Projects");
  private Editor current;
  private File currentFile;

  @Override
  public void start(Stage stage) {
    this.stage = stage;

    tree = new TreeView<>();
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
    refresh.setOnAction(e -> { if (root != null) tree.setRoot(buildTree(root)); });
    Button save = new Button("Save");
    save.setOnAction(e -> saveCurrent());
    ToolBar toolbar = new ToolBar(newFile, refresh, save);

    BorderPane left = new BorderPane(tree);
    left.setTop(toolbar);

    SplitPane split = new SplitPane(left, center);
    split.setDividerPositions(0.24);
    SplitPane.setResizableWithParent(left, false);

    BorderPane rootPane = new BorderPane(split);
    rootPane.setTop(buildMenuBar());
    BorderPane statusBar = new BorderPane(status);
    statusBar.setPadding(new Insets(3, 8, 3, 8));
    rootPane.setBottom(statusBar);

    showHint();

    Scene scene = new Scene(rootPane, 1180, 760);
    scene.getAccelerators().put(
        new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), this::saveCurrent);

    stage.setTitle("rpg-spine editor");
    stage.setScene(scene);
    stage.setOnCloseRequest(e -> {
      if (!confirmDiscardIfDirty()) {
        e.consume();
      }
    });
    stage.show();

    chooseInitialProject();
  }

  // ----------------------------------------------------------------- menu bar

  private MenuBar buildMenuBar() {
    MenuItem openFolder = new MenuItem("Open Folder…");
    openFolder.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN));
    openFolder.setOnAction(e -> {
      File picked = chooseFolder("Open project folder");
      if (picked != null) {
        openProject(picked);
      }
    });

    MenuItem close = new MenuItem("Close");
    close.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.CONTROL_DOWN));
    close.setOnAction(e -> requestQuit());

    rebuildRecentMenu();

    Menu project = new Menu("Project");
    project.getItems().addAll(openFolder, recentMenu, new SeparatorMenuItem(), close);

    MenuBar bar = new MenuBar(project);
    // keep menus inside the window (not the macOS system bar) for consistency
    bar.setUseSystemMenuBar(false);
    return bar;
  }

  private void rebuildRecentMenu() {
    recentMenu.getItems().clear();
    List<File> recents = RecentProjects.load();
    if (recents.isEmpty()) {
      MenuItem none = new MenuItem("(no recent projects)");
      none.setDisable(true);
      recentMenu.getItems().add(none);
      return;
    }
    for (File dir : recents) {
      MenuItem item = new MenuItem(dir.getName() + "   —   " + dir.getAbsolutePath());
      item.setOnAction(e -> openProject(dir));
      recentMenu.getItems().add(item);
    }
    recentMenu.getItems().add(new SeparatorMenuItem());
    MenuItem clear = new MenuItem("Clear Recent Projects");
    clear.setOnAction(e -> {
      RecentProjects.clear();
      rebuildRecentMenu();
    });
    recentMenu.getItems().add(clear);
  }

  // ---------------------------------------------------------- project lifecycle

  /**
   * Decide what to open on startup: an explicit {@code --editor} folder wins;
   * otherwise auto-open the most recent project; only when there is none do we
   * prompt for a folder.
   */
  private void chooseInitialProject() {
    if (EditorLauncher.ROOT != null) {
      openProject(EditorLauncher.ROOT);
      return;
    }
    File recent = RecentProjects.mostRecent();
    if (recent != null) {
      openProject(recent);
      return;
    }
    File picked = chooseFolder("Choose a content folder to edit");
    if (picked != null) {
      openProject(picked);
    }
  }

  private File chooseFolder(String title) {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle(title);
    File initial = root != null ? root.getParentFile() : new File(System.getProperty("user.home", "."));
    if (initial != null && initial.isDirectory()) {
      chooser.setInitialDirectory(initial);
    }
    return chooser.showDialog(stage);
  }

  private void openProject(File dir) {
    if (dir == null) {
      return;
    }
    if (!dir.isDirectory()) {
      error("Cannot open project", "Not a directory:\n" + dir.getAbsolutePath());
      return;
    }
    if (!confirmDiscardIfDirty()) {
      return;
    }
    root = dir.getAbsoluteFile();
    current = null;
    currentFile = null;
    tree.setRoot(buildTree(root));
    showHint();
    stage.setTitle("rpg-spine editor — " + root.getAbsolutePath());
    status.setText("opened project " + root.getName());

    // record as most-recent and refresh the menu order
    RecentProjects.add(root);
    rebuildRecentMenu();
  }

  private void requestQuit() {
    if (confirmDiscardIfDirty()) {
      Platform.exit();
    }
  }

  private void showHint() {
    Label hint = new Label(root == null
        ? "No project open.\n\nProject ▸ Open Folder…  to choose a content folder."
        : "Select a file on the left to edit.\n\n"
            + ".rpg     schema, validated by the spine parser\n"
            + ".dungeon Wizardry-style grid map editor\n"
            + ".world   location/path graph + scene editor");
    hint.setPadding(new Insets(20));
    center.getChildren().setAll(hint);
  }

  // ------------------------------------------------------------------- file tree

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
    if (root == null) {
      error("No project open", "Open a project folder first (Project ▸ Open Folder…).");
      return;
    }
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
