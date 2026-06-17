package mg.assets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Client for the PixelLab.ai REST API — purpose-built pixel-art generation, the
 * chosen image backend (see documents/AI.GEN.md). Calls are <b>synchronous</b>
 * (no task polling): a request returns the generated image(s) directly as base64
 * PNG. Auth is a bearer secret (the PixelLab key from Settings); callers pass it
 * in so this class stays independent of the editor.
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>{@code generate-image-pixflux} — text → image (references).
 *   <li>{@code generate-image-bitforge} — text + a style reference image.
 *   <li>{@code rotate} — turn a character to a new facing direction.
 *   <li>{@code animate-with-text} — reference + action → animation frames.
 *   <li>{@code animate-with-skeleton} — reference + per-frame skeleton keypoints
 *       → animation frames (caller supplies the keypoints JSON).
 * </ul>
 * Images cross the wire as a {@code {"type":"base64","base64":"…"}} wrapper.
 */
public final class PixelLab {
  private static final String BASE = "https://api.pixellab.ai/v1";

  private final String secret;
  private final HttpClient http;

  public PixelLab(String secret) {
    this.secret = secret;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  }

  // ------------------------------------------------------------------ requests

  /** text → image (PNG bytes). */
  public byte[] pixflux(String description, int w, int h, boolean noBackground) throws IOException {
    String body = "{" + jsonStr("description") + ":" + jsonStr(description)
        + "," + jsonStr("image_size") + ":" + size(w, h)
        + "," + jsonStr("no_background") + ":" + noBackground
        + "}";
    return firstImage(post("/generate-image-pixflux", body));
  }

  /** text + a style reference → image (PNG bytes). */
  public byte[] bitforge(String description, int w, int h, byte[] stylePng, boolean noBackground) throws IOException {
    StringBuilder b = new StringBuilder("{");
    b.append(jsonStr("description")).append(":").append(jsonStr(description));
    b.append(",").append(jsonStr("image_size")).append(":").append(size(w, h));
    if (stylePng != null) {
      b.append(",").append(jsonStr("style_image")).append(":").append(b64img(stylePng));
    }
    b.append(",").append(jsonStr("no_background")).append(":").append(noBackground);
    b.append("}");
    return firstImage(post("/generate-image-bitforge", b.toString()));
  }

  /** rotate a character image to a new facing (e.g. "south"→"east"). */
  public byte[] rotate(byte[] fromPng, int w, int h, String fromDirection, String toDirection) throws IOException {
    StringBuilder b = new StringBuilder("{");
    b.append(jsonStr("image_size")).append(":").append(size(w, h));
    b.append(",").append(jsonStr("from_image")).append(":").append(b64img(fromPng));
    if (fromDirection != null) {
      b.append(",").append(jsonStr("from_direction")).append(":").append(jsonStr(fromDirection));
    }
    if (toDirection != null) {
      b.append(",").append(jsonStr("to_direction")).append(":").append(jsonStr(toDirection));
    }
    b.append("}");
    return firstImage(post("/rotate", b.toString()));
  }

  /** reference + action text → animation frames (PNG bytes each). */
  /**
   * {@code imageGuidance} is PixelLab's {@code image_guidance_scale} (1..20, "how
   * closely to follow the reference image"). Its API default of 1.4 makes the
   * text dominate and the reference get ignored — pass a higher value to keep
   * the character's appearance.
   */
  public List<byte[]> animateWithText(byte[] referencePng, String description, String action,
                                      int w, int h, int frames, double imageGuidance) throws IOException {
    String body = "{"
        + jsonStr("image_size") + ":" + size(w, h)
        + "," + jsonStr("description") + ":" + jsonStr(description)
        + "," + jsonStr("action") + ":" + jsonStr(action)
        + "," + jsonStr("reference_image") + ":" + b64img(referencePng)
        + "," + jsonStr("image_guidance_scale") + ":" + imageGuidance
        + "," + jsonStr("n_frames") + ":" + frames
        + "}";
    return allImages(post("/animate-with-text", body));
  }

  /**
   * reference + per-frame skeleton keypoints → animation frames. {@code
   * keypointsJson} is the raw value for {@code skeleton_keypoints} (an array of
   * frames, each an array of {@code {x,y,label,z_index}} points).
   */
  public List<byte[]> animateWithSkeleton(byte[] referencePng, int w, int h, String keypointsJson,
                                          String view, String direction, int frameCount) throws IOException {
    StringBuilder body = new StringBuilder("{");
    body.append(jsonStr("image_size")).append(":").append(size(w, h));
    body.append(",").append(jsonStr("reference_image")).append(":").append(b64img(referencePng));
    body.append(",").append(jsonStr("skeleton_keypoints")).append(":").append(keypointsJson);
    // PixelLab expects the pose-image arrays to match the number of keypoint
    // frames; send nulls of the same length (no inpainting / masking per frame).
    String nulls = nullArray(frameCount);
    body.append(",").append(jsonStr("inpainting_images")).append(":").append(nulls);
    body.append(",").append(jsonStr("mask_images")).append(":").append(nulls);
    if (view != null) {
      body.append(",").append(jsonStr("view")).append(":").append(jsonStr(view));
    }
    if (direction != null) {
      body.append(",").append(jsonStr("direction")).append(":").append(jsonStr(direction));
    }
    body.append("}");
    return allImages(post("/animate-with-skeleton", body.toString()));
  }

