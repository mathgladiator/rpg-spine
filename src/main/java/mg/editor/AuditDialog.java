package mg.editor;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.util.function.Consumer;

/**
 * The asset audit report (Audit ▸ Project Assets…): lists <b>lost</b> images
 * (referenced but missing — with their owning document and an Open button) and
 * <b>orphan</b> images (on disk but unreferenced — with Delete all). Re-runs
 * after changes and notifies the editor so the tree refreshes.
 */
public final class AuditDialog {
  private AuditDialog() {}

  public static void open(Window owner, File root, Consumer<File> openFile, Runnable onChange) {
    Stage stage = new Stage();
    stage.initOwner(owner);
    stage.setTitle("Project asset audit");

    ListView<AssetAudit.Lost> lostList = new ListView<>();
    lostList.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
      @Override
      protected void updateItem(AssetAudit.Lost item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText(null);
        } else {
          setText("✖ " + item.ref() + "   (in " + item.owner().getName() + ")");
          setStyle("-fx-text-fill: #c62828;");
        }
      }
    });
    ListView<File> orphanList = new ListView<>();
    orphanList.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
      @Override
      protected void updateItem(File item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? null : rel(root, item));
      }
    });

    Label summary = new Label();

    Runnable refresh = () -> {
      AssetAudit.Result r = AssetAudit.run(root);
      lostList.getItems().setAll(r.lost);
      orphanList.getItems().setAll(r.orphans);
      summary.setText(r.lost.size() + " lost · " + r.orphans.size() + " orphaned");
    };

    Button openOwner = new Button("Open owner");
    openOwner.setOnAction(e -> {
      AssetAudit.Lost sel = lostList.getSelectionModel().getSelectedItem();
      if (sel != null && openFile != null) {
        openFile.accept(sel.owner());
      }
    });
    Button deleteOrphans = new Button("Delete all orphans");
    deleteOrphans.setOnAction(e -> {
      if (orphanList.getItems().isEmpty()) {
        return;
      }
      Alert a = new Alert(Alert.AlertType.CONFIRMATION,
          "Permanently delete " + orphanList.getItems().size() + " orphaned image file(s)?",
          ButtonType.YES, ButtonType.NO);
      a.setHeaderText("Delete orphans");
      if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
        return;
      }
      int n = 0;
      for (File f : orphanList.getItems()) {
        try {
          Files.deleteIfExists(f.toPath());
          n++;
        } catch (Exception ex) {
          Log.error("could not delete orphan " + f.getName(), ex);
        }
      }
      Log.info("deleted " + n + " orphaned image(s)");
      if (onChange != null) {
        onChange.run();
      }
      refresh.run();
    });
    Button rerun = new Button("Re-run");
    rerun.setOnAction(e -> refresh.run());

    TitledPane lostPane = new TitledPane("Lost images (referenced but missing)",
        new VBox(6, lostList, new HBox(8, openOwner)));
    lostPane.setCollapsible(false);
    TitledPane orphanPane = new TitledPane("Orphaned images (on disk, unreferenced)",
        new VBox(6, orphanList, new HBox(8, deleteOrphans)));
    orphanPane.setCollapsible(false);
    VBox.setVgrow(lostList, Priority.ALWAYS);
    VBox.setVgrow(orphanList, Priority.ALWAYS);

    HBox bottom = new HBox(8, summary, new javafx.scene.layout.Region(), rerun);
    HBox.setHgrow(bottom.getChildren().get(1), Priority.ALWAYS);
    bottom.setPadding(new Insets(8));

    VBox center = new VBox(8, lostPane, orphanPane);
    center.setPadding(new Insets(8));
    BorderPane rootPane = new BorderPane(center);
    rootPane.setBottom(bottom);
    stage.setScene(new Scene(rootPane, 640, 560));
    refresh.run();
    stage.show();
  }

  private static String rel(File root, File f) {
    try {
      return root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
    } catch (Exception e) {
      return f.getAbsolutePath();
    }
  }
}
