package mg.assets;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A single-file bank of black / white / transparent animations — enough to hold
 * every art slot a monster or item owns (stances, icons, walk cycles) in one
 * compact, Playdate-friendly file. The pixel data is the single-byte RLE/detail
 * stream from {@link BwCodec}.
 *
 * <h2>Binary layout (big-endian)</h2>
 * <pre>
 *   u8   magic            = 0x42
 *   u16  animationCount
 *   animationCount &times; {
 *     u16  type            (an {@link AnimType} code)
 *     u16  cells           (frames in the strip)
 *     u16  frameTime       (ms per frame; 0 = a still)
 *     u8   loops           (0 = loop forever)
 *     u32  bits            (== width * height; integrity check)
 *     u16  width
 *     u16  height
 *     u8   checksum        (sum of the 7 fields above, &amp; 0xFF)
 *     u32  byteCount       (length of the encoded image that follows)
 *     u8[byteCount] data   ({@link BwCodec} stream of `bits` pixels)
 *   }
 * </pre>
 *
 * <p>The image is the whole animation as a horizontal strip of {@code cells}
 * equal frames, so {@code cellWidth = width / cells}. Reading validates the
 * magic, every {@code bits == width*height}, the per-animation checksum, and
 * that the encoded stream decodes to exactly {@code bits} pixels.
 */
public final class BwAnimBank {

  /** the file magic — first byte of every bank. */
  public static final int MAGIC = 0x42;

  public final List<Anim> anims = new ArrayList<>();

  /** one named animation: a tri-state pixel strip plus playback metadata. */
  public static final class Anim {
    /** the raw 16-bit role code (see {@link AnimType}). */
    public int typeCode;
    /** number of equal-width frames packed into the strip. */
    public int cells;
    /** milliseconds per frame; 0 for a still image. */
    public int frameTimeMs;
    /** loop count, 0 = forever. */
    public int loops;
    public int width;
    public int height;
    /** row-major tri-state pixels, length {@code width * height}. */
    public int[] pixels;

    /** total pixel count ({@code width * height}); the on-disk {@code bits}. */
    public int bits() {
      return width * height;
    }

    /** the per-frame width ({@code width / cells}), or the full width if cells is 0. */
    public int cellWidth() {
      return cells > 0 ? width / cells : width;
    }

    /** the recognised role, or {@link AnimType#UNKNOWN}. */
    public AnimType type() {
      return AnimType.fromCode(typeCode);
    }

    /** rebuild the strip as a {@link Mono} 1-bit image. */
    public BufferedImage toImage() {
      return BwCodec.toImage(pixels, width, height);
    }

    /** the frame at {@code index} (0-based) as its own image. */
    public BufferedImage frame(int index) {
      int cw = cellWidth();
      return Mono.region(toImage(), index * cw, 0, cw, height);
    }

    /** build an animation from a strip image already sliced into {@code cells} frames. */
    public static Anim ofStrip(AnimType type, BufferedImage strip, int cells, int frameTimeMs, int loops) {
      Anim a = new Anim();
      a.typeCode = type.code;
      a.cells = cells;
      a.frameTimeMs = frameTimeMs;
      a.loops = loops;
      a.width = strip.getWidth();
      a.height = strip.getHeight();
      a.pixels = BwCodec.pixelsOf(strip);
      return a;
    }

    /** build an animation by laying out equal-size frames left-to-right into one strip. */
    public static Anim ofFrames(AnimType type, List<BufferedImage> frames, int frameTimeMs, int loops) {
      if (frames.isEmpty()) {
        throw new IllegalArgumentException("no frames");
      }
      int h = frames.get(0).getHeight();
      int cw = frames.get(0).getWidth();
      for (BufferedImage f : frames) {
        if (f.getWidth() != cw || f.getHeight() != h) {
          throw new IllegalArgumentException("frames must all be " + cw + "x" + h);
        }
      }
      BufferedImage strip = Mono.newBinary(cw * frames.size(), h);
      for (int i = 0; i < frames.size(); i++) {
        BufferedImage f = frames.get(i);
        for (int y = 0; y < h; y++) {
          for (int x = 0; x < cw; x++) {
            Mono.setState(strip, i * cw + x, y, Mono.state(f, x, y));
          }
        }
      }
      return ofStrip(type, strip, frames.size(), frameTimeMs, loops);
    }
  }

