package net.minecheck.common.camera;

import com.google.gson.annotations.SerializedName;
import java.util.Locale;

/**
 * How much of a run the camera owns, as {@code cameraMode} in {@code config/minecheck/config.json}.
 *
 * <p>{@link #OFF} is the default and installs nothing: no camera entity, no bindings, no camera
 * work in the render path. {@link #ASSIST} is the mode a human and a script share — the human flies
 * the camera, the script drives the scene. {@link #FULL_CONTROL} is the mode the script owns
 * outright, with every physical input ignored.
 *
 * <p>Two config defaults depend on the mode rather than being fixed: damage auto-exit and the
 * action-bar notices are things a watching human wants and a scripted run does not. They are
 * answered here so the file's parser and the camera agree on one rule.
 */
public enum CameraMode {
  @SerializedName("off")
  OFF,
  @SerializedName("assist")
  ASSIST,
  @SerializedName("fullControl")
  FULL_CONTROL;

  /** The spelling written to, and read back from, the config file. */
  public String jsonName() {
    switch (this) {
      case OFF:
        return "off";
      case ASSIST:
        return "assist";
      case FULL_CONTROL:
        return "fullControl";
    }
    throw new IllegalStateException("unhandled camera mode: " + this);
  }

  /** Whether the camera is installed at all. */
  public boolean enabled() {
    return this != OFF;
  }

  /**
   * Leave the camera when the player takes damage: on for a human sharing the instance, off for a
   * script that may well want to photograph the hit.
   */
  public boolean defaultDisableOnDamage() {
    return this == ASSIST;
  }

  /** Action-bar notices on toggle: on for a human, off when nobody is watching. */
  public boolean defaultNotifications() {
    return this == ASSIST;
  }

  /**
   * The mode a file value names. Case and surrounding space are forgiven ({@code " Assist "}
   * works); anything else is rejected rather than silently becoming {@code off}, because a typo in
   * the one field that decides whether the camera exists must not pass unremarked.
   */
  public static CameraMode parse(String raw) {
    if (raw != null) {
      switch (raw.trim().toLowerCase(Locale.ROOT)) {
        case "off":
          return OFF;
        case "assist":
          return ASSIST;
        case "fullcontrol":
          return FULL_CONTROL;
        default:
          break;
      }
    }
    throw new IllegalArgumentException(
        "not a camera mode: \"" + raw + "\" (expected off, assist or fullControl)");
  }
}
