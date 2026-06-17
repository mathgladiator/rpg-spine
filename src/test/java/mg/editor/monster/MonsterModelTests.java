package mg.editor.monster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

public class MonsterModelTests {

  @Test
  public void artAndAnimationsRoundTrip() throws Exception {
    File f = File.createTempFile("goblin", ".monster");
    f.deleteOnExit();

    Monster m = Monster.blank("goblin");
    m.references.add("goblin.ref.png");
    m.battleStance = "goblin-stance.png";
    m.battleDamage = "goblin-hurt.png";
    m.battleAttack.fps = 12;
    m.battleAttack.loop = 1;
    m.battleAttack.frames.addAll(List.of("atk1.png", "atk2.png", "atk3.png"));
    m.dungeonWalk.get("left").frames.addAll(List.of("wl1.png", "wl2.png"));
    m.dungeonWalk.get("left").fps = 8;
    m.dungeonIdle.get("towards").frames.add("idle.png");
    m.save(f);

    Monster back = Monster.load(f);
    assertEquals(List.of("goblin.ref.png"), back.references);
    assertEquals("goblin-stance.png", back.battleStance);
    assertEquals("goblin-hurt.png", back.battleDamage);
    assertEquals(12, back.battleAttack.fps);
    assertEquals(1, back.battleAttack.loop);
    assertEquals(List.of("atk1.png", "atk2.png", "atk3.png"), back.battleAttack.frames);
    assertEquals(List.of("wl1.png", "wl2.png"), back.dungeonWalk.get("left").frames);
    assertEquals(8, back.dungeonWalk.get("left").fps);
    assertEquals(List.of("idle.png"), back.dungeonIdle.get("towards").frames);
    assertTrue(back.dungeonWalk.get("away").frames.isEmpty());
    Files.deleteIfExists(f.toPath());
  }

  @Test
  public void skeletonCacheRoundTrips() throws Exception {
    File f = File.createTempFile("goblin", ".monster");
    f.deleteOnExit();

    Monster m = Monster.blank("goblin");
    mg.editor.asset.SkeletonData s = new mg.editor.asset.SkeletonData();
    s.direction = "left";
    double[][] frame = new double[mg.editor.asset.SkeletonData.JOINTS][2];
    frame[0][0] = 0.5;
    frame[0][1] = 0.12;
    frame[16][0] = 0.44;   // LEFT LEG
    frame[16][1] = 0.92;
    s.frames.add(frame);
    m.skeletons.put("ref/goblin.ref.png", s);
    m.save(f);

    Monster back = Monster.load(f);
    mg.editor.asset.SkeletonData r = back.skeletons.get("ref/goblin.ref.png");
    assertEquals("left", r.direction);
    assertEquals(1, r.frames.size());
    assertEquals(0.5, r.frames.get(0)[0][0], 0.0005);
    assertEquals(0.92, r.frames.get(0)[16][1], 0.0005);
    Files.deleteIfExists(f.toPath());
  }

  @Test
  public void extractSettingsRoundTrip() throws Exception {
    File f = File.createTempFile("goblin", ".monster");
    f.deleteOnExit();

    Monster m = Monster.blank("goblin");
    m.extract.used = true;
    m.extract.width = 48;
    m.extract.height = 48;
    m.extract.algo = "ATKINSON";
    m.extract.threshold = 100;
    m.extract.alpha = "TRANSPARENT";
    m.extract.background = "edge scan";
    m.save(f);

    Monster back = Monster.load(f);
    assertTrue(back.extract.used);
    assertEquals(48, back.extract.width);
    assertEquals("ATKINSON", back.extract.algo);
    assertEquals(100, back.extract.threshold);
    assertEquals("TRANSPARENT", back.extract.alpha);
    assertEquals("edge scan", back.extract.background);
    Files.deleteIfExists(f.toPath());
  }
}
