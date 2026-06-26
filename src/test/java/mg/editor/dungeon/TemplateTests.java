package mg.editor.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

public class TemplateTests {

  @Test
  public void builtinsExistAndAreMacroAligned() {
    assertFalse(Template.builtins().isEmpty());
    for (Template t : Template.builtins()) {
      assertEquals(t.name + " width macro-aligned", 0, t.width % 5);
      assertEquals(t.name + " height macro-aligned", 0, t.height % 5);
    }
  }

  @Test
  public void macroSizedRoundTrip() throws Exception {
    File f = File.createTempFile("stamp", ".template");
    f.deleteOnExit();

    Template t = new Template("ledge", 2, 1); // 2×1 macro = 10×5 micro
    assertEquals(10, t.width);
    assertEquals(5, t.height);
    assertEquals(2, t.macroW());
    t.cells[0][0] = Template.WALL;
    t.cells[5][2] = Template.OPEN;
    t.cells[9][4] = Template.WALL;
    t.save(f);

    Template back = Template.load(f);
    assertEquals("ledge", back.name);
    assertEquals(10, back.width);
    assertEquals(5, back.height);
    assertEquals(Template.WALL, back.cells[0][0]);
    assertEquals(Template.OPEN, back.cells[5][2]);
    assertEquals(Template.WALL, back.cells[9][4]);
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
