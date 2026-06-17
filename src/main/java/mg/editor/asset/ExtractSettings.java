package mg.editor.asset;

/**
 * The last-used settings of the reference → black-and-white extract dialog,
 * cached per document so the next extraction reseeds with what worked. Stored in
 * the {@code .monster}; {@code used} stays false until an extract happens.
 */
public class ExtractSettings {
  public boolean used = false;
  public int width = 0;             // 0 = fall back to the dialog's default size
  public int height = 0;
  public String algo = "FLOYD";     // Dither.Algo name
  public int threshold = 128;
  public String alpha = "WHITE";    // Dither.AlphaMode name
  public String background = "none"; // none | edge scan
}
