package mg.tokens;

/** an exception indicating a problem in understanding the language */
public class SpineLangException extends Exception {
  /** where in the document the problem was detected (may be null) */
  public final DocumentPosition position;

  public SpineLangException(final String message, DocumentPosition position) {
    super(message);
    this.position = position;
  }
}
