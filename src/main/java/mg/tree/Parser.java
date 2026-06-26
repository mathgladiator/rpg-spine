package mg.tree;

import mg.tokens.DocumentPosition;
import mg.tokens.SpineLangException;
import mg.tokens.Token;
import mg.tokens.TokenEngine;

/** the recursive parser */
public class Parser {
  public final Root root;
  public final TokenEngine tokens;

  private Parser(Root root, TokenEngine tokens) {
    this.root = root;
    this.tokens = tokens;
  }

  public static void merge_tokens(Root root, TokenEngine tokens) throws SpineLangException {
    Parser p = new Parser(root, tokens);
    p.root();
  }

  public static void merge_string(Root root, String name, String str) throws SpineLangException {
    merge_tokens(root, new TokenEngine(name, str.codePoints().iterator()));
  }

  private Token id() throws SpineLangException {
    Token id = tokens.pop();
    if (id == null) {
      throw new SpineLangException("Expected an identifier, got end of stream", tokens.position());
    }
    if (!id.isIdentifier()) {
      throw new SpineLangException("Expected an identifier", tokens.position());
    }
    return id;
  }

  private void expect(String symbol) throws SpineLangException {
    Token sym = tokens.pop();
    if (sym == null) {
      throw new SpineLangException("Expected a symbol, got end of stream", tokens.position());
    }
    if (!sym.isSymbolWithTextEq(symbol)) {
      throw new SpineLangException("Expected the symbol: " + symbol, tokens.position());
    }
  }

  private Field field(Token fieldCode) throws SpineLangException {
    DocumentPosition position = tokens.position();
    int code = Integer.parseInt(fieldCode.text);
    expect(":");
    Token modifier = id();
    Token type = null;
    final boolean is_private;
    if (modifier.isIdentifier("private", "public")) {
      is_private = modifier.isIdentifier("private");
      type = id();
    } else {
      is_private = false;
      type = modifier;
      modifier = null;
    }
    Token array_mod = tokens.popNextAdjSymbolPairIf((t) -> t.isSymbolWithTextEq("[]"));
    boolean is_array = array_mod != null;
    Token name = id();
    Token open_bracket = tokens.popIf((t) -> t.isSymbolWithTextEq("{"));
    if (open_bracket != null) {
      throw new SpineLangException("NOT YET IMPLEMENTED", tokens.position());
    } else {
      expect(";");
    }
    return new Field(position, code, is_private, type.text, is_array, name.text);
  }

  private void struct() throws SpineLangException {
    Token name = id();
    Struct struct = new Struct(tokens.position(), name.text);
    root.structs.add(struct);
    expect("{");
    while (true) {
      Token detect = tokens.pop();
      if (detect == null) {
        throw new SpineLangException("Expected a symbol, got end of stream", tokens.position());
      }
      if (detect.isNumberLiteralInteger()) {
        struct.fields.add(field(detect));
      } else if (detect.isSymbolWithTextEq("}")) {
        return;
      } else {
        throw new SpineLangException("unexpected token in struct", tokens.position());
      }
    }
  }

  private boolean one_line() throws SpineLangException {
    Token detectField = tokens.popIf((t) -> t.isNumberLiteralInteger());
    if (detectField != null) {
      root.fields.add(field(detectField));
      return true;
    }
    Token detectStruct = tokens.popIf((t) -> t.isIdentifier("struct"));
    if (detectStruct != null) {
      struct(); // throw the struct keyword away
      return true;
    }
    Token detectEffect = tokens.popIf((t) -> t.isIdentifier("effect"));
    if (detectEffect != null) {
      Token name = id(); // the effect's name
      expect(";");
      root.effects.add(name.text);
      return true;
    }
    return false;
  }

  private void root() throws SpineLangException {
    while (one_line()) ;
  }
}
