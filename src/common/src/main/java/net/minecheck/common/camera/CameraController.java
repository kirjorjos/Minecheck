package net.minecheck.common.camera;

/**
 * The camera's state machine, independent of the game.
 *
 * <p>It owns the things every later phase asks about: whether the camera is attached, where it is,
 * how fast it flies, and what one tick of input does to it. The loader glue owns everything
 * Minecraft-shaped — creating the entity, making it the render camera, reading key state — and
 * calls into this object, which is what lets the behaviour be tested without a client.
 *
 * <p>The tick never moves the camera while it is detached or while the caller says the input is not
 * the human's, so "the key was ignored" is a property of this class rather than of every caller.
 * Speed is one factor per axis, adjusted a notch at a time the way spectator flight is, and bounded
 * by the config's documented 0–10 range.
 */
public final class CameraController {

  /** One scroll notch changes both speed factors by this much, spectator-style. */
  public static final double SPEED_STEP = 0.1;

  /** The bounds a speed factor is kept in; matches the config's documented range. */
  public static final double MIN_SPEED = 0.0;

  public static final double MAX_SPEED = 10.0;

  private final CameraMode mode;

  private CameraPose pose;

  private double horizontalSpeed;

  private double verticalSpeed;

  public CameraController(CameraMode mode, double horizontalSpeed, double verticalSpeed) {
    this.mode = mode == null ? CameraMode.OFF : mode;
    this.horizontalSpeed = clampSpeed(horizontalSpeed);
    this.verticalSpeed = clampSpeed(verticalSpeed);
  }

  public CameraMode mode() {
    return mode;
  }

  /** Whether the camera is attached. Always false while the mode is {@code off}. */
  public boolean active() {
    return pose != null;
  }

  /**
   * The current pose, or {@code null} while detached.
   *
   * <p>{@link CameraPose} is immutable, so handing it out cannot let a caller move the camera by
   * mutating it — the source spec's copy rule without needing to copy anything.
   */
  public CameraPose pose() {
    return pose;
  }

  public double horizontalSpeed() {
    return horizontalSpeed;
  }

  public double verticalSpeed() {
    return verticalSpeed;
  }

  /**
   * Attach at {@code startingPose}; refused when the mode is off, when no pose was given, or when
   * the camera is already attached.
   */
  public boolean attach(CameraPose startingPose) {
    if (!mode.enabled() || startingPose == null || pose != null) {
      return false;
    }
    pose = startingPose;
    return true;
  }

  /**
   * Detach, discarding the pose. Idempotent: detaching twice reports {@code false} the second time.
   */
  public boolean detach() {
    if (pose == null) {
      return false;
    }
    pose = null;
    return true;
  }

  /** Move to an absolute pose; refused while detached. */
  public boolean setPose(CameraPose next) {
    if (pose == null || next == null) {
      return false;
    }
    pose = next;
    return true;
  }

  /** Move relative to the current pose, along its own basis; refused while detached. */
  public boolean move(double forward, double up, double right) {
    if (pose == null) {
      return false;
    }
    pose = pose.moved(forward, up, right);
    return true;
  }

  /**
   * One tick of input.
   *
   * @param inputs what the human or a script is holding
   * @param acceptInput whether that input belongs to the camera this tick — a detached camera, a
   *     hidden window or a scripted-only run all pass {@code false}
   * @return the pose the camera should be placed at, or {@code null} when nothing moved
   */
  public CameraPose tick(CameraMovement.Inputs inputs, boolean acceptInput) {
    if (pose == null || !acceptInput || inputs == null) {
      return null;
    }
    CameraPose.Vector velocity =
        CameraMovement.velocity(pose, inputs, horizontalSpeed, verticalSpeed);
    if (velocity.length() == 0.0) {
      return null;
    }
    pose = pose.translated(velocity);
    return pose;
  }

  /**
   * One scroll notch: positive speeds up, negative slows down, both factors move together so the
   * camera keeps its vertical/horizontal relationship.
   */
  public void adjustSpeed(double notches) {
    double delta = notches * SPEED_STEP;
    horizontalSpeed = clampSpeed(horizontalSpeed + delta);
    verticalSpeed = clampSpeed(verticalSpeed + delta);
  }

  /** Set both factors at once, clamped to the documented range. */
  public void setSpeed(double horizontal, double vertical) {
    horizontalSpeed = clampSpeed(horizontal);
    verticalSpeed = clampSpeed(vertical);
  }

  private static double clampSpeed(double speed) {
    if (Double.isNaN(speed)) {
      return MIN_SPEED;
    }
    return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
  }
}
