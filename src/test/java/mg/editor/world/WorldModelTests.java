package mg.editor.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

public class WorldModelTests {

  @Test
  public void paletteObjectImageAndBoundaryRoundTrip() throws Exception {
    File f = File.createTempFile("overworld", ".world");
    f.deleteOnExit();

    World w = World.blank("overworld");
    w.palette.add("tiles/tree.png");
    w.palette.add("tiles/rock.png");

    World.SceneObject o = new World.SceneObject();
    o.id = "tree1";
    o.kind = "image";
    o.image = "tiles/tree.png";
    o.x = 120;
    o.y = 80;
    w.objects.add(o);

    World.Boundary b = new World.Boundary();
    b.id = "coast";
    b.points.add(new double[]{10, 10});
    b.points.add(new double[]{200, 10});
    b.points.add(new double[]{200, 150});
    w.boundaries.add(b);
    w.save(f);

    World back = World.load(f);
    assertEquals(List.of("tiles/tree.png", "tiles/rock.png"), back.palette);
    assertEquals(1, back.objects.size());
    assertEquals("tiles/tree.png", back.objects.get(0).image);
    assertEquals(1, back.boundaries.size());
    assertEquals(3, back.boundaries.get(0).points.size());
    assertEquals(200.0, back.boundaries.get(0).points.get(1)[0], 0.001);
    assertTrue(back.boundaries.get(0).id.equals("coast"));
    Files.deleteIfExists(f.toPath());
  }
}
