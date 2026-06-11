/**
 * MIT License
 * 
 * Copyright (C) 2021 - 2025 by Adama Platform Engineering, LLC
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package mg.tokens;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
