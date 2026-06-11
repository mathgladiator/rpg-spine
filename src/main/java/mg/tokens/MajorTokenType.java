package mg.tokens;

/** defines the type of a token found within a file */
public enum MajorTokenType {
  /** a comment which be a block /* or // */
  Comment(true),
  /** identifiers: [A-Za-z_][A-Za-z_0-9]* */
  Identifier(false),
  /** a number (either integeral or floating point) */
  NumberLiteral(false),
  /** a double quoted string literal */
  StringLiteral(false),
  /** a single symbol: '+', '-' */
  Symbol(false),
  /** whitespace like spaces (' '), tabs ('\t'), or newlines (\'n') */
  Whitespace(true);
  public final boolean hidden;

  MajorTokenType(final boolean hidden) {
    this.hidden = hidden;
  }
}