  // -------------------------------------------------------------------- writing

  public void write(File file) throws IOException {
    File parent = file.getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(file))) {
      write(os);
    }
  }

  public void write(OutputStream os) throws IOException {
    DataOutputStream out = new DataOutputStream(os);
    out.writeByte(MAGIC);
    out.writeShort(anims.size());
    for (Anim a : anims) {
      int bits = a.bits();
      if (a.pixels.length != bits) {
        throw new IOException("pixel count " + a.pixels.length + " != bits " + bits);
      }
      byte[] data = BwCodec.encode(a.pixels);
      out.writeShort(a.typeCode);
      out.writeShort(a.cells);
      out.writeShort(a.frameTimeMs);
      out.writeByte(a.loops);
      out.writeInt(bits);
      out.writeShort(a.width);
      out.writeShort(a.height);
      out.writeByte(checksum(a.typeCode, a.cells, a.frameTimeMs, a.loops, bits, a.width, a.height));
      out.writeInt(data.length);
      out.write(data);
    }
    out.flush();
  }

  // -------------------------------------------------------------------- reading

  public static BwAnimBank read(File file) throws IOException {
    try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
      return read(is);
    }
  }

  public static BwAnimBank read(InputStream is) throws IOException {
    DataInputStream in = new DataInputStream(is);
    int magic = in.readUnsignedByte();
    if (magic != MAGIC) {
      throw new IOException(String.format("bad magic 0x%02X, expected 0x%02X", magic, MAGIC));
    }
    BwAnimBank bank = new BwAnimBank();
    int count = in.readUnsignedShort();
    for (int i = 0; i < count; i++) {
      Anim a = new Anim();
      a.typeCode = in.readUnsignedShort();
      a.cells = in.readUnsignedShort();
      a.frameTimeMs = in.readUnsignedShort();
      a.loops = in.readUnsignedByte();
      int bits = in.readInt();
      a.width = in.readUnsignedShort();
      a.height = in.readUnsignedShort();
      int checksum = in.readUnsignedByte();
      int byteCount = in.readInt();

      if (bits != a.width * a.height) {
        throw new IOException("animation " + i + ": bits " + bits + " != " + a.width + "x" + a.height);
      }
      int expected = checksum(a.typeCode, a.cells, a.frameTimeMs, a.loops, bits, a.width, a.height);
      if (checksum != expected) {
        throw new IOException("animation " + i + ": checksum " + checksum + " != " + expected);
      }
      byte[] data = new byte[byteCount];
      in.readFully(data);
      a.pixels = BwCodec.decode(data, bits);
      bank.anims.add(a);
    }
    return bank;
  }

  /** the one-byte header checksum: the sum of every field byte, masked to 8 bits. */
  static int checksum(int type, int cells, int frameTime, int loops, int bits, int width, int height) {
    int sum = 0;
    sum += (type >> 8) & 0xFF;
    sum += type & 0xFF;
    sum += (cells >> 8) & 0xFF;
    sum += cells & 0xFF;
    sum += (frameTime >> 8) & 0xFF;
    sum += frameTime & 0xFF;
    sum += loops & 0xFF;
    sum += (bits >> 24) & 0xFF;
    sum += (bits >> 16) & 0xFF;
    sum += (bits >> 8) & 0xFF;
    sum += bits & 0xFF;
    sum += (width >> 8) & 0xFF;
    sum += width & 0xFF;
    sum += (height >> 8) & 0xFF;
    sum += height & 0xFF;
    return sum & 0xFF;
  }
}
