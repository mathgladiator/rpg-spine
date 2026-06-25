package mg.assets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;

import org.junit.Test;

public class BwCodecTests {

  private static void roundTrip(int[] px) {
    byte[] data = BwCodec.encode(px);
    assertEquals("encodedSize matches encode()", data.length, BwCodec.encodedSize(px));
    assertArrayEquals(px, BwCodec.decode(data, px.length));
  }

  @Test
  public void emptyStream() {
    roundTrip(new int[0]);
    assertEquals(0, BwCodec.encodedSize(new int[0]));
  }

  @Test
  public void flatRunsCollapse() {
    int[] px = new int[100];
    java.util.Arrays.fill(px, Mono.WHITE);
    roundTrip(px);
    // 100 white pixels collapse to a single 2-byte long run
    assertEquals(2, BwCodec.encodedSize(px));
  }

  @Test
  public void shortRunStillUsedUnder32() {
    int[] px = new int[20];
    java.util.Arrays.fill(px, Mono.BLACK);
    assertEquals(1, BwCodec.encodedSize(px)); // fits one short run
    roundTrip(px);
  }

  @Test
  public void runOf32IsOneByte() {
    // count-1 reclaims the dead "run of 0" code, so 32 fits a single short-run byte
    int[] px = new int[32];
    java.util.Arrays.fill(px, Mono.BLACK);
    byte[] data = BwCodec.encode(px);
    assertEquals(1, data.length);
    assertEquals(0x1F, data[0] & 0x1F); // count field = 32 - 1
    roundTrip(px);
  }

  @Test
  public void longRunCarriesColourAndCount() {
    int[] px = new int[500];
    java.util.Arrays.fill(px, Mono.TRANSPARENT);
    byte[] data = BwCodec.encode(px);
    assertEquals(2, data.length); // 500 transparent in one long run
    int b0 = data[0] & 0xFF;
    assertEquals(0x60, b0 & 0xE0);                 // top bit 0, colour field = 11 (long-run marker)
    assertEquals(Mono.TRANSPARENT, (b0 >> 3) & 3); // real colour
    int count = (((b0 & 0x7) << 8) | (data[1] & 0xFF)) + 1; // stored as count-1
    assertEquals(500, count);
    roundTrip(px);
  }

  @Test
  public void runsBeyondTheLongCapChain() {
    int[] px = new int[5000]; // > 2048, needs multiple long runs
    java.util.Arrays.fill(px, Mono.WHITE);
    roundTrip(px);
  }

  @Test
  public void transparentUsesRunChunks() {
    int[] px = {Mono.TRANSPARENT, Mono.TRANSPARENT, Mono.TRANSPARENT, Mono.BLACK, Mono.WHITE};
    roundTrip(px);
  }

  @Test
  public void detailPacksSevenPerByte() {
    // alternating b/w defeats runs; the encoder must use detail chunks (7 px/byte)
    int[] px = new int[14];
    for (int i = 0; i < px.length; i++) {
      px[i] = (i & 1) == 0 ? Mono.BLACK : Mono.WHITE;
    }
    roundTrip(px);
    assertEquals(2, BwCodec.encodedSize(px)); // 14 pixels / 7 = 2 detail bytes
  }

  @Test
  public void detailBitOrderIsMostSignificantFirst() {
    int[] px = {Mono.BLACK, Mono.WHITE, Mono.WHITE, Mono.WHITE, Mono.WHITE, Mono.WHITE, Mono.WHITE};
    byte[] data = BwCodec.encode(px);
    assertEquals(1, data.length);
    // top bit set (detail) + first pixel black at bit 6 => 0x80 | 0x40 = 0xC0
    assertEquals(0xC0, data[0] & 0xFF);
  }

  @Test
  public void mixedDetailAndRuns() {
    int[] px = {
        Mono.BLACK, Mono.WHITE, Mono.BLACK, Mono.WHITE, Mono.BLACK, Mono.WHITE, Mono.BLACK, // detail
        Mono.TRANSPARENT, Mono.TRANSPARENT,                                                 // run
        Mono.BLACK, Mono.BLACK, Mono.BLACK, Mono.BLACK, Mono.BLACK, Mono.BLACK, Mono.BLACK, Mono.BLACK,
        Mono.WHITE,
    };
    roundTrip(px);
  }

  @Test
  public void runsBeyondShortCapUseTwoBytes() {
    // 40 black: a short run holds at most 32, so this needs two bytes either way
    int[] px = new int[40];
    java.util.Arrays.fill(px, Mono.BLACK);
    byte[] data = BwCodec.encode(px);
    assertEquals(2, data.length);
    assertArrayEquals(px, BwCodec.decode(data, px.length));
  }

  @Test
  public void imageRoundTrip() {
    BufferedImage img = Mono.blank(11, 7, false);
    Mono.set(img, 0, 0, true);
    Mono.set(img, 5, 3, true);
    Mono.setTransparent(img, 10, 6);
    int[] px = BwCodec.pixelsOf(img);
    BufferedImage back = BwCodec.toImage(BwCodec.decode(BwCodec.encode(px), px.length), 11, 7);
    for (int y = 0; y < 7; y++) {
      for (int x = 0; x < 11; x++) {
        assertEquals("(" + x + "," + y + ")", Mono.state(img, x, y), Mono.state(back, x, y));
      }
    }
  }

  @Test
  public void decodeRejectsWrongPixelCount() {
    byte[] data = BwCodec.encode(new int[] {Mono.BLACK, Mono.WHITE});
    try {
      BwCodec.decode(data, 5);
      fail("expected mismatch");
    } catch (IllegalArgumentException expected) {
      // ok
    }
  }
}
