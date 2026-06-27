package mg.codegen;

import mg.assets.AnimType;
import mg.assets.BwAnimBank;
import mg.assets.Mono;
import mg.editor.story.Story;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Compiles a {@link Story} into the binary {@code .storybin} container
 * (see {@code documents/STORYBIN_FORMAT.md}) — the whole story plus all its images
 * packed into one file. Images are packed as a {@link BwAnimBank} ({@code .bwa},
 * one one-cell animation per image) so the same codec serves story art and, later,
 * real animations. Logic (targets, effects, results) is integer-encoded; text is an
 * interned string pool; everything is big-endian with a trailing CRC32.
 *
 * <p>{@link #compile} is used by {@link Compiler}; {@link #decode} is the symmetric
 * reader used by tests (the device reads it in C via {@code runtime/storybin.c}).
 */
public final class Storybin {
  private Storybin() {}

  /** the 4-byte magic {@code "STB1"}. */
  static final int MAGIC0 = 'S', MAGIC1 = 'T', MAGIC2 = 'B', MAGIC3 = '1';
  static final int VERSION = 1;
  /** reserved sentinel for "no string / no image / no target" in any u16 index. */
  static final int NONE = 0xFFFF;

  // ============================================================== compile (write)

  /**
   * Compile {@code story} to {@code .storybin} bytes. {@code effectCodes} maps each
   * declared effect name to its stable dispatch code; {@code baseDir} is the folder
   * image paths are resolved against (the story file's directory). Throws on an
   * undeclared effect, a missing/oversize image, or an over-long pool.
   */
  public static byte[] compile(Story story, Map<String, Integer> effectCodes, File baseDir)
      throws IOException {
    // node id -> index
    Map<String, Integer> nodeIndex = new LinkedHashMap<>();
    for (int i = 0; i < story.nodes.size(); i++) {
      nodeIndex.put(story.nodes.get(i).id, i);
    }
    if (story.nodes.size() > NONE) {
      throw new IOException("too many nodes (" + story.nodes.size() + ")");
    }

    // intern strings and images on first use, in encounter order
    Map<String, Integer> strIndex = new LinkedHashMap<>();
    Map<String, Integer> imgIndex = new LinkedHashMap<>();
    BwAnimBank bank = new BwAnimBank();

    ByteArrayOutputStream nodesBuf = new ByteArrayOutputStream();
    DataOutputStream nodes = new DataOutputStream(nodesBuf);
    for (Story.Node n : story.nodes) {
      nodes.writeByte(n.kind.ordinal()); // 0 beat, 1 choice, 2 outcome
      if (n.onEnter.size() > 255) {
        throw new IOException(story.id + "." + n.id + ": too many effects (" + n.onEnter.size() + ")");
      }
      nodes.writeByte(n.onEnter.size());
      for (Story.Effect e : n.onEnter) {
        if (e.name == null || e.name.isBlank()) {
          throw new IOException(story.id + "." + n.id + ": empty effect name");
        }
        Integer code = effectCodes.get(e.name);
        if (code == null) {
          throw new IOException(story.id + "." + n.id + ": effect '" + e.name
              + "' is not declared (effect <code>: " + e.name + "; ) in any .rpg");
        }
        nodes.writeShort(code);
        nodes.writeInt(e.param);
      }
      switch (n.kind) {
        case BEAT -> {
          nodes.writeShort(intern(strIndex, n.text));
          nodes.writeShort(internImage(imgIndex, bank, baseDir, n.image));
          nodes.writeShort(nodeRef(nodeIndex, n.next));
        }
        case CHOICE -> {
          nodes.writeShort(intern(strIndex, n.text));
          nodes.writeShort(nodeRef(nodeIndex, n.next));
          if (n.choices.size() > 255) {
            throw new IOException(story.id + "." + n.id + ": too many choices");
          }
          nodes.writeByte(n.choices.size());
          for (Story.Choice c : n.choices) {
            nodes.writeShort(intern(strIndex, c.text));
            nodes.writeShort(c.to == null || c.to.isBlank() ? NONE : nodeRef(nodeIndex, c.to));
          }
        }
        case OUTCOME -> {
          nodes.writeByte(n.result.ordinal()); // 0 survive, 1 die
          nodes.writeShort(intern(strIndex, n.reason));
          nodes.writeShort(internImage(imgIndex, bank, baseDir, n.reward));
        }
      }
    }
    nodes.flush();

    // embedded image bank
    ByteArrayOutputStream bankBuf = new ByteArrayOutputStream();
    bank.write(bankBuf);
    byte[] bankBytes = bankBuf.toByteArray();

    // string pool, in interned order
    String[] strings = new String[strIndex.size()];
    for (Map.Entry<String, Integer> e : strIndex.entrySet()) {
      strings[e.getValue()] = e.getKey();
    }

    // assemble the body (everything the CRC covers)
    ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
    DataOutputStream body = new DataOutputStream(bodyBuf);
    body.writeByte(MAGIC0);
    body.writeByte(MAGIC1);
    body.writeByte(MAGIC2);
    body.writeByte(MAGIC3);
    body.writeShort(VERSION);
    body.writeShort(nodeRef(nodeIndex, story.start));
    body.writeInt(bankBytes.length);
    body.write(bankBytes);
    body.writeShort(strings.length);
    for (String s : strings) {
      byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
      if (utf8.length > NONE) {
        throw new IOException("string too long (" + utf8.length + " bytes)");
      }
      body.writeShort(utf8.length);
      body.write(utf8);
    }
    body.writeShort(story.nodes.size());
    body.write(nodesBuf.toByteArray());
    body.flush();

    byte[] bytes = bodyBuf.toByteArray();
    CRC32 crc = new CRC32();
    crc.update(bytes);

    ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 4);
    out.write(bytes);
    DataOutputStream tail = new DataOutputStream(out);
    tail.writeInt((int) crc.getValue());
    tail.flush();
    return out.toByteArray();
  }

  private static int intern(Map<String, Integer> pool, String s) {
    if (s == null || s.isBlank()) {
      return NONE;
    }
    return pool.computeIfAbsent(s, k -> pool.size());
  }

  private static int nodeRef(Map<String, Integer> nodeIndex, String id) {
    if (id == null || id.isBlank()) {
      return NONE;
    }
    Integer i = nodeIndex.get(id);
    return i == null ? NONE : i;
  }

  /** load {@code path} (relative to baseDir) as a 1-bit image and add it to the bank once. */
  private static int internImage(Map<String, Integer> imgIndex, BwAnimBank bank, File baseDir,
                                 String path) throws IOException {
    if (path == null || path.isBlank()) {
      return NONE;
    }
    Integer existing = imgIndex.get(path);
    if (existing != null) {
      return existing;
    }
    File f = new File(baseDir, path);
    if (!f.isFile()) {
      throw new IOException("image not found: " + path + " (looked in " + baseDir + ")");
    }
    BufferedImage img = Mono.load(f);
    int idx = bank.anims.size();
    bank.anims.add(BwAnimBank.Anim.ofStrip(AnimType.STORY_SCENE, img, 1, 0, 0));
    imgIndex.put(path, idx);
    return idx;
  }

  // ============================================================== decode (read)

  /** a decoded node, for tests: kind + effects + resolved-by-index fields. */
  public static final class DNode {
    public int kind;                 // 0 beat, 1 choice, 2 outcome
    public final List<int[]> fx = new ArrayList<>(); // {code, param}
    public int text = NONE, image = NONE, next = NONE, reason = NONE, reward = NONE;
    public int result;               // outcome only
    public final List<int[]> decisions = new ArrayList<>(); // {label, to}
  }

  /** a decoded story, for tests (the device builds the same shape in C). */
  public static final class Decoded {
    public int start;
    public final List<String> strings = new ArrayList<>();
    public BwAnimBank bank;
    public final List<DNode> nodes = new ArrayList<>();

    public String string(int idx) {
      return idx == NONE || idx < 0 || idx >= strings.size() ? "" : strings.get(idx);
    }
  }

  /** Parse {@code .storybin} bytes back into a {@link Decoded}, verifying magic + CRC. */
  public static Decoded decode(byte[] data) throws IOException {
    if (data.length < 4 + 2 + 2 + 4 + 2 + 2 + 4) {
      throw new IOException("too short");
    }
    CRC32 crc = new CRC32();
    crc.update(data, 0, data.length - 4);
    int stored = ((data[data.length - 4] & 0xFF) << 24) | ((data[data.length - 3] & 0xFF) << 16)
        | ((data[data.length - 2] & 0xFF) << 8) | (data[data.length - 1] & 0xFF);
    if ((int) crc.getValue() != stored) {
      throw new IOException("crc mismatch");
    }

    DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
    if (in.readUnsignedByte() != MAGIC0 || in.readUnsignedByte() != MAGIC1
        || in.readUnsignedByte() != MAGIC2 || in.readUnsignedByte() != MAGIC3) {
      throw new IOException("bad magic");
    }
    int version = in.readUnsignedShort();
    if (version != VERSION) {
      throw new IOException("unsupported version " + version);
    }
    Decoded d = new Decoded();
    d.start = in.readUnsignedShort();
    int bankLen = in.readInt();
    byte[] bankBytes = new byte[bankLen];
    in.readFully(bankBytes);
    d.bank = BwAnimBank.read(new ByteArrayInputStream(bankBytes));

    int strCount = in.readUnsignedShort();
    for (int i = 0; i < strCount; i++) {
      int len = in.readUnsignedShort();
      byte[] s = new byte[len];
      in.readFully(s);
      d.strings.add(new String(s, StandardCharsets.UTF_8));
    }

    int nodeCount = in.readUnsignedShort();
    for (int i = 0; i < nodeCount; i++) {
      DNode n = new DNode();
      n.kind = in.readUnsignedByte();
      int nfx = in.readUnsignedByte();
      for (int k = 0; k < nfx; k++) {
        int code = in.readUnsignedShort();
        int param = in.readInt();
        n.fx.add(new int[] {code, param});
      }
      switch (n.kind) {
        case 0 -> { n.text = in.readUnsignedShort(); n.image = in.readUnsignedShort(); n.next = in.readUnsignedShort(); }
        case 1 -> {
          n.text = in.readUnsignedShort();
          n.next = in.readUnsignedShort();
          int ndec = in.readUnsignedByte();
          for (int k = 0; k < ndec; k++) {
            n.decisions.add(new int[] {in.readUnsignedShort(), in.readUnsignedShort()});
          }
        }
        case 2 -> { n.result = in.readUnsignedByte(); n.reason = in.readUnsignedShort(); n.reward = in.readUnsignedShort(); }
        default -> throw new IOException("unknown node kind " + n.kind);
      }
      d.nodes.add(n);
    }
    return d;
  }
}
