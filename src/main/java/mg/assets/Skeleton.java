package mg.assets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a bipedal walk-cycle skeleton in PixelLab's {@code skeleton_keypoints}
 * format — an array of frames, each an array of labelled {@code {x,y,label,z_index}}
 * points in image-pixel space (origin top-left). The 18 labels are exactly those
 * PixelLab accepts. The pose drives the limb motion per frame; the request's
 * {@code view}/{@code direction} drive facing. A template, tunable by stride/lift
 * and a front-vs-profile layout (front for towards/away, profile for left/right).
 */
public final class Skeleton {

  /** the joint labels PixelLab accepts, head-to-toe. */
  public static final String[] LABELS = {
      "NOSE", "LEFT EYE", "RIGHT EYE", "LEFT EAR", "RIGHT EAR", "NECK",
      "LEFT SHOULDER", "RIGHT SHOULDER", "LEFT ELBOW", "RIGHT ELBOW", "LEFT ARM", "RIGHT ARM",
      "LEFT HIP", "RIGHT HIP", "LEFT KNEE", "RIGHT KNEE", "LEFT LEG", "RIGHT LEG"};

  /** bones to draw for the overlay (pairs of labels). */
  public static final String[][] BONES = {
      {"NOSE", "NECK"},
      {"NECK", "LEFT SHOULDER"}, {"NECK", "RIGHT SHOULDER"},
      {"LEFT SHOULDER", "LEFT ELBOW"}, {"LEFT ELBOW", "LEFT ARM"},
      {"RIGHT SHOULDER", "RIGHT ELBOW"}, {"RIGHT ELBOW", "RIGHT ARM"},
      {"NECK", "LEFT HIP"}, {"NECK", "RIGHT HIP"},
      {"LEFT HIP", "LEFT KNEE"}, {"LEFT KNEE", "LEFT LEG"},
      {"RIGHT HIP", "RIGHT KNEE"}, {"RIGHT KNEE", "RIGHT LEG"}};

  public record Point(double x, double y, String label) {}

  private Skeleton() {}

  /**
   * A walk cycle of {@code frames} frames sized to {@code w}&times;{@code h}.
   * {@code stride} (fraction of width) is the horizontal swing, {@code lift}
   * (fraction of height) the foot raise; {@code profile} narrows the body and
   * exaggerates fore/aft swing for a side view.
   */
  public static List<List<Point>> walk(int w, int h, int frames, double stride, double lift, boolean profile) {
    List<List<Point>> out = new ArrayList<>();
    double cx = w / 2.0;
    double sep = profile ? 0.35 : 1.0;    // limb separation from the midline
    double swing = stride * w * (profile ? 1.4 : 0.7);
    double raise = lift * h;
    int n = Math.max(1, frames);
    for (int i = 0; i < n; i++) {
      double p = 2 * Math.PI * i / n;
      double legL = Math.sin(p);
      double legR = Math.sin(p + Math.PI);
      double armL = Math.sin(p + Math.PI);
      double armR = Math.sin(p);
      List<Point> f = new ArrayList<>();
      add(f, "NOSE", cx, 0.12 * h);
      add(f, "LEFT EYE", cx - 0.03 * w * sep, 0.10 * h);
      add(f, "RIGHT EYE", cx + 0.03 * w * sep, 0.10 * h);
      add(f, "LEFT EAR", cx - 0.05 * w * sep, 0.11 * h);
      add(f, "RIGHT EAR", cx + 0.05 * w * sep, 0.11 * h);
      add(f, "NECK", cx, 0.20 * h);
      add(f, "LEFT SHOULDER", cx - 0.10 * w * sep, 0.24 * h);
      add(f, "RIGHT SHOULDER", cx + 0.10 * w * sep, 0.24 * h);
      add(f, "LEFT ELBOW", cx - 0.12 * w * sep + armL * swing * 0.5, 0.36 * h);
      add(f, "RIGHT ELBOW", cx + 0.12 * w * sep + armR * swing * 0.5, 0.36 * h);
      add(f, "LEFT ARM", cx - 0.13 * w * sep + armL * swing, 0.48 * h);
      add(f, "RIGHT ARM", cx + 0.13 * w * sep + armR * swing, 0.48 * h);
      add(f, "LEFT HIP", cx - 0.07 * w * sep, 0.52 * h);
      add(f, "RIGHT HIP", cx + 0.07 * w * sep, 0.52 * h);
      add(f, "LEFT KNEE", cx - 0.07 * w * sep + legL * swing * 0.6, 0.70 * h - Math.max(0, legL) * raise * 0.5);
      add(f, "RIGHT KNEE", cx + 0.07 * w * sep + legR * swing * 0.6, 0.70 * h - Math.max(0, legR) * raise * 0.5);
      add(f, "LEFT LEG", cx - 0.07 * w * sep + legL * swing, 0.92 * h - Math.max(0, legL) * raise);
      add(f, "RIGHT LEG", cx + 0.07 * w * sep + legR * swing, 0.92 * h - Math.max(0, legR) * raise);
      out.add(f);
    }
    return out;
  }

  private static void add(List<Point> f, String label, double x, double y) {
    f.add(new Point(x, y, label));
  }

