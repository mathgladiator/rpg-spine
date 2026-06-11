package mg.tokens;

/** an exception indicating a problem in understanding the language */
public class SpineLangException extends Exception {
  public SpineLangException(final String message, DocumentPosition position) {
    super(message);
  }
}
