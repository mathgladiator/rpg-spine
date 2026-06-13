package mg;

import mg.tree.Field;
import mg.tree.Parser;
import mg.tree.Root;
import mg.tree.Struct;

import java.io.File;
import java.nio.file.Files;

public class Main {
  public static void main(String[] args) throws Exception{
    // No arguments means the jar was launched on its own (e.g. double-clicked on
    // Windows). Open the editor and let the user pick a content folder.
    if (args.length == 0) {
      mg.editor.EditorLauncher.openChooser();
      return;
    }

    Root root = new Root();
    String output = null;
    String symbols = null;
    String editorDir = null;
    for (int k = 0; k < args.length; k++) {
      String arg = args[k];
      if (k + 1 < args.length) {
        if ("--input".equals(arg) || "-i".equals(arg)) {
          String name = args[k + 1];
          Parser.merge_string(root, name, Files.readString(new File(name).toPath()));
        }
        if ("--output".equals(arg) || "-o".equals(arg)) {
          output = args[k + 1];
        }
        if ("--symbols".equals(arg) || "-s".equals(arg)) {
          symbols = args[k + 1];;
        }
        if ("--editor".equals(arg) || "-e".equals(arg)) {
          editorDir = args[k + 1];
        }
      }
    }

    // The visual editor takes over the process when requested. It validates the
    // target directory itself and hands off to the JavaFX application thread.
    if (editorDir != null) {
      File dir = new File(editorDir);
      if (!dir.exists()) {
        System.err.println("--editor: directory does not exist: " + dir.getAbsolutePath());
        System.exit(2);
      }
      if (!dir.isDirectory()) {
        System.err.println("--editor: not a directory: " + dir.getAbsolutePath());
        System.exit(2);
      }
      mg.editor.EditorLauncher.open(dir);
      return;
    }
    if (output == null) {
      System.out.println("no output; showing debug information");
      System.out.println("symbol table:" + symbols);
      for (Struct struct : root.structs) {
        System.out.println("struct:" + struct.name);
        for (Field field : struct.fields) {
          System.out.println("--" + field.name);
        }
      }
      System.out.println("root:");
      for (Field field : root.fields) {
        System.out.println("-" + field.name);
      }
    }
  }
}