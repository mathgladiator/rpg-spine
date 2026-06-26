package mg.editor;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;
import mg.codegen.Compiler;

import java.io.File;

/**
 * The codegen status screen. Runs {@link Compiler} on a background thread and
 * streams its progress into a scrolling log, ending with a pass/fail summary. The
 * output directory is the project-relative {@code outputDir} from
 * {@link ProjectSettings} (edit it in Settings ▸ Project Settings…).
 */
public final class CompilerWindow {
  private CompilerWindow() {}

  public static void open(Window owner, File root) {
    if (root == null) {
      return;
    }
    Stage stage = new Stage();
    stage.initOwner(owner);
    stage.setTitle("Compile — C codegen");

    TextArea log = new TextArea();
    log.setEditable(false);
    log.setWrapText(false);
    log.setFont(Font.font("Monospaced", 12));

    Label out = new Label("output: " + ProjectSettings.current().outputDir + "/  (relative to project root)");
    Button run = new Button("Compile");
    HBox top = new HBox(10, out, grow(), run);
    top.setAlignment(Pos.CENTER_LEFT);
    top.setPadding(new Insets(8));

    Label statusLabel = new Label("ready");
    Button close = new Button("Close");
    close.setOnAction(e -> stage.close());
    HBox bottom = new HBox(10, statusLabel, grow(), close);
    bottom.setAlignment(Pos.CENTER_LEFT);
    bottom.setPadding(new Insets(8));

    run.setOnAction(e -> {
      run.setDisable(true);
      log.clear();
      statusLabel.setText("compiling…");
      statusLabel.setStyle("");
      Thread t = new Thread(() -> {
        Compiler.Result r = Compiler.run(root, ProjectSettings.current(),
            line -> Platform.runLater(() -> log.appendText(line + "\n")));
        Platform.runLater(() -> {
          statusLabel.setText(r.ok()
              ? "✓ done — " + r.written.size() + " file(s) written, " + r.warnings + " warning(s)"
              : "✗ failed — " + r.errors + " error(s), " + r.warnings + " warning(s)");
          statusLabel.setStyle(r.ok() ? "-fx-text-fill:#2e7d32;" : "-fx-text-fill:#c62828;");
          run.setDisable(false);
        });
      }, "spine-codegen");
      t.setDaemon(true);
      t.start();
    });

    BorderPane pane = new BorderPane(log);
    pane.setTop(top);
    pane.setBottom(bottom);
    stage.setScene(new Scene(pane, 760, 540));
    stage.show();
  }

  private static Region grow() {
    Region r = new Region();
    HBox.setHgrow(r, Priority.ALWAYS);
    return r;
  }
}
