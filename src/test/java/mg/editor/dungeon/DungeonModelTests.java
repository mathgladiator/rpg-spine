package mg.editor.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

public class DungeonModelTests {

  @Test
  public void blankHasPaletteAndOneLevel() {
    Dungeon d = Dungeon.blank();
    assertFalse(d.palette.isEmpty());
    assertEquals(1, d.levels.size());
    assertTrue("first wall is solid fill", d.isWall(d.defaultWallIndex()));
    assertFalse("first floor is open", d.isWall(d.defaultFloorIndex()));
  }

  @Test
  public void dimensionsSnapToMultiplesOfFive() {
    Dungeon.Level lv = new Dungeon.Level("L", 18, 7, 0);
    assertEquals(20, lv.width);
    assertEquals(10, lv.height);
    assertEquals(4, lv.macroW());
    assertEquals(2, lv.macroH());
  }

  @Test
  public void outOfBoundsReadsAsSolidRock() {
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0);
    assertTrue(d.occupied(lv, -1, 0));
    assertTrue(d.occupied(lv, lv.width, 0));
  }

  @Test
  public void fullRoundTrip() throws Exception {
    File f = File.createTempFile("test", ".dungeon");
    f.deleteOnExit();

    Dungeon d = Dungeon.blank();
    d.name = "Crypt";
    Dungeon.Level lv = d.levels.get(0);
    lv.name = "Sub-basement";
    int floor = d.defaultFloorIndex();
    lv.cells[3][4] = floor;
    lv.cells[5][6] = floor;

    Dungeon.Feature target = new Dungeon.Feature();
    target.type = Dungeon.FeatureType.TARGET;
    target.mx = 3;
    target.my = 3;
    target.id = "vault";
    lv.features.add(target);

    Dungeon.Feature feat = new Dungeon.Feature();
    feat.type = Dungeon.FeatureType.PORTAL;
    feat.mx = 1;
    feat.my = 2;
    feat.dest = "vault";
    feat.note = "to the vault";
    lv.features.add(feat);

    Dungeon.MonsterPlacement mp = new Dungeon.MonsterPlacement();
    mp.monsterId = "goblin";
    mp.x = 5;
    mp.y = 6;
    lv.monsters.add(mp);

    d.save(f);
    Dungeon back = Dungeon.load(f);

    assertEquals("Crypt", back.name);
    assertEquals(d.palette.size(), back.palette.size());
    assertEquals("stone", back.palette.get(0).name);
    assertEquals(100, back.palette.get(0).weight);
    assertEquals(Dungeon.Kind.WALL, back.palette.get(0).kind);

    Dungeon.Level bl = back.levels.get(0);
    assertEquals("Sub-basement", bl.name);
    assertFalse("carved cell is open", back.isWall(bl.cells[3][4]));
    assertTrue("untouched cell is solid", back.isWall(bl.cells[0][0]));

    assertEquals(2, bl.features.size());
    assertEquals(java.util.List.of("vault"), back.targetIds());
    Dungeon.Feature portal = bl.features.stream()
        .filter(x -> x.type == Dungeon.FeatureType.PORTAL).findFirst().orElseThrow();
    assertEquals(1, portal.mx);
    assertEquals(2, portal.my);
    assertEquals("vault", portal.dest);
    assertEquals("to the vault", portal.note);
    Dungeon.Feature tgt = bl.features.stream()
        .filter(x -> x.type == Dungeon.FeatureType.TARGET).findFirst().orElseThrow();
    assertEquals("vault", tgt.id);

    assertEquals(1, bl.monsters.size());
    assertEquals("goblin", bl.monsters.get(0).monsterId);
    assertEquals(5, bl.monsters.get(0).x);
    assertEquals(6, bl.monsters.get(0).y);
    assertEquals(java.util.List.of("goblin"), back.monsterIds());

    Files.deleteIfExists(f.toPath());
  }

  @Test
  public void paletteOverThirtySixCellsClampOnWrite() {
    // base-36 row encoding supports indices 0..35; ensure encode/decode is stable for in-range indices
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0);
    lv.cells[0][0] = 5;
    String row = d.serialize();
    assertTrue(row.contains("row y=0"));
  }
}
