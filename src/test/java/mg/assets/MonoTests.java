package mg.assets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import org.junit.Test;

public class MonoTests {

  @Test
  public void blankIsBinaryAndCorrectColor() {
    BufferedImage white = Mono.blank(4, 4, false);
    assertEquals(BufferedImage.TYPE_BYTE_INDEXED, white.getType());
    assertFalse(Mono.isBlack(white, 0, 0));
    BufferedImage black = Mono.blank(4, 4, true);
    assertTrue(Mono.isBlack(black, 2, 3));
  }

  @Test
  public void transparentPixelRoundTrips() {
    BufferedImage img = Mono.blank(4, 4, false);
    Mono.setTransparent(img, 1, 1);
    assertTrue(Mono.isTransparent(img, 1, 1));
    assertFalse(Mono.isBlack(img, 1, 1));
    assertEquals(Mono.TRANSPARENT, Mono.state(img, 1, 1));
  }

  @Test
  public void backgroundMaskFlagsExteriorNotInteriorHoles() {
    // a hollow black square: exterior white is background, the inner white hole is not
    BufferedImage img = Mono.blank(7, 7, false);
    for (int i = 1; i <= 5; i++) {
      Mono.set(img, i, 1, true);
      Mono.set(img, i, 5, true);
      Mono.set(img, 1, i, true);
      Mono.set(img, 5, i, true);
    }
    boolean[][] bg = Mono.backgroundMask(img);
    assertTrue(bg[0][0]);     // corner exterior
    assertFalse(bg[3][3]);    // inner hole, walled off by ink
  }

  @Test
  public void setAndReadPixels() {
    BufferedImage img = Mono.blank(3, 3, false);
    Mono.set(img, 1, 1, true);
    assertTrue(Mono.isBlack(img, 1, 1));
    assertFalse(Mono.isBlack(img, 0, 0));
  }

  @Test
  public void outOfBoundsReadsAsPaper() {
    BufferedImage img = Mono.blank(2, 2, true);
    assertFalse(Mono.isBlack(img, -1, 0));
    assertFalse(Mono.isBlack(img, 5, 5));
  }

  @Test
  public void thresholdSplitsAtLevel() {
    BufferedImage src = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
    src.setRGB(0, 0, 0x000000); // black
    src.setRGB(1, 0, 0xFFFFFF); // white
    BufferedImage mono = Mono.toMonoThreshold(src, 128);
    assertTrue(Mono.isBlack(mono, 0, 0));
    assertFalse(Mono.isBlack(mono, 1, 0));
  }

  @Test
  public void reduceHalfMajorityVote() {
    // 2x2 all black -> single black pixel at every threshold.
    BufferedImage allBlack = Mono.blank(2, 2, true);
    assertTrue(Mono.isBlack(Mono.reduceHalf(allBlack, Mono.MAJORITY), 0, 0));

    // one black of four: black only when PRESERVE_INK (threshold 1).
    BufferedImage one = Mono.blank(2, 2, false);
    Mono.set(one, 0, 0, true);
    assertTrue(Mono.isBlack(Mono.reduceHalf(one, Mono.PRESERVE_INK), 0, 0));
    assertFalse(Mono.isBlack(Mono.reduceHalf(one, Mono.MAJORITY), 0, 0));

    // tie (two black): black at MAJORITY (>=2), white at PRESERVE_PAPER (>=3).
    BufferedImage two = Mono.blank(2, 2, false);
    Mono.set(two, 0, 0, true);
    Mono.set(two, 1, 1, true);
    assertTrue(Mono.isBlack(Mono.reduceHalf(two, Mono.MAJORITY), 0, 0));
    assertFalse(Mono.isBlack(Mono.reduceHalf(two, Mono.PRESERVE_PAPER), 0, 0));
  }

  @Test
  public void reduceHalfAndQuarterDimensions() {
    BufferedImage src = Mono.blank(48, 48, false);
    BufferedImage half = Mono.reduceHalf(src, Mono.MAJORITY);
    assertEquals(24, half.getWidth());
    assertEquals(24, half.getHeight());
    BufferedImage quarter = Mono.reduceQuarter(src, Mono.MAJORITY);
    assertEquals(12, quarter.getWidth());
    assertEquals(12, quarter.getHeight());
  }

  @Test
  public void reduceHalfOddDimensionRoundsUp() {
    BufferedImage src = Mono.blank(5, 3, false);
    BufferedImage half = Mono.reduceHalf(src, Mono.MAJORITY);
    assertEquals(3, half.getWidth());
    assertEquals(2, half.getHeight());
  }

  @Test
  public void regionExtractsRect() {
    BufferedImage src = Mono.blank(10, 10, false);
    Mono.set(src, 5, 5, true);
    BufferedImage cell = Mono.region(src, 4, 4, 3, 3);
    assertEquals(3, cell.getWidth());
    assertTrue(Mono.isBlack(cell, 1, 1)); // (5,5) maps to (1,1)
    assertFalse(Mono.isBlack(cell, 0, 0));
  }

  @Test
  public void trimWhiteCropsToBlackContent() {
    BufferedImage src = Mono.blank(10, 10, false);
    Mono.set(src, 4, 5, true);
    Mono.set(src, 5, 5, true);
    BufferedImage t = Mono.trim(src, false); // strip white margins
    assertEquals(2, t.getWidth());
    assertEquals(1, t.getHeight());
    assertTrue(Mono.isBlack(t, 0, 0));
    assertTrue(Mono.isBlack(t, 1, 0));
  }

  @Test
  public void trimAllWhiteReturnsOneByOne() {
    BufferedImage src = Mono.blank(8, 8, false);
    BufferedImage t = Mono.trim(src, false);
    assertEquals(1, t.getWidth());
    assertEquals(1, t.getHeight());
  }

  @Test
  public void resizeCanvasAnchorsTopLeftAndClips() {
    BufferedImage src = Mono.blank(4, 4, true); // all black
    BufferedImage grown = Mono.resizeCanvas(src, 6, 6, false);
    assertEquals(6, grown.getWidth());
    assertTrue(Mono.isBlack(grown, 0, 0));
    assertFalse(Mono.isBlack(grown, 5, 5)); // new paper area
    BufferedImage shrunk = Mono.resizeCanvas(src, 2, 2, false);
    assertEquals(2, shrunk.getWidth());
    assertTrue(Mono.isBlack(shrunk, 1, 1));
  }

  @Test
  public void scaleToHalfMatchesAreaVote() {
    BufferedImage src = Mono.blank(4, 4, true);
    BufferedImage half = Mono.scaleTo(src, 2, 2);
    assertEquals(2, half.getWidth());
    assertTrue(Mono.isBlack(half, 0, 0));
    // upscale stays black (nearest)
    BufferedImage up = Mono.scaleTo(src, 8, 8);
    assertEquals(8, up.getWidth());
    assertTrue(Mono.isBlack(up, 7, 7));
  }

  @Test
  public void savedPngIsOneBitAndRoundTrips() throws Exception {
    File f = File.createTempFile("mono", ".png");
    f.deleteOnExit();
    BufferedImage img = Mono.blank(8, 8, false);
    Mono.set(img, 3, 4, true);
    Mono.savePng(img, f);

    BufferedImage reread = Mono.load(f);
    assertTrue(Mono.isBlack(reread, 3, 4));
    assertFalse(Mono.isBlack(reread, 0, 0));
    Files.deleteIfExists(f.toPath());
  }
}
