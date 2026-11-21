package net.minecheck.loader.legacyfabric;

import net.minecraft.entity.Entity;

/**
 * Where an entity's feet are on this line.
 *
 * <p>1.7.10's {@code Entity.y} is not the feet position it is on the newer lines: it is offset by
 * the entity's eye height (a player standing with its bounding box starting at y=4 reports {@code
 * y} = 5.62, and {@code getEyeHeight()} reports 0.12 rather than 1.62). The bounding box is the
 * position the collision, the renderer and the server all agree on, so every height this platform
 * publishes — a player's position, an entity's position — and every ray it casts starts from the
 * box corner rather than from that field.
 */
final class LegacyEntitySpace {

  /** A player's eye height above its feet, which is what the camera and every ray start from. */
  static final double PLAYER_EYE_HEIGHT = 1.62;

  private LegacyEntitySpace() {}

  /** The y an entity's bounding box starts at: its feet. */
  static double feetY(Entity entity) {
    return entity.getBoundingBox().minY;
  }

  /** The y a player looks from: its eyes. */
  static double eyeY(Entity entity) {
    return feetY(entity) + PLAYER_EYE_HEIGHT;
  }
}
