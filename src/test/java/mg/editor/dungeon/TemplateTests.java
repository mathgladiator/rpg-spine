package mg.editor.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

public class TemplateTests {

  @Test
  public void builtinsExist() {
    assertFalse(Template.builtins().isEmpty());
  }

  @Test
  public void triStateRoundTrip() throws Exception {
    File f = File.createTempFile("stamp", ".template");
    f.deleteOnExit();

    Template t = new Template("ledge", 4, 3);
    t.cells[0][0] = Template.WALL;
    t.cells[1][1] = Template.OPEN;
    t.cells[2][2] = Template.SKIP; // default, but explicit
    t.cells[3][0] = Template.WALL;
    t.save(f);

    Template back = Template.load(f);
    assertEquals("ledge", back.name);
    assertEquals(4, back.width);
    assertEquals(3, back.height);
    assertEquals(Template.WALL, back.cells[0][0]);
    assertEquals(Template.OPEN, back.cells[1][1]);
    assertEquals(Template.SKIP, back.cells[2][2]);
    assertEquals(Template.WALL, back.cells[3][0]);
    Files.deleteIfExists(f.toPath());
  }

  @Test
  public void brushShapesSelectCells() {
    // square includes corners; circle/diamond exclude the far corner at r=1
    assertTrue(mg.editor.TemplateEditor.inBrush(1, 1, 1, "square"));
    assertFalse(mg.editor.TemplateEditor.inBrush(1, 1, 1, "diamond"));
    assertFalse(mg.editor.TemplateEditor.inBrush(1, 1, 1, "circle"));
    assertTrue(mg.editor.TemplateEditor.inBrush(0, 1, 1, "diamond"));
    assertTrue(mg.editor.TemplateEditor.inBrush(0, 0, 0, "square")); // size-1 brush = just center
  }
}
