package mg.tree;

import mg.tokens.DocumentPosition;

import java.util.ArrayList;

/** a struct is a collection of fields in a common name */
public class Struct {
  public final DocumentPosition position;
  public final String name;
  public final ArrayList<Field> fields;

  public Struct(DocumentPosition position, final String name) {
    this.position = position;
    this.name = name;
    this.fields = new ArrayList<>();
  }
}
