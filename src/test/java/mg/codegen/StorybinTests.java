package mg.codegen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Test;

import mg.assets.BwCodec;
import mg.assets.Mono;
import mg.editor.story.Story;

public class StorybinTests {

  /** a deterministic 400x240 1-bit image (a few black pixels on white). */
  private static File writeScene(File dir, String name, int seed) throws Exception {
    BufferedImage img = Mono.newBinary(400, 240);
    for (int y = 0; y < 240; y++) {
      for (int x = 0; x < 400; x++) {
        Mono.set(img, x, y, ((x + y * 3 + seed) % 7) == 0);
      }
    }
    File f = new File(dir, name);
    Mono.savePng(img, f);
    return f;
  }

  @Test
  public void roundTripsStoryWithImagesEffectsAndChoices() throws Exception {
    File dir = Files.createTempDirectory("storybin").toFile();
    File scene = writeScene(dir, "scene.png", 0);
    writeScene(dir, "reward.png", 4);

    Story s = new Story();
    s.id = "t";
    s.name = "T";
    s.start = "a";

    Story.Node a = new Story.Node();
    a.id = "a"; a.kind = Story.Kind.BEAT; a.text = "hello"; a.image = "scene.png"; a.next = "c";
    a.onEnter.add(new Story.Effect("kill_player", 7));
    a.onEnter.add(new Story.Effect("give_crown", 1));

    Story.Node c = new Story.Node();
    c.id = "c"; c.kind = Story.Kind.CHOICE; c.text = "pick";
    Story.Choice go = new Story.Choice(); go.text = "go"; go.to = "d";
    Story.Choice stay = new Story.Choice(); stay.text = "stay"; stay.to = "";
    c.choices.add(go); c.choices.add(stay);

    Story.Node d = new Story.Node();
    d.id = "d"; d.kind = Story.Kind.OUTCOME; d.result = Story.Result.DIE;
    d.reason = "dead"; d.reward = "reward.png";

    s.nodes.add(a); s.nodes.add(c); s.nodes.add(d);

    Map<String, Integer> codes = Map.of("kill_player", 7, "give_crown", 1);
    byte[] bin = Storybin.compile(s, codes, dir);
    assertTrue("starts with STB1 magic", bin[0] == 'S' && bin[1] == 'T' && bin[2] == 'B' && bin[3] == '1');

    Storybin.Decoded dd = Storybin.decode(bin);
    assertEquals(0, dd.start);              // node "a" is index 0
    assertEquals(3, dd.nodes.size());

    // beat node
    Storybin.DNode na = dd.nodes.get(0);
    assertEquals(0, na.kind);
    assertEquals("hello", dd.string(na.text));
    assertEquals(2, na.fx.size());
    assertArrayEquals(new int[] {7, 7}, na.fx.get(0));   // {code, param}
    assertArrayEquals(new int[] {1, 1}, na.fx.get(1));
    assertEquals(1, na.next);                            // -> node "c"
    assertEquals(0, na.image);                           // first image in the bank

    // choice node
    Storybin.DNode nc = dd.nodes.get(1);
    assertEquals(1, nc.kind);
    assertEquals("pick", dd.string(nc.text));
    assertEquals(2, nc.decisions.size());
    assertEquals("go", dd.string(nc.decisions.get(0)[0]));
    assertEquals(2, nc.decisions.get(0)[1]);            // "go" -> node "d"
    assertEquals("stay", dd.string(nc.decisions.get(1)[0]));
    assertEquals(Storybin.NONE, nc.decisions.get(1)[1]); // "stay" falls through

    // outcome node
    Storybin.DNode nd = dd.nodes.get(2);
    assertEquals(2, nd.kind);
    assertEquals(1, nd.result);                          // die
    assertEquals("dead", dd.string(nd.reason));
    assertEquals(1, nd.reward);                          // second image in the bank

    // images: two 400x240 banks, pixels match the source exactly
    assertEquals(2, dd.bank.anims.size());
    assertEquals(400, dd.bank.anims.get(0).width);
    assertEquals(240, dd.bank.anims.get(0).height);
    int[] expected = BwCodec.pixelsOf(Mono.load(scene));
    assertArrayEquals(expected, dd.bank.anims.get(0).pixels);
  }

  @Test
  public void undeclaredEffectFailsCompile() throws Exception {
    File dir = Files.createTempDirectory("storybin2").toFile();
    Story s = new Story();
    s.id = "t"; s.start = "a";
    Story.Node a = new Story.Node();
    a.id = "a"; a.kind = Story.Kind.OUTCOME; a.result = Story.Result.SURVIVE;
    a.onEnter.add(new Story.Effect("ghost", 0));
    s.nodes.add(a);
    try {
      Storybin.compile(s, Map.of(), dir);
      fail("expected an undeclared-effect failure");
    } catch (Exception ex) {
      assertTrue(ex.getMessage().contains("ghost"));
    }
  }

  @Test
  public void corruptionIsRejected() throws Exception {
    File dir = Files.createTempDirectory("storybin3").toFile();
    Story s = new Story();
    s.id = "t"; s.start = "a";
    Story.Node a = new Story.Node();
    a.id = "a"; a.kind = Story.Kind.OUTCOME; a.result = Story.Result.SURVIVE;
    s.nodes.add(a);
    byte[] bin = Storybin.compile(s, Map.of(), dir);
    bin[6] ^= 0xFF; // flip a byte in the body
    try {
      Storybin.decode(bin);
      fail("expected a crc mismatch");
    } catch (Exception ex) {
      assertTrue(ex.getMessage().toLowerCase().contains("crc"));
    }
  }
}
