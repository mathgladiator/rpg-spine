package mg.assets;

/**
 * The stable 16-bit role code stored for each animation in a {@link BwAnimBank}.
 * Single images (a battle stance, an item icon) are just one-cell animations, so
 * every art slot a monster or item owns maps to one of these. Codes are stable
 * (like the spine field codes) so a bank survives reordering and new roles being
 * added; unknown codes round-trip as {@link #UNKNOWN} without losing the raw
 * value (the bank stores the raw {@code int}).
 */
public enum AnimType {
  UNKNOWN(0),

  BATTLE_STANCE(1),
  BATTLE_DAMAGE(2),
  BATTLE_ATTACK(3),

  DUNGEON_IDLE_TOWARDS(10),
  DUNGEON_IDLE_AWAY(11),
  DUNGEON_IDLE_LEFT(12),
  DUNGEON_IDLE_RIGHT(13),

  DUNGEON_WALK_TOWARDS(20),
  DUNGEON_WALK_AWAY(21),
  DUNGEON_WALK_LEFT(22),
  DUNGEON_WALK_RIGHT(23),

  ITEM_ICON_LARGE(40),
  ITEM_ICON_MEDIUM(41),
  ITEM_ICON_SMALL(42),
  ITEM_USAGE(43);

  /** the 16-bit code written to disk. */
  public final int code;

  AnimType(int code) {
    this.code = code;
  }

  /** the role for a code, or {@link #UNKNOWN} if it is not one we recognise. */
  public static AnimType fromCode(int code) {
    for (AnimType t : values()) {
      if (t.code == code) {
        return t;
      }
    }
    return UNKNOWN;
  }
}
