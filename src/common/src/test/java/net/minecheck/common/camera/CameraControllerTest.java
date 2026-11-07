package net.minecheck.common.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The camera's state machine: attach, detach, and what a tick is allowed to do.
 *
 * <p>These are the rules the loader glue relies on and that a human would notice if they were
 * wrong: an off camera cannot attach, a detached camera cannot move, ignored input does not move
 * anything, and speed stays inside the documented range however it is nudged.
 */
class CameraControllerTest {

  private static final double EPSILON = 1e-9;

  private static final CameraPose ORIGIN = new CameraPose(0, 64, 0, 0, 0);

  private static CameraMovement.Inputs forward() {
    return new CameraMovement.Inputs(true, false, false, false, false, false, false);
  }

  private static CameraController controller(CameraMode mode) {
    return new CameraController(mode, 1.0, 1.0);
  }

  @Test
  void anOffCameraCannotAttach() {
    CameraController camera = controller(CameraMode.OFF);

    assertFalse(camera.attach(ORIGIN), "off installs nothing, so there is nothing to attach");
    assertFalse(camera.active());
    assertNull(camera.pose());
  }

  @Test
  void anEnabledCameraAttachesOnce() {
    CameraController camera = controller(CameraMode.ASSIST);

    assertTrue(camera.attach(ORIGIN));
    assertTrue(camera.active());
    assertEquals(ORIGIN, camera.pose());
    assertFalse(camera.attach(new CameraPose(1, 2, 3, 0, 0)), "already attached");
  }

  @Test
  void detachingClearsThePoseAndIsIdempotent() {
    CameraController camera = controller(CameraMode.FULL_CONTROL);
    camera.attach(ORIGIN);

    assertTrue(camera.detach());
    assertFalse(camera.active());
    assertNull(camera.pose());
    assertFalse(camera.detach(), "detaching twice is not a second detach");
  }

  @Test
  void aDetachedCameraCannotBeMovedOrReposed() {
    CameraController camera = controller(CameraMode.ASSIST);

    assertFalse(camera.move(1, 0, 0));
    assertFalse(camera.setPose(ORIGIN));
    assertNull(camera.tick(forward(), true));
  }

  @Test
  void ignoredInputDoesNotMoveTheCamera() {
    CameraController camera = controller(CameraMode.ASSIST);
    camera.attach(ORIGIN);

    assertNull(camera.tick(forward(), false), "the key was not the camera's this tick");
    assertEquals(ORIGIN, camera.pose());
  }

  @Test
  void noInputDoesNotMoveTheCamera() {
    CameraController camera = controller(CameraMode.ASSIST);
    camera.attach(ORIGIN);

    assertNull(camera.tick(CameraMovement.Inputs.none(), true));
    assertEquals(ORIGIN, camera.pose());
  }

  @Test
  void aTickMovesAlongThePoseAndKeepsTheResult() {
    CameraController camera = controller(CameraMode.ASSIST);
    camera.attach(ORIGIN);

    CameraPose moved = camera.tick(forward(), true);

    assertNotNull(moved);
    assertEquals(0, moved.x(), EPSILON, "yaw 0 is south");
    assertEquals(1, moved.z(), EPSILON);
    assertEquals(64, moved.y(), EPSILON);
    assertEquals(moved, camera.pose(), "the tick is the new state, not a proposal");
  }

  @Test
  void verticalInputClimbsAndDescends() {
    CameraController camera = controller(CameraMode.ASSIST);
    camera.attach(ORIGIN);

    CameraPose climbed =
        camera.tick(
            new CameraMovement.Inputs(false, false, false, false, true, false, false), true);
    assertEquals(65, climbed.y(), EPSILON);

    CameraPose descended =
        camera.tick(
            new CameraMovement.Inputs(false, false, false, false, false, true, false), true);
    assertEquals(64, descended.y(), EPSILON);
  }

  @Test
  void relativeMovesUseThePosesOwnBasis() {
    CameraController camera = controller(CameraMode.ASSIST);
    camera.attach(new CameraPose(10, 64, 10, 90, 0));

    assertTrue(camera.move(3, 0, 0));

    assertEquals(7, camera.pose().x(), EPSILON, "yaw 90 is west");
    assertEquals(10, camera.pose().z(), EPSILON);
  }

  @Test
  void speedIsClampedWhicheverWayItIsNudged() {
    CameraController camera = new CameraController(CameraMode.ASSIST, 50, -4);
    assertEquals(CameraController.MAX_SPEED, camera.horizontalSpeed(), EPSILON);
    assertEquals(CameraController.MIN_SPEED, camera.verticalSpeed(), EPSILON);

    camera.adjustSpeed(1000);
    assertEquals(CameraController.MAX_SPEED, camera.horizontalSpeed(), EPSILON);
    assertEquals(CameraController.MAX_SPEED, camera.verticalSpeed(), EPSILON);

    camera.adjustSpeed(-1000);
    assertEquals(CameraController.MIN_SPEED, camera.horizontalSpeed(), EPSILON);
    assertEquals(CameraController.MIN_SPEED, camera.verticalSpeed(), EPSILON);

    camera.setSpeed(Double.NaN, 3);
    assertEquals(CameraController.MIN_SPEED, camera.horizontalSpeed(), EPSILON);
    assertEquals(3, camera.verticalSpeed(), EPSILON);
  }

  @Test
  void oneNotchIsOneStepAndBothFactorsMoveTogether() {
    CameraController camera = new CameraController(CameraMode.ASSIST, 2.0, 4.0);

    camera.adjustSpeed(1);
    assertEquals(2.0 + CameraController.SPEED_STEP, camera.horizontalSpeed(), EPSILON);
    assertEquals(4.0 + CameraController.SPEED_STEP, camera.verticalSpeed(), EPSILON);

    camera.adjustSpeed(-2);
    assertEquals(2.0 - CameraController.SPEED_STEP, camera.horizontalSpeed(), EPSILON);
    assertEquals(4.0 - CameraController.SPEED_STEP, camera.verticalSpeed(), EPSILON);
  }

  @Test
  void speedActuallyChangesHowFarATickMoves() {
    CameraController slow = new CameraController(CameraMode.ASSIST, 1.0, 1.0);
    CameraController fast = new CameraController(CameraMode.ASSIST, 2.0, 2.0);
    slow.attach(ORIGIN);
    fast.attach(ORIGIN);

    slow.tick(forward(), true);
    fast.tick(forward(), true);

    assertEquals(1, slow.pose().z(), EPSILON);
    assertEquals(2, fast.pose().z(), EPSILON);
  }
}
