package mg.tree;

import java.util.ArrayList;

/** root document */
public class Root {
  public final ArrayList<Field> fields;
  public final ArrayList<Struct> structs;
  /**
   * Named effects declared with {@code effect <code>: <name>;}. Each registers a
   * side effect a {@code .story} (or other content) may invoke; the {@code code} is
   * the stable dispatch id compiled content stores (so effects can be renamed). At
   * codegen each maps to {@code void effect_<name>(SPINE* doc, int param)}, reached
   * via a generated {@code story_effect} dispatch keyed by the code. See
   * {@code documents/story.vm.md} and {@code documents/STORYBIN_FORMAT.md}.
   */
  public final ArrayList<Effect> effects;

  public Root() {
    this.fields = new ArrayList<>();
    this.structs = new ArrayList<>();
    this.effects = new ArrayList<>();
  }
}