  private static String nullArray(int n) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        b.append(',');
      }
      b.append("null");
    }
    return b.append(']').toString();
  }

  /** a single detected joint from {@link #estimateSkeleton}, in image-pixel space. */
  public record Keypoint(double x, double y, String label) {}

  /** ask PixelLab to detect the skeleton of a character image → labelled joints. */
  public List<Keypoint> estimateSkeleton(byte[] imagePng) throws IOException {
    String body = "{" + jsonStr("image") + ":" + b64img(imagePng) + "}";
    return parseKeypoints(post("/estimate-skeleton", body));
  }

  private static List<Keypoint> parseKeypoints(String json) {
    List<Keypoint> out = new ArrayList<>();
    int k = json.indexOf("\"keypoints\"");
    if (k < 0) {
      return out;
    }
    int lb = json.indexOf('[', k);
    int rb = lb < 0 ? -1 : json.indexOf(']', lb);
    if (lb < 0 || rb < 0) {
      return out;
    }
    String arr = json.substring(lb + 1, rb);
    int i = 0;
    while (true) {
      int o = arr.indexOf('{', i);
      if (o < 0) {
        break;
      }
      int c = arr.indexOf('}', o);
      if (c < 0) {
        break;
      }
      String obj = arr.substring(o + 1, c);
      Double x = num(obj, "x");
      Double y = num(obj, "y");
      String label = str(obj, "label");
      if (x != null && y != null && label != null) {
        out.add(new Keypoint(x, y, label));
      }
      i = c + 1;
    }
    return out;
  }

  private static Double num(String s, String key) {
    int k = s.indexOf("\"" + key + "\"");
    if (k < 0) {
      return null;
    }
    int i = s.indexOf(':', k);
    if (i < 0) {
      return null;
    }
    i++;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    int start = i;
    while (i < s.length() && "0123456789+-.eE".indexOf(s.charAt(i)) >= 0) {
      i++;
    }
    try {
      return Double.parseDouble(s.substring(start, i));
    } catch (Exception e) {
      return null;
    }
  }

  private static String str(String s, String key) {
    int k = s.indexOf("\"" + key + "\"");
    if (k < 0) {
      return null;
    }
    int i = s.indexOf(':', k);
    if (i < 0) {
      return null;
    }
    i++;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    if (i >= s.length() || s.charAt(i) != '"') {
      return null;
    }
    int start = i + 1;
    int end = s.indexOf('"', start);
    return end < 0 ? null : s.substring(start, end);
  }

  // ------------------------------------------------------------------- transport

  private String post(String path, String body) throws IOException {
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
          .timeout(Duration.ofSeconds(180))
          .header("Authorization", "Bearer " + secret)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new IOException("PixelLab POST " + path + " failed (" + resp.statusCode() + "): " + resp.body());
      }
      return resp.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted calling PixelLab " + path, e);
    }
  }

  // ---------------------------------------------------------------- JSON helpers

  private static String size(int w, int h) {
    return "{\"width\":" + w + ",\"height\":" + h + "}";
  }

  private static String b64img(byte[] png) {
    return "{\"type\":\"base64\",\"base64\":" + jsonStr(Base64.getEncoder().encodeToString(png)) + "}";
  }

  /** decode the first base64 image found in the response. */
  private static byte[] firstImage(String json) throws IOException {
    String b64 = firstBase64(json);
    if (b64 == null) {
      throw new IOException("PixelLab returned no image");
    }
    return Base64.getDecoder().decode(b64);
  }

  /** decode every base64 image found in the response (animation frames). */
  private static List<byte[]> allImages(String json) throws IOException {
    List<byte[]> out = new ArrayList<>();
    int i = 0;
    while (true) {
      int at = json.indexOf("\"base64\"", i);
      if (at < 0) {
        break;
      }
      String b64 = readBase64(json, at);
      if (b64 != null) {
        out.add(Base64.getDecoder().decode(b64));
      }
      i = at + 8;
    }
    if (out.isEmpty()) {
      throw new IOException("PixelLab returned no frames");
    }
    return out;
  }

  private static String firstBase64(String json) {
    int at = json.indexOf("\"base64\"");
    return at < 0 ? null : readBase64(json, at);
  }

  /** read the string value of the "base64" key whose key starts at {@code keyAt}. */
  private static String readBase64(String json, int keyAt) {
    int i = json.indexOf(':', keyAt);
    if (i < 0) {
      return null;
    }
    i++;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    if (i >= json.length() || json.charAt(i) != '"') {
      return null;
    }
    int start = i + 1;
    int end = json.indexOf('"', start); // base64 contains no quotes
    return end < 0 ? null : json.substring(start, end);
  }

  private static String jsonStr(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
