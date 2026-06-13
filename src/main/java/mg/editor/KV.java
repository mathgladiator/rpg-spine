package mg.editor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tiny line-oriented {@code key=value} format shared by the .dungeon and
 * .world serializers. Values may be bare tokens or double-quoted strings (with
 * \" and \\ escapes). Designed to be trivially regenerated/ingested by the C
 * codegen later, and friendly to hand editing and version control diffs.
 *
 * A line looks like:  {@code record key=val key2="a quoted val" flag}
 * where the first token is the record verb and the rest are attributes.
 */
public final class KV {
  public String verb = "";
  public final Map<String, String> attrs = new LinkedHashMap<>();

  /** parse a single line into a verb + attributes; returns null for blank/comment lines. */
  public static KV parse(String line) {
    String trimmed = line.strip();
    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
      return null;
    }
    KV kv = new KV();
    int i = 0;
    boolean first = true;
    StringBuilder tok = new StringBuilder();
    int n = trimmed.length();
    while (i <= n) {
      char c = i < n ? trimmed.charAt(i) : ' ';
      if (c == '"') {
        i++;
        while (i < n) {
          char q = trimmed.charAt(i);
          if (q == '\\' && i + 1 < n) {
            tok.append(trimmed.charAt(i + 1));
            i += 2;
            continue;
          }
          if (q == '"') {
            i++;
            break;
          }
          tok.append(q);
          i++;
        }
        continue;
      }
      if (Character.isWhitespace(c)) {
        if (tok.length() > 0) {
          String t = tok.toString();
          tok.setLength(0);
          if (first) {
            kv.verb = t;
            first = false;
          } else {
            int eq = t.indexOf('=');
            if (eq >= 0) {
              kv.attrs.put(t.substring(0, eq), t.substring(eq + 1));
            } else {
              kv.attrs.put(t, "true");
            }
          }
        }
        i++;
        continue;
      }
      // a quoted value that follows a key= : key="..." — handle key, then quote
      if (c == '=' && tok.length() > 0 && i + 1 < n && trimmed.charAt(i + 1) == '"') {
        // emit key= prefix then let the quote branch capture the value
        String key = tok.toString();
        tok.setLength(0);
        i++; // consume '='
        i++; // consume opening quote
        StringBuilder val = new StringBuilder();
        while (i < n) {
          char q = trimmed.charAt(i);
          if (q == '\\' && i + 1 < n) {
            val.append(trimmed.charAt(i + 1));
            i += 2;
            continue;
          }
          if (q == '"') {
            i++;
            break;
          }
          val.append(q);
          i++;
        }
        kv.attrs.put(key, val.toString());
        first = false;
        continue;
      }
      tok.append(c);
      i++;
    }
    return kv;
  }

  public String get(String key, String dflt) {
    return attrs.getOrDefault(key, dflt);
  }

  public int getInt(String key, int dflt) {
    try {
      return Integer.parseInt(attrs.get(key));
    } catch (Exception e) {
      return dflt;
    }
  }

  public boolean getBool(String key, boolean dflt) {
    String v = attrs.get(key);
    return v == null ? dflt : v.equals("true") || v.equals("1");
  }

  /** quote a value if it contains whitespace, quotes, or is empty. */
  public static String q(String v) {
    if (v == null) {
      return "\"\"";
    }
    boolean needs = v.isEmpty();
    for (int i = 0; i < v.length() && !needs; i++) {
      char c = v.charAt(i);
      if (Character.isWhitespace(c) || c == '"' || c == '#' || c == '=') {
        needs = true;
      }
    }
    if (!needs) {
      return v;
    }
    return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
