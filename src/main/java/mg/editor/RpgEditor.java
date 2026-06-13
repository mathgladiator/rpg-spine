package mg.editor;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.util.Duration;
import mg.tokens.DocumentPosition;
import mg.tokens.SpineLangException;
import mg.tree.Field;
import mg.tree.Parser;
import mg.tree.Root;
import mg.tree.Struct;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A text editor for .rpg schema files that runs the project's own parser on
 * every (debounced) edit and reports parse + semantic diagnostics on the right.
 */
public class RpgEditor extends PlainTextEditor {
  private final ListView<String> diagnostics = new ListView<>();
  private final Label summary = new Label();
  private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
  private final BorderPane node = new BorderPane();

  public RpgEditor(File file, Label status) throws Exception {
    super(file, status);
    text.setFont(Font.font("monospaced", 14));
    diagnostics.setPrefWidth(360);
    summary.setPadding(new Insets(6, 8, 6, 8));

    BorderPane right = new BorderPane(diagnostics);
    Label header = new Label("Diagnostics");
    header.setPadding(new Insets(6, 8, 6, 8));
    right.setTop(header);
    right.setBottom(summary);

    SplitPane split = new SplitPane(text, right);
    split.setDividerPositions(0.66);
    node.setCenter(split);

    debounce.setOnFinished(e -> validate());
    validate();
  }

  @Override
  protected void onTextChanged(String now) {
    debounce.playFromStart();
  }

  @Override
  public Node getNode() {
    return node;
  }

  @Override
  public String title() {
    return "rpg schema";
  }

  private void validate() {
    List<String> problems = new ArrayList<>();
    Root root = new Root();
    try {
      Parser.merge_string(root, file.getName(), text.getText());
      problems.addAll(semanticChecks(root));
    } catch (SpineLangException ex) {
      problems.add(formatError(ex));
    } catch (Exception ex) {
      problems.add("✖ " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }

    diagnostics.getItems().setAll(problems.isEmpty()
        ? List.of("✓ no problems found")
        : problems);

    int fieldCount = root.fields.size();
    int structFieldCount = 0;
    for (Struct s : root.structs) {
      structFieldCount += s.fields.size();
    }
    summary.setText(root.structs.size() + " struct(s), "
        + fieldCount + " root field(s), "
        + structFieldCount + " struct field(s)");
    summary.setTextFill(problems.isEmpty() ? Color.web("#2e7d32") : Color.web("#b71c1c"));
  }

  private String formatError(SpineLangException ex) {
    DocumentPosition p = ex.position;
    if (p != null) {
      return "✖ line " + (p.getStartLine() + 1) + ":" + (p.getStartChar() + 1)
          + " — " + ex.getMessage();
    }
    return "✖ " + ex.getMessage();
  }

  /**
   * Field codes must be unique across the root document and every struct (see
   * README milestone 1). Flag collisions here so the schema author catches them
   * before the C codegen does.
   */
  private List<String> semanticChecks(Root root) {
    List<String> out = new ArrayList<>();
    Map<Integer, String> codes = new HashMap<>();
    for (Field f : root.fields) {
      checkCode(codes, f.code, "root." + f.name, out);
    }
    for (Struct s : root.structs) {
      for (Field f : s.fields) {
        checkCode(codes, f.code, s.name + "." + f.name, out);
      }
    }
    return out;
  }

  private void checkCode(Map<Integer, String> codes, int code, String where, List<String> out) {
    String prior = codes.put(code, where);
    if (prior != null) {
      out.add("✖ duplicate field code " + code + " used by " + prior + " and " + where);
    }
  }
}
