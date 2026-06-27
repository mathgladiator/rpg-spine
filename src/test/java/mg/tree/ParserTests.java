package mg.tree;

import mg.tokens.SpineLangException;
import org.junit.Assert;
import org.junit.Test;

public class ParserTests {

  @Test
  public void test_effects() throws SpineLangException {
    Root root = new Root();
    Parser.merge_string(root, "test",
        "10: long score; effect 7: kill_player; effect 9: grant_gold; struct S { 1: int a; }");
    Assert.assertEquals(2, root.effects.size());
    Assert.assertEquals(7, root.effects.get(0).code);
    Assert.assertEquals("kill_player", root.effects.get(0).name);
    Assert.assertEquals(9, root.effects.get(1).code);
    Assert.assertEquals("grant_gold", root.effects.get(1).name);
    Assert.assertEquals(1, root.fields.size());
    Assert.assertEquals(1, root.structs.size());
  }

  @Test
  public void test_empty() throws SpineLangException {
    Root root = new Root();
    Parser.merge_string(root, "test", "");
  }

  @Test
  public void test_fields() throws SpineLangException {
    Root root = new Root();
    Parser.merge_string(root, "test", "100: int name; 500: bool boss_alive; 1000: private int xp;");
    Assert.assertEquals(3, root.fields.size());

    Assert.assertEquals(100, root.fields.get(0).code);
    Assert.assertEquals(500, root.fields.get(1).code);
    Assert.assertEquals(1000, root.fields.get(2).code);

    Assert.assertEquals("int", root.fields.get(0).type);
    Assert.assertEquals("bool", root.fields.get(1).type);
    Assert.assertEquals("int", root.fields.get(2).type);

    Assert.assertEquals("name", root.fields.get(0).name);
    Assert.assertEquals("boss_alive", root.fields.get(1).name);
    Assert.assertEquals("xp", root.fields.get(2).name);

    Assert.assertFalse(root.fields.get(0).is_private);
    Assert.assertFalse(root.fields.get(1).is_private);
    Assert.assertTrue(root.fields.get(2).is_private);

    Assert.assertFalse(root.fields.get(0).is_array);
    Assert.assertFalse(root.fields.get(1).is_array);
    Assert.assertFalse(root.fields.get(2).is_array);
  }
}
