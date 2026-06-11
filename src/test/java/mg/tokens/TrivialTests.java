package mg.tokens;

import org.junit.Assert;
import org.junit.Test;

public class TrivialTests {
  @Test
  public void  major_token_type() {
    Assert.assertEquals(MajorTokenType.Whitespace, MajorTokenType.valueOf("Whitespace"));
  }

  @Test
  public void  minor_token_type() {
    Assert.assertEquals(MinorTokenType.NumberIsDouble, MinorTokenType.valueOf("NumberIsDouble"));
  }

  @Test
  public void exception() {
    SpineLangException e = new SpineLangException("zero", DocumentPosition.ZERO);
  }
}
