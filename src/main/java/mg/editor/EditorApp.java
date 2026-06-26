package mg.editor;

import javafx.application.Platform;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
  /** guards against re-entrant tree selection changes when we revert a selection. */
  private boolean suppressSelection;
  /** canonical files with errors (parse failure / lost image refs) — shown red. */
  private java.util.Set<File> erroredFiles = new java.util.HashSet<>();
  /** the file/folder currently being dragged within the tree, if any. */
  private File dragged;

  @Override
  public void start(Stage stage) {
    this.stage = stage;
    Thread.setDefaultUncaughtExceptionHandler((t, ex) -> Log.error("uncaught in " + t.getName(), ex));
    Log.info("editor started");

    tree = new TreeView<>();
    tree.setShowRoot(true);
    tree.setCellFactory(tv -> {
      TreeCell<File> cell = new TreeCell<>() {
        @Override
        protected void updateItem(File item, boolean empty) {
          super.updateItem(item, empty);
          if (empty || item == null) {
            setText(null);
            setStyle("");
          } else {
            setText(item.equals(root) ? item.getName() + "/" : item.getName());
            setStyle(isErrored(item) ? "-fx-text-fill: #c62828;" : "");
          }
        }
      };
      installDragAndDrop(cell);
      installContextMenu(cell);
      return cell;
    });
    tree.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
      if (suppressSelection) {
        return;
      }
      if (now != null && now.getValue().isFile()) {
        if (!openFile(now.getValue()) && was != null) {
          // discard was cancelled — keep the previously open file selected
          suppressSelection = true;
          tree.getSelectionModel().select(was);
          suppressSelection = false;
        }
      }
    });

    Button newFile = new Button("New File");
    newFile.setOnAction(e -> promptNewFile());
    Button newFolder = new Button("New Folder");
    newFolder.setOnAction(e -> promptNewFolder());
    Button refresh = new Button("Refresh");
    refresh.setOnAction(e -> { if (root != null) { tree.setRoot(buildTree(root)); recomputeErrors(); } });
    Button save = new Button("Save");
    save.setOnAction(e -> saveCurrent());
    ToolBar toolbar = new ToolBar(newFile, newFolder, refresh, save);

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

    // any file-producing action (reference gen, B&W extract, codegen) can ask the tree to rescan
    ProjectRefresh.set(() -> {
      if (root != null) {
        tree.setRoot(buildTree(root));
        recomputeErrors();
      }
    });

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

    MenuItem grokKey = new MenuItem("Grok API Key…");
    grokKey.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN));
    grokKey.setOnAction(e -> showSettingsDialog());
    MenuItem projectSettings = new MenuItem("Project Settings…");
    projectSettings.setOnAction(e -> showProjectSettingsDialog());
    Menu settings = new Menu("Settings");
    settings.getItems().addAll(grokKey, projectSettings);

    MenuItem logItem = new MenuItem("Log");
    logItem.setAccelerator(new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN));
    logItem.setOnAction(e -> LogWindow.show(stage));
    Menu view = new Menu("View");
    view.getItems().add(logItem);

    MenuItem assetsAudit = new MenuItem("Project Assets…");
    assetsAudit.setOnAction(e -> {
      if (root == null) {
        error("No project open", "Open a project folder first.");
      } else {
        AuditDialog.open(stage, root, this::openFile, () -> {
          recomputeErrors();
          tree.setRoot(buildTree(root));
        });
      }
    });
    Menu audit = new Menu("Audit");
    audit.getItems().add(assetsAudit);

    MenuItem compile = new MenuItem("Compile…");
    compile.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN));
    compile.setOnAction(e -> {
      if (root == null) {
        error("No project open", "Open a project folder first.");
      } else {
        CompilerWindow.open(stage, root);
      }
    });
    Menu build = new Menu("Build");
    build.getItems().add(compile);

    MenuBar bar = new MenuBar(project, settings, view, audit, build);
    // keep menus inside the window (not the macOS system bar) for consistency
    bar.setUseSystemMenuBar(false);
    return bar;
  }

  /** a masked secret field paired with a plain field and a Show toggle. */
  private static final class KeyField {
    final PasswordField masked = new PasswordField();
    final TextField plain = new TextField();
    final CheckBox reveal = new CheckBox("Show");
    final StackPane node;

    KeyField(String prompt, String initial) {
      masked.setPromptText(prompt);
      masked.setText(initial);
      masked.setPrefColumnCount(40);
      plain.setPromptText(prompt);
      plain.textProperty().bindBidirectional(masked.textProperty());
      plain.setManaged(false);
      plain.setVisible(false);
      reveal.selectedProperty().addListener((o, was, now) -> {
        plain.setManaged(now);
        plain.setVisible(now);
        masked.setManaged(!now);
        masked.setVisible(!now);
      });
      node = new StackPane(masked, plain);
    }

    String text() {
      return masked.getText();
    }
  }

  /**
   * Settings dialog for AI keys: the xAI Grok key (assistance) and the PixelLab
   * key (pixel-art image generation), via {@link Settings}. Each key entry is
   * masked by default with a Show toggle.
   */
  private void showSettingsDialog() {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(stage);
    dialog.setTitle("Settings");
    dialog.setHeaderText("AI keys — xAI Grok (assistance) and PixelLab (image generation)");

    ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

    KeyField grokKey = new KeyField("xai-…", Settings.grokApiKey());
    TextField grokModel = new TextField(Settings.grokModel());
    grokModel.setPromptText(Settings.DEFAULT_GROK_MODEL);

    KeyField pixelLabKey = new KeyField("pixellab key", Settings.pixellabApiKey());

    Label note = new Label(
        "Stored locally in " + Settings.store().getAbsolutePath()
            + " (owner-only where supported). Grok key: https://console.x.ai · "
            + "PixelLab key: https://www.pixellab.ai/account");
    note.setWrapText(true);
    note.setMaxWidth(460);
    note.setStyle("-fx-opacity: 0.7;");

    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(10);
    grid.setPadding(new Insets(12));
    int r = 0;
    grid.add(new Label("Grok API key:"), 0, r);
    grid.add(grokKey.node, 1, r);
    grid.add(grokKey.reveal, 2, r++);
    grid.add(new Label("Grok model:"), 0, r);
    grid.add(grokModel, 1, r++);
    grid.add(new Separator(), 0, r++, 3, 1);
    grid.add(new Label("PixelLab API key:"), 0, r);
    grid.add(pixelLabKey.node, 1, r);
    grid.add(pixelLabKey.reveal, 2, r++);
    grid.add(note, 1, r, 2, 1);
    dialog.getDialogPane().setContent(grid);

    dialog.showAndWait().ifPresent(bt -> {
      if (bt == saveType) {
        Settings.set(Settings.GROK_API_KEY, grokKey.text());
        Settings.set(Settings.GROK_MODEL, grokModel.getText());
        Settings.set(Settings.PIXELLAB_API_KEY, pixelLabKey.text());
        status.setText("settings saved"
            + (Settings.hasGrokApiKey() ? " · Grok ✓" : " · Grok –")
            + (Settings.hasPixellabApiKey() ? " · PixelLab ✓" : " · PixelLab –"));
      }
    });
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

  /** edit the per-project {@code .project} settings (icon size, animation cell). */
  private void showProjectSettingsDialog() {
    if (root == null) {
      error("No project open", "Open a project folder first (Project ▸ Open Folder…).");
      return;
    }
    ProjectSettings p = ProjectSettings.current();
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(stage);
    dialog.setTitle("Project Settings");
    dialog.setHeaderText("Stored in " + new File(root, ".project").getAbsolutePath());
    ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

    Spinner<Integer> icon = new Spinner<>(4, 512, p.iconSize);
    icon.setEditable(true);
    Spinner<Integer> iconMed = new Spinner<>(4, 512, p.iconMed);
    iconMed.setEditable(true);
    Spinner<Integer> iconSmall = new Spinner<>(4, 512, p.iconSmall);
    iconSmall.setEditable(true);
    Spinner<Integer> cellW = new Spinner<>(4, 512, p.animCellW);
    cellW.setEditable(true);
    Spinner<Integer> cellH = new Spinner<>(4, 512, p.animCellH);
    cellH.setEditable(true);
    TextField outputDir = new TextField(p.outputDir);
    outputDir.setPromptText("out");

    GridPane g = new GridPane();
    g.setHgap(8);
    g.setVgap(10);
    g.setPadding(new Insets(12));
    g.addRow(0, new Label("Icon size large (px):"), icon);
    g.addRow(1, new Label("Icon size medium (px):"), iconMed);
    g.addRow(2, new Label("Icon size small (px):"), iconSmall);
    g.addRow(3, new Label("Animation cell width (px):"), cellW);
    g.addRow(4, new Label("Animation cell height (px):"), cellH);
    g.addRow(5, new Label("C output dir (rel. to root):"), outputDir);
    dialog.getDialogPane().setContent(g);

    dialog.showAndWait().ifPresent(bt -> {
      if (bt == saveType) {
        p.iconSize = icon.getValue();
        p.iconMed = iconMed.getValue();
        p.iconSmall = iconSmall.getValue();
        p.animCellW = cellW.getValue();
        p.animCellH = cellH.getValue();
        String od = outputDir.getText() == null ? "" : outputDir.getText().strip();
        p.outputDir = od.isEmpty() ? "out" : od;
        try {
          p.save(root);
          status.setText("saved project settings");
          Log.info("saved .project (icon=" + p.iconSize + ", cell=" + p.animCellW + "×" + p.animCellH + ")");
        } catch (Exception ex) {
          Log.error("could not save .project", ex);
          error("Could not save project settings", ex.getMessage());
        }
      }
    });
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
    ProjectSettings.load(root);
    tree.setRoot(buildTree(root));
    recomputeErrors();
    showHint();
    stage.setTitle("rpg-spine editor — " + root.getAbsolutePath());
    status.setText("opened project " + root.getName());
    Log.info("opened project " + root.getAbsolutePath());

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
            + ".dungeon micro/macro occupancy grid map editor\n"
            + ".template reusable wall/floor stamp for dungeons\n"
            + ".world   location/path graph + scene editor\n"
            + ".monster monster art + level/skill tables\n"
            + ".item    item with type-specific properties\n"
            + ".story   node-graph story editor (beat / choice / outcome)\n"
            + ".png     black & white image editor + animation stepper");
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
          // Always show directories, including empty ones the user just created;
          // only the name-based filter above (dotfiles, target) hides folders.
          item.getChildren().add(buildTree(k));
        } else {
          item.getChildren().add(new TreeItem<>(k));
        }
      }
    }
    item.setExpanded(true);
    return item;
  }

  // ----------------------------------------------------- drag & drop reorganize

  /**
   * Wire a tree cell for drag-and-drop moves: drag a file or folder onto a
   * folder (or onto a file, meaning its containing folder) to relocate it on
   * disk. Dropping on the empty space below the tree targets the project root.
   */
  private void installDragAndDrop(TreeCell<File> cell) {
    cell.setOnDragDetected(e -> {
      File f = cell.getItem();
      if (f == null || f.equals(root)) {
        return; // never move the project root itself
      }
      dragged = f;
      Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
      ClipboardContent content = new ClipboardContent();
      content.putString(f.getAbsolutePath());
      db.setContent(content);
      e.consume();
    });

    cell.setOnDragOver(e -> {
      File target = dropTargetDir(cell);
      if (canMove(dragged, target)) {
        e.acceptTransferModes(TransferMode.MOVE);
      }
      e.consume();
    });

    cell.setOnDragEntered(e -> {
      if (canMove(dragged, dropTargetDir(cell))) {
        cell.setStyle("-fx-background-color: -fx-accent;");
      }
    });

    cell.setOnDragExited(e -> cell.setStyle(""));

    cell.setOnDragDropped(e -> {
      boolean ok = moveInto(dragged, dropTargetDir(cell));
      cell.setStyle("");
      e.setDropCompleted(ok);
      e.consume();
    });

    cell.setOnDragDone(e -> {
      dragged = null;
      cell.setStyle("");
    });
  }

  /** right-click menu on a tree cell: open, clone (files), delete. */
  private void installContextMenu(TreeCell<File> cell) {
    MenuItem open = new MenuItem("Open");
    open.setOnAction(e -> {
      File f = cell.getItem();
      if (f != null && f.isFile()) {
        openFile(f);
      }
    });
    MenuItem rename = new MenuItem("Rename…");
    rename.setOnAction(e -> {
      File f = cell.getItem();
      if (f != null) {
        renameFile(f);
      }
    });
    MenuItem clone = new MenuItem("Clone");
    clone.setOnAction(e -> {
      File f = cell.getItem();
      if (f != null && f.isFile()) {
        cloneFile(f);
      }
    });
    MenuItem delete = new MenuItem("Delete");
    delete.setOnAction(e -> {
      File f = cell.getItem();
      if (f != null) {
        deleteFile(f);
      }
    });
    ContextMenu menu = new ContextMenu(open, rename, clone, new SeparatorMenuItem(), delete);
    // no menu on the empty space below the tree
    cell.emptyProperty().addListener((o, was, now) -> cell.setContextMenu(now ? null : menu));
  }

  /** rename a file or folder in place; reopen from the new path if it was open. */
  private void renameFile(File f) {
    if (f.equals(root)) {
      error("Cannot rename", "This is the project root.");
      return;
    }
    TextInputDialog d = new TextInputDialog(f.getName());
    d.setHeaderText("Rename " + (f.isDirectory() ? "folder" : "file"));
    d.setContentText("name:");
    d.showAndWait().ifPresent(raw -> {
      String name = raw.strip();
      if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
        error("Invalid name", "The name must not be empty or contain path separators.");
        return;
      }
      File dest = new File(f.getParentFile(), name);
      if (dest.equals(f)) {
        return;
      }
      if (dest.exists()) {
        error("Name already exists", dest.getName());
        return;
      }
      boolean affectedOpen = currentFile != null && isSameOrAncestor(f, currentFile);
      if (affectedOpen && !confirmDiscardIfDirty()) {
        return;
      }
      try {
        Files.move(f.toPath(), dest.toPath());
        Log.info("renamed " + f.getName() + " → " + dest.getName());
        tree.setRoot(buildTree(root));
        if (affectedOpen) {
          File reopened = remap(f, dest, currentFile);
          current = null;
          currentFile = null;
          if (reopened.isFile()) {
            Platform.runLater(() -> openFile(reopened));
          } else {
            showHint();
          }
        }
        status.setText("renamed to " + name);
      } catch (Exception ex) {
        Log.error("rename failed: " + f.getName(), ex);
        error("Rename failed", ex.getMessage());
      }
    });
  }

  /** duplicate a file alongside itself, then open the copy. */
  private void cloneFile(File f) {
    try {
      File dest = uniqueClone(f);
      Files.copy(f.toPath(), dest.toPath());
      Log.info("cloned " + f.getName() + " → " + dest.getName());
      tree.setRoot(buildTree(root));
      Platform.runLater(() -> openFile(dest));
    } catch (Exception ex) {
      Log.error("clone failed: " + f.getName(), ex);
      error("Clone failed", ex.getMessage());
    }
  }

  private File uniqueClone(File f) {
    String name = f.getName();
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    String ext = dot > 0 ? name.substring(dot) : "";
    File parent = f.getParentFile();
    for (int i = 1; ; i++) {
      String suffix = i == 1 ? "-copy" : "-copy" + i;
      File c = new File(parent, base + suffix + ext);
      if (!c.exists()) {
        return c;
      }
    }
  }

  /** delete a file or folder (recursively) after confirmation. */
  private void deleteFile(File f) {
    if (f.equals(root)) {
      error("Cannot delete", "This is the project root.");
      return;
    }
    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
        "Delete \"" + f.getName() + "\"" + (f.isDirectory() ? " and all its contents?" : "?"),
        ButtonType.YES, ButtonType.NO);
    a.setHeaderText("Delete");
    if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
      return;
    }
    try {
      boolean affectedOpen = currentFile != null && isSameOrAncestor(f, currentFile);
      deleteRecursively(f);
      Log.info("deleted " + f.getName());
      tree.setRoot(buildTree(root));
      if (affectedOpen) {
        current = null;
        currentFile = null;
        showHint();
      }
      status.setText("deleted " + f.getName());
    } catch (Exception ex) {
      Log.error("delete failed: " + f.getName(), ex);
      error("Delete failed", ex.getMessage());
    }
  }

  private static void deleteRecursively(File f) throws IOException {
    File[] kids = f.listFiles();
    if (kids != null) {
      for (File k : kids) {
        deleteRecursively(k);
      }
    }
    if (!f.delete()) {
      throw new IOException("could not delete " + f.getAbsolutePath());
    }
  }

  /** the directory a drop on this cell targets: the folder, a file's parent, or the root. */
  private File dropTargetDir(TreeCell<File> cell) {
    File f = cell.getItem();
    if (f == null) {
      return root; // empty space below the tree
    }
    return f.isDirectory() ? f : f.getParentFile();
  }

  /** can {@code src} be moved into {@code targetDir} without nonsense? */
  private boolean canMove(File src, File targetDir) {
    if (src == null || targetDir == null || !targetDir.isDirectory()) {
      return false;
    }
    if (targetDir.equals(src) || targetDir.equals(src.getParentFile())) {
      return false; // onto itself, or a no-op back into the same folder
    }
    // refuse moving a folder into itself or one of its own descendants
    return !isSameOrAncestor(src, targetDir);
  }

  /** move a file/folder into a directory on disk, then refresh the tree. */
  private boolean moveInto(File src, File targetDir) {
    if (!canMove(src, targetDir)) {
      return false;
    }
    File dest = new File(targetDir, src.getName());
    if (dest.exists()) {
      error("Cannot move", "A file named \"" + src.getName()
          + "\" already exists in " + targetDir.getName() + ".");
      return false;
    }
    // moving the open file (or a folder containing it) would orphan the editor's
    // path, so confirm any unsaved work first and reopen from the new location.
    boolean affectsOpen = currentFile != null && isSameOrAncestor(src, currentFile);
    if (affectsOpen && !confirmDiscardIfDirty()) {
      return false;
    }
    try {
      Files.move(src.toPath(), dest.toPath());
    } catch (Exception ex) {
      error("Move failed", ex.getMessage());
      return false;
    }
    tree.setRoot(buildTree(root));
    if (affectsOpen) {
      File reopened = remap(src, dest, currentFile);
      current = null;
      currentFile = null;
      if (reopened.isFile()) {
        Platform.runLater(() -> openFile(reopened));
      } else {
        showHint();
      }
    }
    status.setText("moved " + src.getName() + " → " + targetDir.getName() + "/");
    return true;
  }

  /** true when {@code f} is {@code ancestor} itself or lives somewhere beneath it. */
  private boolean isSameOrAncestor(File ancestor, File f) {
    for (File p = f; p != null; p = p.getParentFile()) {
      if (p.equals(ancestor)) {
        return true;
      }
    }
    return false;
  }

  /** translate a path rooted at {@code oldBase} to the equivalent under {@code newBase}. */
  private File remap(File oldBase, File newBase, File path) {
    if (path.equals(oldBase)) {
      return newBase;
    }
    String rel = path.getAbsolutePath().substring(oldBase.getAbsolutePath().length());
    return new File(newBase.getAbsolutePath() + rel);
  }

  // --------------------------------------------------------------- error flags

  /** rescan the project for errored files (lost refs / parse failures) and repaint. */
  private void recomputeErrors() {
    erroredFiles = (root == null) ? new java.util.HashSet<>() : AssetAudit.run(root).errored;
    if (tree != null) {
      tree.refresh();
    }
  }

  /** true if this file has errors, or (for a folder) contains any errored file. */
  private boolean isErrored(File f) {
    if (erroredFiles.isEmpty()) {
      return false;
    }
    File c = AssetAudit.canon(f);
    if (f.isDirectory()) {
      String prefix = c.getPath() + File.separator;
      for (File e : erroredFiles) {
        if (e.getPath().startsWith(prefix)) {
          return true;
        }
      }
      return false;
    }
    return erroredFiles.contains(c);
  }

  /** open a file in the center pane. Returns false if cancelled (discard declined). */
  private boolean openFile(File file) {
    if (file.equals(currentFile)) {
      return true;
    }
    if (!confirmDiscardIfDirty()) {
      return false;
    }
    try {
      Editor editor = makeEditor(file);
      current = editor;
      currentFile = file;
      center.getChildren().setAll(editor.getNode());
      status.setText(editor.title() + " — " + file.getName());
      Log.info("opened " + file.getName());
    } catch (Throwable ex) {
      // keep the file selected and show the error in-pane (View ▸ Log has more)
      Log.error("failed to load " + file.getName(), ex);
      current = null;
      currentFile = file;
      center.getChildren().setAll(failedPanel(file, ex));
      status.setText("failed to load " + file.getName());
    }
    return true;
  }

  /** an in-pane panel explaining why a file could not be loaded. */
  private Node failedPanel(File file, Throwable ex) {
    Label title = new Label("Failed to load " + file.getName());
    title.setFont(Font.font("System", FontWeight.BOLD, 14));
    Label what = new Label(ex.getMessage() == null ? ex.toString() : ex.getMessage());
    what.setWrapText(true);
    Button openLog = new Button("Open Log");
    openLog.setOnAction(e -> LogWindow.show(stage));
    TextArea trace = new TextArea(stackString(ex));
    trace.setEditable(false);
    trace.setStyle("-fx-font-family: monospace;");
    VBox.setVgrow(trace, Priority.ALWAYS);
    VBox box = new VBox(10, title, what, openLog, new Label("Details:"), trace);
    box.setPadding(new Insets(20));
    return box;
  }

  private static String stackString(Throwable ex) {
    StringWriter sw = new StringWriter();
    ex.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }

  private Editor makeEditor(File file) throws Exception {
    String name = file.getName().toLowerCase();
    if (name.endsWith(".rpg")) {
      return new RpgEditor(file, status);
    }
    if (name.endsWith(".dungeon")) {
      return new DungeonEditor(file, status);
    }
    if (name.endsWith(".template")) {
      return new TemplateEditor(file, status);
    }
    if (name.endsWith(".world")) {
      return new WorldEditor(file, status);
    }
    if (name.endsWith(".monster")) {
      return new MonsterEditor(file, status);
    }
    if (name.endsWith(".item")) {
      return new ItemEditor(file, status);
    }
    if (name.endsWith(".story")) {
      return new StoryEditor(file, status);
    }
    if (name.endsWith(".png")) {
      return new BwImageEditor(file, status);
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
      recomputeErrors();
    } catch (Exception ex) {
      ex.printStackTrace();
      error("Save failed", ex.getMessage());
    }
  }

  /**
   * Gate a transition that would lose the current editor's unsaved work. Offers
   * <b>Save</b> (persist then proceed), <b>Discard</b> (proceed losing changes), or
   * <b>Cancel</b> (abort the transition). Returns true when the caller may proceed.
   */
  private boolean confirmDiscardIfDirty() {
    if (current == null || !current.isDirty()) {
      return true;
    }
    String name = currentFile != null ? currentFile.getName() : "the file";
    ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.YES);
    ButtonType discardBtn = new ButtonType("Discard", ButtonBar.ButtonData.NO);
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
        "Save changes to " + name + " before continuing?", saveBtn, discardBtn, ButtonType.CANCEL);
    alert.setHeaderText("Unsaved changes");
    Optional<ButtonType> r = alert.showAndWait();
    if (r.isEmpty() || r.get() == ButtonType.CANCEL) {
      return false;
    }
    if (r.get() == saveBtn) {
      try {
        current.save();
        recomputeErrors();
      } catch (Exception ex) {
        Log.error("save failed during discard prompt", ex);
        error("Save failed", ex.getMessage());
        return false; // don't lose work if the save didn't take
      }
    }
    return true;
  }

  private void promptNewFile() {
    if (root == null) {
      error("No project open", "Open a project folder first (Project ▸ Open Folder…).");
      return;
    }
    File base = selectedDirOrRoot();
    TextInputDialog dialog = new TextInputDialog("untitled.dungeon");
    dialog.setHeaderText("New file (relative to " + base.getName() + ")");
    dialog.setContentText("name:");
    dialog.showAndWait().ifPresent(rawName -> {
      String fileName = rawName.strip();
      if (fileName.isEmpty()) {
        return;
      }
      File f = new File(base, fileName);
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

  private void promptNewFolder() {
    if (root == null) {
      error("No project open", "Open a project folder first (Project ▸ Open Folder…).");
      return;
    }
    File base = selectedDirOrRoot();
    TextInputDialog dialog = new TextInputDialog("new-folder");
    dialog.setHeaderText("New folder (relative to " + base.getName() + ")");
    dialog.setContentText("name:");
    dialog.showAndWait().ifPresent(rawName -> {
      String folderName = rawName.strip();
      if (folderName.isEmpty()) {
        return;
      }
      File dir = new File(base, folderName);
      if (dir.exists()) {
        error("Folder exists", dir.getAbsolutePath());
        return;
      }
      if (!dir.mkdirs()) {
        error("Could not create folder", dir.getAbsolutePath());
        return;
      }
      tree.setRoot(buildTree(root));
      status.setText("created folder " + folderName);
    });
  }

  /** the directory currently selected in the tree, or the project root. */
  private File selectedDirOrRoot() {
    TreeItem<File> sel = tree.getSelectionModel().getSelectedItem();
    if (sel != null && sel.getValue() != null) {
      File f = sel.getValue();
      if (f.isDirectory()) {
        return f;
      }
      File parent = f.getParentFile();
      if (parent != null && parent.isDirectory()) {
        return parent;
      }
    }
    return root;
  }

  private void error(String header, String message) {
    Log.warn(header + (message == null || message.isBlank() ? "" : ": " + message));
    Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "" : message, ButtonType.OK);
    alert.setHeaderText(header);
    alert.showAndWait();
  }
}
