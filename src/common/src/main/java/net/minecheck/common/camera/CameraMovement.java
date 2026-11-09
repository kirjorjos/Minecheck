package net.minecheck.common.camera;

/**
 * The camera's flight model — the {@code DEFAULT} one, and the only one it has.
 *
 * <p>One tick of input becomes a velocity: horizontal speed along the yaw-only forward and right
 * axes, sprinting multiplied by 1.5, and diagonals scaled so that holding two keys is not faster
 * than holding one. Vertical movement comes only from jump and sneak, because the camera flies
 * horizontally and looks in a different direction — a camera that climbed with the crosshair would
 * make framing a shot from above awkward.
 *
 * <p>Vanilla's own flying is not used: the camera entity's flying speed is forced to zero, so this
 * is the only thing that moves it. That is what makes the motion identical on every loader and
 * every Minecraft version, and what makes it testable here.
 */
public final class CameraMovement {

  /** sin(45°): each axis of a diagonal is scaled by this, so the resulting speed is unchanged. */
  public static final double DIAGONAL_SCALE = Math.sin(Math.PI / 4.0);

  /** Sprinting moves the camera 1.5×, matching vanilla's own sprint multiplier. */
  public static final double SPRINT_MULTIPLIER = 1.5;

  private CameraMovement() {}

  /** The keys the camera reads for one tick. */
  public record Inputs(
      boolean forward,
      boolean back,
      boolean left,
      boolean right,
      boolean jump,
      boolean sneak,
      boolean sprint) {

    /** Nothing held: the camera stays put. */
    public static Inputs none() {
      return new Inputs(false, false, false, false, false, false, false);
    }
  }

  /**
   * The velocity one tick of {@code inputs} produces, in blocks per tick.
   *
   * <p>Opposing keys cancel ({@code forward} with {@code back}, {@code right} with {@code left}),
   * jump beats sneak when both are held, and a zero speed factor means no motion on that axis.
   */
  public static CameraPose.Vector velocity(
      CameraPose pose, Inputs inputs, double horizontalSpeed, double verticalSpeed) {
    double forwardAmount = (inputs.forward() ? 1 : 0) - (inputs.back() ? 1 : 0);
    double rightAmount = (inputs.right() ? 1 : 0) - (inputs.left() ? 1 : 0);
    if (forwardAmount != 0 && rightAmount != 0) {
      forwardAmount *= DIAGONAL_SCALE;
      rightAmount *= DIAGONAL_SCALE;
    }
    double speed = horizontalSpeed * (inputs.sprint() ? SPRINT_MULTIPLIER : 1.0);
    CameraPose.Vector horizontal =
        pose.forward().scaled(forwardAmount * speed).plus(pose.right().scaled(rightAmount * speed));
    double vertical = inputs.jump() ? verticalSpeed : inputs.sneak() ? -verticalSpeed : 0.0;
    return new CameraPose.Vector(horizontal.x(), vertical, horizontal.z());
  }
}
