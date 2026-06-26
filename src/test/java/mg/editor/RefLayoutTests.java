package mg.editor;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.junit.Test;

public class RefLayoutTests {

  @Test
  public void perFileRefAndExtFolders() {
    File monster = new File("content/goblin.monster");
    assertEquals("goblin", RefLayout.base(monster));
    assertEquals(new File("content", "goblin.ref"), RefLayout.refDir(monster));
    assertEquals(new File("content", "goblin.ext"), RefLayout.extDir(monster));
    assertEquals("goblin.ext", RefLayout.extName(monster));

    File story = new File("levels/vault.story");
    assertEquals(new File("levels", "vault.ref"), RefLayout.refDir(story));
    assertEquals(new File("levels", "vault.ext"), RefLayout.extDir(story));
  }

  @Test
  public void dottedNameKeepsOnlyLastExtension() {
    File f = new File("a/my.cool.item");
    assertEquals("my.cool", RefLayout.base(f));
    assertEquals(new File("a", "my.cool.ref"), RefLayout.refDir(f));
  }
}
