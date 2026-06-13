package mg.editor;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.text.Font;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** A no-frills monospace text editor used for files without a richer editor. */
public class PlainTextEditor implements Editor {
  protected final File file;
  protected final Label status;
  protected final TextArea text = new TextArea();
  private boolean dirty;

  public PlainTextEditor(File file, Label status) throws Exception {
    this.file = file;
    this.status = status;
    text.setFont(Font.font("monospaced", 13));
    if (file.exists()) {
      text.setText(Files.readString(file.toPath()));
    }
    text.textProperty().addListener((o, was, now) -> {
      dirty = true;
      onTextChanged(now);
    });
    dirty = false;
  }

  /** hook for subclasses (validation etc.); default is a no-op. */
  protected void onTextChanged(String now) {}

  @Override
  public Node getNode() {
    return text;
  }

  @Override
  public void save() throws Exception {
    Files.write(file.toPath(), text.getText().getBytes(StandardCharsets.UTF_8));
    dirty = false;
  }

  @Override
  public boolean isDirty() {
    return dirty;
  }

  @Override
  public String title() {
    return "text";
  }
}
