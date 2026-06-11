package mg.tree;

import java.util.ArrayList;

/** root document */
public class Root {
  public final ArrayList<Field> fields;
  public final ArrayList<Struct> structs;

  public Root() {
    this.fields = new ArrayList<>();
    this.structs = new ArrayList<>();
  }
}
