package mg.tree;

import mg.tokens.DocumentPosition;

/** A field within either a root document or a structure */
public class Field {
  public final DocumentPosition position;
  public final int code;
  public final boolean is_private;
  public final boolean is_array;
  public final String type;
  public final String name;

  public Field(DocumentPosition position, int code, boolean is_private, String type, boolean is_array, String name) {
    this.position = position;
    this.code = code;
    this.is_private = is_private;
    this.type = type;
    this.is_array = is_array;
    this.name = name;
  }
}
