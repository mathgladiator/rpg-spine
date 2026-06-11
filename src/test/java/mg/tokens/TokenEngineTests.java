package mg.tokens;

import org.junit.Assert;
import org.junit.Test;

public class TokenEngineTests {
  @Test
  public void root_field() throws Exception {
    TokenEngine te = new TokenEngine("test_case", "100: int field;".codePoints().iterator());
    {
      Token t = te.pop();
      Assert.assertEquals("100", t.text);
      Assert.assertEquals(MajorTokenType.NumberLiteral, t.majorType);
      Assert.assertEquals(MinorTokenType.NumberIsInteger, t.minorType);
      Assert.assertTrue(t.isNumberLiteral());
      Assert.assertTrue(t.isNumberLiteralInteger());
    }
    {
      Token t = te.pop();
      Assert.assertEquals(":", t.text);
      Assert.assertEquals(MajorTokenType.Symbol, t.majorType);
      Assert.assertTrue(t.isSymbol());
    }
    {
      Token t = te.pop();
      Assert.assertEquals("int", t.text);
      Assert.assertEquals(MajorTokenType.Identifier, t.majorType);
    }
    {
      Token t = te.pop();
      Assert.assertEquals("field", t.text);
      Assert.assertEquals(MajorTokenType.Identifier, t.majorType);
    }
    {
      Token t = te.pop();
      Assert.assertEquals(";", t.text);
      Assert.assertEquals(MajorTokenType.Symbol, t.majorType);
      Assert.assertTrue(t.isSymbol());
    }
    Assert.assertNull(te.pop());
    Assert.assertNull(te.pop());
  }

  @Test
  public void string_literal_base() throws Exception{
    TokenEngine te = new TokenEngine("test_case", "\"123\"".codePoints().iterator());
    {
      Token t = te.pop();
      Assert.assertEquals("\"123\"", t.text);
      Assert.assertEquals(MajorTokenType.StringLiteral, t.majorType);
      Assert.assertTrue(t.isStringLiteral());
    }
    Assert.assertNull(te.pop());
  }

  @Test
  public void string_literal_escape_newline() throws Exception{
    TokenEngine te = new TokenEngine("test_case", "\"x\\nz\"".codePoints().iterator());
    {
      Token t = te.pop();
      Assert.assertEquals("\"x\\nz\"", t.text);
      Assert.assertEquals(MajorTokenType.StringLiteral, t.majorType);
      Assert.assertTrue(t.isStringLiteral());
    }
    Assert.assertNull(te.pop());
  }

  @Test
  public void string_literal_escape_quote() throws Exception{
    TokenEngine te = new TokenEngine("test_case", "\"x\\\"z\"".codePoints().iterator());
    {
      Token t = te.pop();
      Assert.assertEquals("\"x\\\"z\"", t.text);
      Assert.assertEquals(MajorTokenType.StringLiteral, t.majorType);
      Assert.assertTrue(t.isStringLiteral());
    }
    Assert.assertNull(te.pop());
  }

  @Test
  public void string_literal_escape_unicode() throws Exception{
    TokenEngine te = new TokenEngine("test_case", "\"x\\u1234z\"".codePoints().iterator());
    {
      Token t = te.pop();
      Assert.assertEquals("\"x\\u1234z\"", t.text);
      Assert.assertEquals(MajorTokenType.StringLiteral, t.majorType);
      Assert.assertTrue(t.isStringLiteral());
    }
    Assert.assertNull(te.pop());
  }

  @Test
  public void string_literal_escape_unknown() throws Exception{
    TokenEngine te = new TokenEngine("test_case", "\"x\\kz\"".codePoints().iterator());
    try {
      te.pop();
    } catch (SpineLangException e) {
      Assert.assertEquals("Unrecognized string escape value:107('k')", e.getMessage());
    }
  }

  @Test
  public void string_literal_escape_bad_unicode() throws Exception{
    TokenEngine te = new TokenEngine("test_case", "\"x\\uZZZZz\"".codePoints().iterator());
    try {
      te.pop();
    } catch (SpineLangException e) {
      Assert.assertEquals("Unrecognized hex value within the unicode escape value:90('Z')", e.getMessage());
    }
  }

  @Test
  public void single_line_comment() throws Exception {
    TokenEngine te = new TokenEngine("test_case", "// HELLO WORLD\nx".codePoints().iterator());

    Token t = te.pop();
    Assert.assertEquals("x", t.text);
    Assert.assertNotNull(t.nonSemanticTokensPrior);
    Assert.assertEquals("// HELLO WORLD\n", t.nonSemanticTokensPrior.get(0).text);
    Assert.assertEquals(MajorTokenType.Comment, t.nonSemanticTokensPrior.get(0).majorType);
  }

  @Test
  public void multiline_comment() throws Exception {
    TokenEngine te = new TokenEngine("test_case", "/* HELLO \n * WORLD*/\nx".codePoints().iterator());
    Token t = te.pop();
    Assert.assertEquals("x", t.text);
    Assert.assertNotNull(t.nonSemanticTokensPrior);
    Assert.assertEquals("/* HELLO \n * WORLD*/", t.nonSemanticTokensPrior.get(0).text);
    Assert.assertEquals("\n", t.nonSemanticTokensPrior.get(1).text);
    Assert.assertEquals(MajorTokenType.Comment, t.nonSemanticTokensPrior.get(0).majorType);
    Assert.assertEquals(MajorTokenType.Whitespace, t.nonSemanticTokensPrior.get(1).majorType);
  }
}
