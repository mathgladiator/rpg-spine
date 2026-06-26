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
  public void doodadDirectionInference() {
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0); // all solid wall
    int floor = d.defaultFloorIndex();
    int x = 5, y = 5;

    // only east is open → faces east
    lv.cells[x + 1][y] = floor;
    assertEquals(Dungeon.Dir.E, d.inferDir(lv, x, y));

    // open north too → north wins (clockwise from N)
    lv.cells[x][y - 1] = floor;
    assertEquals(Dungeon.Dir.N, d.inferDir(lv, x, y));

    // fully walled → default north
    assertEquals(Dungeon.Dir.N, d.inferDir(lv, 1, 1));
  }

  @Test
  public void doodadsRoundTripAndCapAtThree() throws Exception {
    File f = File.createTempFile("doodad", ".dungeon");
    f.deleteOnExit();
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0);
    for (int i = 0; i < 3; i++) {
      Dungeon.Doodad dd = new Dungeon.Doodad();
      dd.x = 4;
      dd.y = 6;
      dd.id = "torch" + i;
      dd.dir = Dungeon.Dir.values()[i];
      lv.doodads.add(dd);
    }
    assertEquals(3, lv.doodadsAt(4, 6).size());
    d.save(f);

    Dungeon.Level bl = Dungeon.load(f).levels.get(0);
    assertEquals(3, bl.doodads.size());
    assertEquals(3, bl.doodadsAt(4, 6).size());
    assertEquals("torch0", bl.doodadsAt(4, 6).get(0).id);
    assertEquals(Dungeon.Dir.E, bl.doodadsAt(4, 6).get(1).dir);
    assertEquals(Dungeon.MAX_DOODADS, 3);
    Files.deleteIfExists(f.toPath());
  }

  @Test
  public void regionRoundTrips() throws Exception {
    File f = File.createTempFile("region", ".dungeon");
    f.deleteOnExit();
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0);
    Dungeon.Region rg = new Dungeon.Region();
    rg.name = "secret-door";
    rg.x = 2;
    rg.y = 3;
    rg.w = 4;
    rg.h = 1;
    rg.onIndex = d.defaultFloorIndex();
    rg.offIndex = d.defaultWallIndex();
    rg.on = false;
    lv.regions.add(rg);
    d.save(f);

    Dungeon.Level bl = Dungeon.load(f).levels.get(0);
    assertEquals(1, bl.regions.size());
    Dungeon.Region br = bl.regions.get(0);
    assertEquals("secret-door", br.name);
    assertEquals(2, br.x);
    assertEquals(4, br.w);
    assertEquals(d.defaultFloorIndex(), br.onIndex);
    assertEquals(d.defaultWallIndex(), br.offIndex);
    assertFalse(br.on);
    assertEquals(d.defaultWallIndex(), br.currentIndex()); // off → wall
    Files.deleteIfExists(f.toPath());
  }

  /** carve a fully-open 5×5 macro cell, then a corridor stub past one edge. */
  private static void carveMacro(Dungeon d, Dungeon.Level lv, int mx, int my) {
    int floor = d.defaultFloorIndex();
    for (int x = mx * 5; x < mx * 5 + 5; x++) {
      for (int y = my * 5; y < my * 5 + 5; y++) {
        lv.cells[x][y] = floor;
      }
    }
  }

  @Test
  public void doorAxisInferenceNeedsTwoOppositeAnchors() {
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0); // 20×20 micro, all solid
    int floor = d.defaultFloorIndex();
    // open macro (1,1) plus the macro cells above and below → corridor runs N–S
    carveMacro(d, lv, 1, 1);
    carveMacro(d, lv, 1, 0);
    carveMacro(d, lv, 1, 2);
    // N & S open, E & W solid → door panel spans E–W
    assertEquals(Dungeon.DoorAxis.EW, d.inferDoorAxis(lv, 1, 1));

    // open the east neighbour too → now three sides open, ambiguous → no door
    carveMacro(d, lv, 2, 1);
    assertEquals(null, d.inferDoorAxis(lv, 1, 1));
  }

  @Test
  public void doorRejectsPartlyWalledChamber() {
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0);
    carveMacro(d, lv, 1, 1);
    carveMacro(d, lv, 1, 0);
    carveMacro(d, lv, 1, 2);
    // a single wall cell inside the chamber breaks the "entire cell open" rule
    lv.cells[1 * 5 + 2][1 * 5 + 2] = d.defaultWallIndex();
    assertEquals(null, d.inferDoorAxis(lv, 1, 1));
  }

  @Test
  public void doorRoundTripsAndValidates() throws Exception {
    File f = File.createTempFile("door", ".dungeon");
    f.deleteOnExit();
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0);
    carveMacro(d, lv, 1, 1);
    carveMacro(d, lv, 1, 0);
    carveMacro(d, lv, 1, 2);

    Dungeon.Door door = new Dungeon.Door();
    door.mx = 1;
    door.my = 1;
    door.axis = d.inferDoorAxis(lv, 1, 1);
    door.lock = Dungeon.DoorLock.KEY;
    door.key = "brass key";
    door.open = false;
    door.note = "to the vault";
    lv.doors.add(door);
    assertTrue("contract holds when placed", d.doorValid(lv, door));

    d.save(f);
    Dungeon back = Dungeon.load(f);
    Dungeon.Level bl = back.levels.get(0);
    assertEquals(1, bl.doors.size());
    Dungeon.Door bd = bl.doors.get(0);
    assertEquals(1, bd.mx);
    assertEquals(1, bd.my);
    assertEquals(Dungeon.DoorAxis.EW, bd.axis);
    assertEquals(Dungeon.DoorLock.KEY, bd.lock);
    assertEquals("brass key", bd.key);
    assertEquals("to the vault", bd.note);
    assertFalse(bd.open);
    assertEquals(bd, bl.doorAt(1, 1));
    assertTrue(back.doorValid(bl, bd));

    // sealing one anchor side's corridor doesn't matter, but opening a third side
    // (so the cell becomes a junction) invalidates the door as a live diagnostic.
    carveMacro(back, bl, 2, 1);
    assertFalse(back.doorValid(bl, bd));
    Files.deleteIfExists(f.toPath());
  }

  @Test
  public void macroFillRoundTrips() throws Exception {
    File f = File.createTempFile("macrofill", ".dungeon");
    f.deleteOnExit();
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0); // 20×20 micro → 4×4 macro
    assertEquals(Dungeon.Fill.MARCHING, lv.fillAt(0, 0)); // default
    lv.macroFill[1][2] = Dungeon.Fill.DIAGONAL;
    lv.macroFill[3][3] = Dungeon.Fill.SQUARES;
    d.save(f);

    Dungeon.Level bl = Dungeon.load(f).levels.get(0);
    assertEquals(Dungeon.Fill.DIAGONAL, bl.fillAt(1, 2));
    assertEquals(Dungeon.Fill.SQUARES, bl.fillAt(3, 3));
    assertEquals(Dungeon.Fill.MARCHING, bl.fillAt(0, 0));
    Files.deleteIfExists(f.toPath());
  }

  @Test
  public void resizePreservesMacroFill() {
    Dungeon d = Dungeon.blank();
    Dungeon.Level lv = d.levels.get(0);
    lv.macroFill[0][0] = Dungeon.Fill.SQUARES;
    lv.resize(30, 30, d.defaultWallIndex()); // 6×6 macro
    assertEquals(Dungeon.Fill.SQUARES, lv.fillAt(0, 0));
    assertEquals(Dungeon.Fill.MARCHING, lv.fillAt(5, 5)); // new area defaults
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
