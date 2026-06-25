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
    // 100 white pixels = ceil(100/31) = 4 run bytes, far smaller than the pixel count
    assertEquals(4, BwCodec.encodedSize(px));
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
  public void runsNeverExceedThirtyOne() {
    int[] px = new int[40];
    java.util.Arrays.fill(px, Mono.BLACK);
    byte[] data = BwCodec.encode(px);
    for (byte b : data) {
      if (((b & 0xFF) & 0x80) == 0) {
        int count = b & 0x1F;
        assertTrue("run count " + count + " in [1,31]", count >= 1 && count <= 31);
      }
    }
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
