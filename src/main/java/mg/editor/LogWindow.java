package mg.editor;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The View ▸ Log tool window: a live list of the last {@link Log} entries with a
 * detail pane that shows the full message / stack trace of the selected entry.
 * A single instance is reused so re-opening just raises the window.
 */
public final class LogWindow {
  private static LogWindow instance;

  private final Stage stage = new Stage();
  private final ListView<Log.Entry> list = new ListView<>();
  private final TextArea detail = new TextArea();
  private final Runnable listener;

  private LogWindow(Window owner) {
    stage.initOwner(owner);
    stage.setTitle("Log — last 1000 actions");

    list.setCellFactory(v -> new ListCell<>() {
      @Override
      protected void updateItem(Log.Entry e, boolean empty) {
        super.updateItem(e, empty);
        if (empty || e == null) {
          setText(null);
          setTextFill(Color.BLACK);
        } else {
          setText(e.toString());
          setTextFill(switch (e.level()) {
            case ERROR -> Color.web("#b00020");
            case WARN -> Color.web("#9a6700");
            default -> Color.web("#333333");
          });
        }
      }
    });
    list.getSelectionModel().selectedItemProperty().addListener((o, was, now) ->
        detail.setText(now == null ? "" : describe(now)));

    detail.setEditable(false);
    detail.setStyle("-fx-font-family: monospace;");

    Button clear = new Button("Clear");
    clear.setOnAction(e -> Log.clear());
    Button refresh = new Button("Refresh");
    refresh.setOnAction(e -> refresh());
    HBox bar = new HBox(8, clear, refresh);
    bar.setPadding(new Insets(6));

    SplitPane split = new SplitPane(list, detail);
    split.setOrientation(javafx.geometry.Orientation.VERTICAL);
    split.setDividerPositions(0.6);

    BorderPane rootPane = new BorderPane(split);
    rootPane.setTop(bar);
    stage.setScene(new Scene(rootPane, 720, 560));

    listener = () -> Platform.runLater(this::refresh);
    Log.addListener(listener);
    stage.setOnHidden(e -> Log.removeListener(listener));
    refresh();
  }

  /** open the (single) log window, or raise it if already open. */
  public static void show(Window owner) {
    if (instance == null || !instance.stage.isShowing()) {
      instance = new LogWindow(owner);
    }
    instance.stage.show();
    instance.stage.toFront();
  }

  private void refresh() {
    var items = Log.snapshot();
    list.getItems().setAll(items);
    if (!items.isEmpty()) {
      list.scrollTo(items.size() - 1);
    }
  }

  private static String describe(Log.Entry e) {
    StringBuilder sb = new StringBuilder();
    sb.append(e.time()).append("  ").append(e.level()).append('\n');
    sb.append(e.message()).append('\n');
    if (e.detail() != null && !e.detail().isBlank()) {
      sb.append('\n').append(e.detail());
    }
    return sb.toString();
  }
}
