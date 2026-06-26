package mg.tree;

import java.util.ArrayList;

/** root document */
public class Root {
  public final ArrayList<Field> fields;
  public final ArrayList<Struct> structs;
  /**
   * Named effects declared with {@code effect <name>;}. Each registers a side
   * effect a {@code .story} (or other content) may invoke; at codegen it maps to a
   * C function taking the whole document plus an integer parameter
   * ({@code void effect_<name>(spine_t* doc, int param)}). See
   * {@code documents/story.vm.md}.
   */
  public final ArrayList<String> effects;

  public Root() {
    this.fields = new ArrayList<>();
    this.structs = new ArrayList<>();
    this.effects = new ArrayList<>();
  }
}
