package mg.assets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.Test;

public class DitherTests {

  /** a mid-grey ramp for exercising tonal algorithms. */
  private static BufferedImage greyRamp(int w, int h) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < w; x++) {
      int v = (int) (255.0 * x / (w - 1));
      int rgb = (v << 16) | (v << 8) | v;
      for (int y = 0; y < h; y++) {
        img.setRGB(x, y, rgb);
      }
    }
    return img;
  }

  @Test
  public void everyAlgoProducesBinaryImageOfSameSize() {
    BufferedImage src = greyRamp(32, 16);
    for (Dither.Algo a : Dither.Algo.values()) {
      BufferedImage out = Dither.apply(src, a, 128);
      assertEquals(a + " width", 32, out.getWidth());
      assertEquals(a + " height", 16, out.getHeight());
      assertEquals(a + " type", BufferedImage.TYPE_BYTE_INDEXED, out.getType());
    }
  }

  @Test
  public void thresholdExtremes() {
    BufferedImage src = greyRamp(10, 4);
    // threshold 0: nothing is < 0 → all white
    BufferedImage white = Dither.threshold(src, 0);
    assertFalse(Mono.isBlack(white, 0, 0));
    assertFalse(Mono.isBlack(white, 9, 0));
    // threshold 256: everything is < 256 → all black
    BufferedImage black = Dither.threshold(src, 256);
    assertTrue(Mono.isBlack(black, 0, 0));
    assertTrue(Mono.isBlack(black, 9, 0));
  }

  @Test
  public void bayerMatrixIsAPermutation() {
    int[][] m = Dither.bayer(4);
    boolean[] seen = new boolean[16];
    for (int[] row : m) {
      for (int v : row) {
        assertFalse("duplicate " + v, seen[v]);
        seen[v] = true;
      }
    }
    for (boolean b : seen) {
      assertTrue(b);
    }
  }

  @Test
  public void floydDiffusesMidGreyToAMixOfBlackAndWhite() {
    BufferedImage flat = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        flat.setRGB(x, y, 0x808080); // ~50% grey
      }
    }
    BufferedImage out = Dither.apply(flat, Dither.Algo.FLOYD, 128);
    int black = 0;
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        if (Mono.isBlack(out, x, y)) black++;
      }
    }
    // a 50% grey should yield a roughly balanced mix, not all-one-colour
    assertTrue("black=" + black, black > 32 && black < 224);
  }

  @Test
  public void contourOfAFilledSquareDrawsAnOutline() {
    BufferedImage src = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
    // white field with a black filled square in the middle
    for (int y = 0; y < 20; y++) {
      for (int x = 0; x < 20; x++) {
        boolean inside = x >= 6 && x < 14 && y >= 6 && y < 14;
        src.setRGB(x, y, inside ? 0x000000 : 0xFFFFFF);
      }
    }
    BufferedImage out = Dither.contour(src, 128);
    int black = 0;
    for (int y = 0; y < 20; y++) {
      for (int x = 0; x < 20; x++) {
        if (Mono.isBlack(out, x, y)) black++;
      }
    }
    // the interior and exterior are blank; only the ~perimeter is inked
    assertTrue("outline pixels=" + black, black > 8 && black < 120);
    assertFalse("centre should be blank", Mono.isBlack(out, 10, 10));
  }
}
