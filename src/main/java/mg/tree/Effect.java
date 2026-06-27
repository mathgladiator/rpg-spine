package mg.tree;

import mg.tokens.DocumentPosition;

/**
 * A named side effect declared in a {@code .rpg} schema with a stable integer
 * <b>dispatch code</b>:
 *
 * <pre>effect 1: give_crown;</pre>
 *
 * The code — not the name and not the declaration order — is what compiled
 * content (a {@code .storybin}) stores, so an effect can be <em>renamed</em>
 * without breaking already-compiled stories: the link to the C engine is the
 * code. At codegen each effect maps to {@code void effect_<name>(SPINE* doc, int
 * param)} and is reached through a generated {@code story_effect} dispatch
 * keyed by this code.
 */
public class Effect {
  public final DocumentPosition position;
  public final int code;
  public final String name;

  public Effect(DocumentPosition position, int code, String name) {
    this.position = position;
    this.code = code;
    this.name = name;
  }
}
