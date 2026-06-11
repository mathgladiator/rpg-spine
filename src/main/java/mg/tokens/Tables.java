package mg.tokens;

/** responsible for character tables which indicate membership within a regex */
public class Tables {
  public static final int BOOLEAN_TABLES_SIZE = 256;
  public static final boolean[] DIGITS_SCANNER = assembleBooleanTable("0123456789");
  public static final boolean[] DOUBLE_SCANNER = assembleBooleanTable("0123456789.eE-");
  public static final boolean[] HEX_SCANNER = assembleBooleanTable("0123456789xabcdefABCDEF");
  public static final boolean[] PART_IDENTIFIER_SCANNER = assembleBooleanTable("qwertyuiopasdfghjklzxcvbnm_QWERTYUIOPASDFGHJKLZXCVBNM0123456789");
  public static final boolean[] SINGLE_CHAR_ESCAPE_SCANNER = assembleBooleanTable("btnrf\"\\");
  public static final boolean[] START_IDENTIFIER_SCANNER = assembleBooleanTable("@#qwertyuiopasdfghjklzxcvbnm_QWERTYUIOPASDFGHJKLZXCVBNM");
  /** various scanner tables */
  public static final boolean[] SYMBOL_SCANNER = assembleBooleanTable("~!$%^&*()-=+[]{}|;:.,?/<>");
  public static final boolean[] WHITESPACE_SCANNER = assembleBooleanTable(" \t\n\r");

  private static boolean[] assembleBooleanTable(final String str) {
    final var table = new boolean[BOOLEAN_TABLES_SIZE];
    // force the array to be clear
    for (var k = 0; k < table.length; k++) {
      table[k] = false;
    }
    str.chars().forEach(x -> {
      table[x] = true;
    });
    return table;
  }
}
