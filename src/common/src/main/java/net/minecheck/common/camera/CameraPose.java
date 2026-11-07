package net.minecheck.common.camera;

/**
 * A camera position and rotation, plus the movement basis derived from it.
 *
 * <p>The camera sits where an entity's eye would be ({@link #atEye}) and moves along the basis
 * vanilla uses: forward is yaw-only — flight is horizontal, with a separate vertical input — and
 * right is that forward turned 90 degrees. Keeping this arithmetic here, free of Minecraft types,
 * is what lets the flight model be unit-tested without a game.
 *
 * <p>Angles follow the vanilla convention: yaw 0 looks south (+Z), positive yaw turns toward west
 * (-X), pitch 90 looks straight down and -90 straight up. The constructor keeps yaw in (-180, 180]
 * and pitch in [-90, 90], so no caller has to normalise after a rotation change.
 */
public record CameraPose(double x, double y, double z, float yaw, float pitch) {

  /** A direction or offset in world coordinates. */
  public record Vector(double x, double y, double z) {

    public Vector plus(Vector other) {
      return new Vector(x + other.x, y + other.y, z + other.z);
    }

    public Vector scaled(double factor) {
      return new Vector(x * factor, y * factor, z * factor);
    }

    public double length() {
      return Math.sqrt(x * x + y * y + z * z);
    }
  }

  public CameraPose {
    yaw = normalizeYaw(yaw);
    pitch = clampPitch(pitch);
  }

  /**
   * A pose at an entity's eye level: {@code y} is the feet position, and the eye height is added
   * here so the camera starts at eye level rather than at the player's feet.
   */
  public static CameraPose atEye(
      double x, double y, double z, float yaw, float pitch, double eyeHeight) {
    return new CameraPose(x, y + eyeHeight, z, yaw, pitch);
  }

  /** The look direction, pitch included — what the camera sees. */
  public Vector view() {
    double yawRadians = Math.toRadians(yaw);
    double pitchRadians = Math.toRadians(pitch);
    return new Vector(
        -Math.sin(yawRadians) * Math.cos(pitchRadians),
        -Math.sin(pitchRadians),
        Math.cos(yawRadians) * Math.cos(pitchRadians));
  }

  /** The horizontal forward direction (yaw only): the axis flight moves along. */
  public Vector forward() {
    double yawRadians = Math.toRadians(yaw);
    return new Vector(-Math.sin(yawRadians), 0.0, Math.cos(yawRadians));
  }

  /** The horizontal right direction: {@link #forward()} turned 90 degrees. */
  public Vector right() {
    double yawRadians = Math.toRadians(yaw);
    return new Vector(-Math.cos(yawRadians), 0.0, -Math.sin(yawRadians));
  }

  /** This pose moved by the given amounts along the basis, plus a vertical offset. */
  public CameraPose moved(double forwardAmount, double upAmount, double rightAmount) {
    Vector offset =
        forward()
            .scaled(forwardAmount)
            .plus(right().scaled(rightAmount))
            .plus(new Vector(0.0, upAmount, 0.0));
    return new CameraPose(x + offset.x(), y + offset.y(), z + offset.z(), yaw, pitch);
  }

  /**
   * This pose translated by a world-space offset.
   *
   * <p>Movement in this class comes in two shapes and they are easy to confuse: {@link #moved}
   * takes amounts along the pose's own basis, while this takes a vector already expressed in world
   * coordinates — what {@link CameraMovement#velocity} produces.
   */
  public CameraPose translated(Vector offset) {
    return new CameraPose(x + offset.x(), y + offset.y(), z + offset.z(), yaw, pitch);
  }

  /** This pose moved along its own forward axis. */
  public CameraPose movedForward(double distance) {
    return moved(distance, 0.0, 0.0);
  }

  /** This pose rotated 180 degrees: the mirror placement, yaw turned and pitch negated. */
  public CameraPose mirrored() {
    return new CameraPose(x, y, z, yaw + 180.0f, -pitch);
  }

  public CameraPose withRotation(float newYaw, float newPitch) {
    return new CameraPose(x, y, z, newYaw, newPitch);
  }

  /** The shortest equivalent yaw in (-180, 180]. */
  public static float normalizeYaw(float rawYaw) {
    float normalized = rawYaw % 360.0f;
    if (normalized > 180.0f) {
      normalized -= 360.0f;
    } else if (normalized <= -180.0f) {
      normalized += 360.0f;
    }
    return normalized;
  }

  /** Pitch has no wrap: 90 looks straight down, -90 straight up, and nothing is steeper. */
  public static float clampPitch(float rawPitch) {
    return Math.max(-90.0f, Math.min(90.0f, rawPitch));
  }
}
