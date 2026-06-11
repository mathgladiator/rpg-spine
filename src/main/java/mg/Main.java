package mg;

import mg.tree.Field;
import mg.tree.Parser;
import mg.tree.Root;
import mg.tree.Struct;

import java.io.File;
import java.nio.file.Files;

public class Main {
  public static void main(String[] args) throws Exception{
    Root root = new Root();
    String output = null;
    String symbols = null;
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
      }
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