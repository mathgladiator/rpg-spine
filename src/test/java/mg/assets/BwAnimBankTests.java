package mg.assets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.junit.Test;

public class BwAnimBankTests {

  private static byte[] bytesOf(BwAnimBank bank) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    bank.write(bo);
    return bo.toByteArray();
  }

  private static BwAnimBank fromBytes(byte[] data) throws IOException {
    return BwAnimBank.read(new ByteArrayInputStream(data));
  }

  @Test
  public void magicIsFirstByte() throws IOException {
    BwAnimBank bank = new BwAnimBank();
    assertEquals(BwAnimBank.MAGIC, bytesOf(bank)[0] & 0xFF);
  }

  @Test
  public void roundTripStillImage() throws IOException {
    BufferedImage stance = Mono.blank(8, 8, false);
    Mono.set(stance, 2, 2, true);
    Mono.setTransparent(stance, 0, 0);

    BwAnimBank bank = new BwAnimBank();
    bank.anims.add(BwAnimBank.Anim.ofStrip(AnimType.BATTLE_STANCE, stance, 1, 0, 1));

    BwAnimBank back = fromBytes(bytesOf(bank));
    assertEquals(1, back.anims.size());
    BwAnimBank.Anim a = back.anims.get(0);
    assertEquals(AnimType.BATTLE_STANCE, a.type());
    assertEquals(1, a.cells);
    assertEquals(8, a.width);
    assertEquals(8, a.height);
    assertEquals(64, a.bits());
    BufferedImage img = a.toImage();
    assertEquals(Mono.BLACK, Mono.state(img, 2, 2));
    assertEquals(Mono.TRANSPARENT, Mono.state(img, 0, 0));
  }

  @Test
  public void roundTripMultiAnimationBank() throws IOException {
    BufferedImage walk = Mono.blank(24, 8, false); // 3 cells of 8 wide
    Mono.set(walk, 1, 1, true);
    Mono.set(walk, 9, 2, true);
    Mono.set(walk, 17, 3, true);

    BwAnimBank bank = new BwAnimBank();
    bank.anims.add(BwAnimBank.Anim.ofStrip(AnimType.BATTLE_STANCE, Mono.blank(8, 8, true), 1, 0, 1));
    bank.anims.add(BwAnimBank.Anim.ofStrip(AnimType.DUNGEON_WALK_TOWARDS, walk, 3, 120, 0));

    BwAnimBank back = fromBytes(bytesOf(bank));
    assertEquals(2, back.anims.size());

    BwAnimBank.Anim w = back.anims.get(1);
    assertEquals(AnimType.DUNGEON_WALK_TOWARDS, w.type());
    assertEquals(3, w.cells);
    assertEquals(120, w.frameTimeMs);
    assertEquals(0, w.loops); // infinite
    assertEquals(8, w.cellWidth());
    assertEquals(Mono.BLACK, Mono.state(w.frame(1), 1, 2)); // second frame, local coords
  }

  @Test
  public void framesComposeIntoStrip() throws IOException {
    BufferedImage f0 = Mono.blank(4, 4, false);
    Mono.set(f0, 0, 0, true);
    BufferedImage f1 = Mono.blank(4, 4, false);
    Mono.set(f1, 3, 3, true);

    BwAnimBank.Anim a = BwAnimBank.Anim.ofFrames(AnimType.ITEM_USAGE, List.of(f0, f1), 100, 0);
    assertEquals(2, a.cells);
    assertEquals(8, a.width);
    assertEquals(Mono.BLACK, Mono.state(a.frame(0), 0, 0));
    assertEquals(Mono.BLACK, Mono.state(a.frame(1), 3, 3));
  }

  @Test
  public void unknownTypeCodeRoundTrips() throws IOException {
    BwAnimBank bank = new BwAnimBank();
    BwAnimBank.Anim a = BwAnimBank.Anim.ofStrip(AnimType.UNKNOWN, Mono.blank(2, 2, false), 1, 0, 1);
    a.typeCode = 9999; // a code no enum constant claims
    bank.anims.add(a);

    BwAnimBank.Anim back = fromBytes(bytesOf(bank)).anims.get(0);
    assertEquals(9999, back.typeCode);
    assertEquals(AnimType.UNKNOWN, back.type());
  }

  @Test
  public void badMagicIsRejected() {
    try {
      fromBytes(new byte[] {0x00, 0x00, 0x00});
      fail("expected bad magic");
    } catch (IOException expected) {
      // ok
    }
  }

  @Test
  public void corruptChecksumIsRejected() throws IOException {
    BwAnimBank bank = new BwAnimBank();
    bank.anims.add(BwAnimBank.Anim.ofStrip(AnimType.BATTLE_STANCE, Mono.blank(4, 4, false), 1, 0, 1));
    byte[] data = bytesOf(bank);
    // the checksum byte sits after magic(1)+count(2)+type(2)+cells(2)+frameTime(2)+loops(1)+bits(4)+w(2)+h(2)
    int checksumIndex = 1 + 2 + 2 + 2 + 2 + 1 + 4 + 2 + 2;
    data[checksumIndex] ^= 0xFF;
    try {
      fromBytes(data);
      fail("expected checksum failure");
    } catch (IOException expected) {
      // ok
    }
  }
}
