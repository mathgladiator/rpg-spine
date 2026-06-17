package mg.editor.asset;

import java.util.ArrayList;
import java.util.List;

/**
 * A cached, editable skeleton for one reference image: a facing direction plus a
 * list of frames, each frame holding the 18 PixelLab joints (in the canonical
 * label order) as <b>normalized</b> {@code [x,y]} in 0..1 — so it is independent
 * of the output resolution. Persisted in the {@code .monster} keyed by the
 * reference path, so a hand-adjusted walk survives reopening.
 */
public class SkeletonData {
  public static final int JOINTS = 18;

  public String direction = "towards";
  /** frames; each is a {@code [JOINTS][2]} array of normalized x,y. */
  public final List<double[][]> frames = new ArrayList<>();

  public boolean isEmpty() {
    return frames.isEmpty();
  }

  /** encode frames as "f0;f1;…", each frame "x,y|x,y|…" (JOINTS pairs, 4 dp). */
  public String encode() {
    StringBuilder b = new StringBuilder();
    for (int f = 0; f < frames.size(); f++) {
      if (f > 0) {
        b.append(';');
      }
      double[][] frame = frames.get(f);
      for (int j = 0; j < frame.length; j++) {
        if (j > 0) {
          b.append('|');
        }
        b.append(fmt(frame[j][0])).append(',').append(fmt(frame[j][1]));
      }
    }
    return b.toString();
  }

  public static SkeletonData decode(String direction, String data) {
    SkeletonData s = new SkeletonData();
    s.direction = direction == null || direction.isBlank() ? "towards" : direction;
    if (data == null || data.isBlank()) {
      return s;
    }
    for (String fs : data.split(";")) {
      if (fs.isBlank()) {
        continue;
      }
      String[] pts = fs.split("\\|");
      double[][] frame = new double[JOINTS][2];
      for (int j = 0; j < JOINTS && j < pts.length; j++) {
        String[] xy = pts[j].split(",");
        if (xy.length == 2) {
          frame[j][0] = parse(xy[0]);
          frame[j][1] = parse(xy[1]);
        }
      }
      s.frames.add(frame);
    }
    return s;
  }

  private static String fmt(double v) {
    return String.format(java.util.Locale.ROOT, "%.4f", v);
  }

  private static double parse(String s) {
    try {
      return Double.parseDouble(s.strip());
    } catch (Exception e) {
      return 0;
    }
  }
}
