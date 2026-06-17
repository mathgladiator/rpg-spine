package mg.editor.asset;

import java.util.ArrayList;
import java.util.List;

/**
 * An explicit, file-per-frame animation: an ordered list of image paths (each a
 * real PNG on disk, relative to the owning document), a frame speed, and a loop
 * count. "Everything explicit" — frames are concrete files so existing tools and
 * the eventual build step operate on them directly. Single images (a stance, an
 * icon) are just paths and don't use this.
 */
public class Animation {
  /** ordered frame image paths, relative to the owning document's folder. */
  public final List<String> frames = new ArrayList<>();
  /** playback speed in frames per second. */
  public int fps = 6;
  /** loop count; 0 = loop forever. */
  public int loop = 0;

  /** join frames for single-line KV storage (paths can't contain '|'). */
  public String framesJoined() {
    return String.join("|", frames);
  }

  /** replace frames from a '|'-joined string. */
  public void setFramesJoined(String joined) {
    frames.clear();
    if (joined == null || joined.isBlank()) {
      return;
    }
    for (String f : joined.split("\\|")) {
      String t = f.strip();
      if (!t.isEmpty()) {
        frames.add(t);
      }
    }
  }

  public boolean isEmpty() {
    return frames.isEmpty();
  }
}
