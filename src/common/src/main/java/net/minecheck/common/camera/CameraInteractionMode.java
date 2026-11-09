package net.minecheck.common.camera;

import java.util.Locale;

/**
 * Where a world interaction originates while the camera is the driven view.
 *
 * <p>{@link #CAMERA}: the camera's crosshair picks the target. {@link #PLAYER}: the player's own
 * crosshair does, even though the view is elsewhere. Either way the target must also be within the
 * player's reach — that gate is not a mode, it is always applied.
 */
public enum CameraInteractionMode {
  CAMERA,
  PLAYER;

  /** The mode a file value names; case and surrounding space are forgiven. */
  public static CameraInteractionMode parse(String raw) {
    if (raw != null) {
      switch (raw.trim().toUpperCase(Locale.ROOT)) {
        case "CAMERA":
          return CAMERA;
        case "PLAYER":
          return PLAYER;
        default:
          break;
      }
    }
    throw new IllegalArgumentException(
        "not an interaction mode: \"" + raw + "\" (expected CAMERA or PLAYER)");
  }
}