  /**
   * Turn a single <b>rest pose</b> ({@code [18][2]} normalized, e.g. from
   * estimate-skeleton) into a walk cycle by swinging the limbs around their
   * detected positions — so the gait matches the character's real proportions
   * without any hand-posing. Returns {@code [frames][18][2]} normalized.
   */
  public static double[][][] walkFromRest(double[][] rest, int frames, boolean profile) {
    int n = Math.max(2, frames);
    double swing = profile ? 0.10 : 0.06; // horizontal limb swing (normalized)
    double raise = 0.05;                   // foot lift (normalized)
    int ll = idx("LEFT LEG");
    int rl = idx("RIGHT LEG");
    int lk = idx("LEFT KNEE");
    int rk = idx("RIGHT KNEE");
    int la = idx("LEFT ARM");
    int ra = idx("RIGHT ARM");
    int le = idx("LEFT ELBOW");
    int re = idx("RIGHT ELBOW");
    double[][][] out = new double[n][][];
    for (int i = 0; i < n; i++) {
      double p = 2 * Math.PI * i / n;
      double legL = Math.sin(p);
      double legR = Math.sin(p + Math.PI);
      double armL = Math.sin(p + Math.PI);
      double armR = Math.sin(p);
      double[][] f = copyPose(rest);
      shift(f, ll, legL * swing, -Math.max(0, legL) * raise);
      shift(f, rl, legR * swing, -Math.max(0, legR) * raise);
      shift(f, lk, legL * swing * 0.6, -Math.max(0, legL) * raise * 0.5);
      shift(f, rk, legR * swing * 0.6, -Math.max(0, legR) * raise * 0.5);
      shift(f, la, armL * swing * 0.8, 0);
      shift(f, ra, armR * swing * 0.8, 0);
      shift(f, le, armL * swing * 0.4, 0);
      shift(f, re, armR * swing * 0.4, 0);
      out[i] = f;
    }
    return out;
  }

  /** index of a label in {@link #LABELS}, or -1. */
  public static int idx(String label) {
    for (int i = 0; i < LABELS.length; i++) {
      if (LABELS[i].equals(label)) {
        return i;
      }
    }
    return -1;
  }

  private static void shift(double[][] f, int j, double dx, double dy) {
    if (j >= 0 && j < f.length) {
      f[j][0] = Math.max(0, Math.min(1, f[j][0] + dx));
      f[j][1] = Math.max(0, Math.min(1, f[j][1] + dy));
    }
  }

  private static double[][] copyPose(double[][] src) {
    double[][] c = new double[src.length][2];
    for (int i = 0; i < src.length; i++) {
      c[i][0] = src[i][0];
      c[i][1] = src[i][1];
    }
    return c;
  }

  /**
   * A walk-cycle template as <b>normalized</b> frames: {@code [frames][18][2]} in
   * 0..1, joints in {@link #LABELS} order. Resolution-independent — scale to the
   * output size at request time. Used to seed an editable skeleton.
   */
  public static double[][][] templateNormalized(int frames, boolean profile) {
    int n = Math.max(1, frames);
    List<List<Point>> walk = walk(1000, 1000, n, 0.12, 0.08, profile);
    double[][][] out = new double[n][LABELS.length][2];
    for (int f = 0; f < n; f++) {
      Map<String, double[]> m = asMap(walk.get(f));
      for (int j = 0; j < LABELS.length; j++) {
        double[] p = m.get(LABELS[j]);
        out[f][j][0] = p[0] / 1000.0;
        out[f][j][1] = p[1] / 1000.0;
      }
    }
    return out;
  }

  /** serialize normalized frames ({@code [frames][18][2]}) to keypoints JSON at {@code size}. */
  public static String toPixelJson(java.util.List<double[][]> normFrames, int size) {
    List<List<Point>> frames = new ArrayList<>();
    for (double[][] nf : normFrames) {
      List<Point> f = new ArrayList<>();
      for (int j = 0; j < LABELS.length && j < nf.length; j++) {
        f.add(new Point(nf[j][0] * size, nf[j][1] * size, LABELS[j]));
      }
      frames.add(f);
    }
    return toJson(frames);
  }

  /** label → {x,y} for one frame, for overlay drawing. */
  public static Map<String, double[]> asMap(List<Point> frame) {
    Map<String, double[]> m = new LinkedHashMap<>();
    for (Point p : frame) {
      m.put(p.label(), new double[] {p.x(), p.y()});
    }
    return m;
  }

  /** serialize frames to the PixelLab {@code skeleton_keypoints} JSON array. */
  public static String toJson(List<List<Point>> frames) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < frames.size(); i++) {
      if (i > 0) {
        b.append(',');
      }
      b.append('[');
      List<Point> f = frames.get(i);
      for (int j = 0; j < f.size(); j++) {
        if (j > 0) {
          b.append(',');
        }
        Point p = f.get(j);
        b.append("{\"x\":").append(Math.round(p.x()))
            .append(",\"y\":").append(Math.round(p.y()))
            .append(",\"label\":\"").append(p.label()).append("\",\"z_index\":0}");
      }
      b.append(']');
    }
    return b.append(']').toString();
  }
}
