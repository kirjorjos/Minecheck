package net.minecheck.common.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The flight model, in blocks per tick.
 *
 * <p>The cases pinned here are the ones the spec calls out: sprint is 1.5×, a diagonal is not
 * faster than a straight line, vertical movement comes only from jump and sneak, and opposing keys
 * cancel. Getting any of these wrong is a motion bug that reads as "the camera drifts", which is
 * why they are arithmetic tests rather than screenshots.
 */
class CameraMovementTest {

  private static final double EPSILON = 1e-9;

  /** Facing south (+Z), which makes the axes easy to read. */
  private static final CameraPose SOUTH = new CameraPose(0, 0, 0, 0, 0);

  private static CameraMovement.Inputs keys(
      boolean forward,
      boolean back,
      boolean left,
      boolean right,
      boolean jump,
      boolean sneak,
      boolean sprint) {
    return new CameraMovement.Inputs(forward, back, left, right, jump, sneak, sprint);
  }

  private static CameraMovement.Inputs forward() {
    return keys(true, false, false, false, false, false, false);
  }

  private static CameraMovement.Inputs jump() {
    return keys(false, false, false, false, true, false, false);
  }

  @Test
  void noInputMeansNoMotion() {
    CameraPose.Vector velocity = CameraMovement.velocity(SOUTH, CameraMovement.Inputs.none(), 1, 1);

    assertEquals(0, velocity.length(), EPSILON);
  }

  @Test
  void forwardFliesAlongYaw() {
    CameraPose.Vector south = CameraMovement.velocity(SOUTH, forward(), 1, 1);
    assertEquals(0, south.x(), EPSILON);
    assertEquals(0, south.y(), EPSILON);
    assertEquals(1, south.z(), EPSILON);

    CameraPose.Vector west =
        CameraMovement.velocity(new CameraPose(0, 0, 0, 90, 0), forward(), 1, 1);
    assertEquals(-1, west.x(), EPSILON);
    assertEquals(0, west.z(), EPSILON);
  }

  @Test
  void sprintingIsHalfAgainAsFast() {
    CameraPose.Vector walk = CameraMovement.velocity(SOUTH, forward(), 1, 1);
    CameraPose.Vector sprint =
        CameraMovement.velocity(SOUTH, keys(true, false, false, false, false, false, true), 1, 1);

    assertEquals(1.5, sprint.length(), EPSILON);
    assertEquals(walk.length() * CameraMovement.SPRINT_MULTIPLIER, sprint.length(), EPSILON);
  }

  @Test
  void aDiagonalIsNotFasterThanAStraightLine() {
    CameraPose.Vector diagonal =
        CameraMovement.velocity(SOUTH, keys(true, false, false, true, false, false, false), 1, 1);

    assertEquals(1, diagonal.length(), EPSILON, "diagonal speed equals straight speed");
    assertEquals(CameraMovement.DIAGONAL_SCALE, diagonal.z(), EPSILON);
    assertEquals(-CameraMovement.DIAGONAL_SCALE, diagonal.x(), EPSILON);
  }

  @Test
  void strafingLeftMovesEastWhenFacingSouth() {
    CameraPose.Vector velocity =
        CameraMovement.velocity(SOUTH, keys(false, false, true, false, false, false, false), 1, 1);

    assertEquals(1, velocity.x(), EPSILON);
    assertEquals(0, velocity.z(), EPSILON);
  }

  @Test
  void verticalComesOnlyFromJumpAndSneak() {
    assertEquals(1, CameraMovement.velocity(SOUTH, jump(), 1, 1).y(), EPSILON);

    CameraPose.Vector down =
        CameraMovement.velocity(SOUTH, keys(false, false, false, false, false, true, false), 1, 1);
    assertEquals(-1, down.y(), EPSILON);

    assertEquals(0, CameraMovement.velocity(SOUTH, forward(), 1, 1).y(), EPSILON);
  }

  @Test
  void jumpWinsWhenBothAreHeld() {
    CameraPose.Vector velocity =
        CameraMovement.velocity(SOUTH, keys(false, false, false, false, true, true, false), 1, 1);

    assertEquals(1, velocity.y(), EPSILON);
  }

  @Test
  void opposingKeysCancel() {
    CameraPose.Vector forwardBack =
        CameraMovement.velocity(SOUTH, keys(true, true, false, false, false, false, false), 1, 1);
    assertEquals(0, forwardBack.length(), EPSILON);

    CameraPose.Vector leftRight =
        CameraMovement.velocity(SOUTH, keys(false, false, true, true, false, false, false), 1, 1);
    assertEquals(0, leftRight.length(), EPSILON);
  }

  @Test
  void eachSpeedKeepsToItsOwnAxis() {
    CameraPose.Vector fast = CameraMovement.velocity(SOUTH, forward(), 2, 3);
    assertEquals(2, fast.z(), EPSILON);
    assertEquals(0, fast.y(), EPSILON);

    CameraPose.Vector climbing = CameraMovement.velocity(SOUTH, jump(), 2, 3);
    assertEquals(3, climbing.y(), EPSILON);
    assertEquals(0, climbing.z(), EPSILON);

    CameraPose.Vector stopped = CameraMovement.velocity(SOUTH, forward(), 0, 1);
    assertEquals(0, stopped.length(), EPSILON);
  }
}
