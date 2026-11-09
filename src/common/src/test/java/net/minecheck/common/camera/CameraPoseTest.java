package net.minecheck.common.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pose math: the basis the camera moves along and the angles it reports.
 *
 * <p>These are the numbers every later phase depends on, so they are pinned here rather than only
 * through a rendering test: a wrong sign in {@link CameraPose#right()} would move the camera the
 * opposite way from the key, which is far easier to see in an assertion than in a screenshot.
 */
class CameraPoseTest {

  private static final double EPSILON = 1e-9;

  private static void assertVector(double x, double y, double z, CameraPose.Vector actual) {
    assertEquals(x, actual.x(), EPSILON, "x");
    assertEquals(y, actual.y(), EPSILON, "y");
    assertEquals(z, actual.z(), EPSILON, "z");
  }

  private static void assertVector(
      double x, double y, double z, CameraPose.Vector actual, String message) {
    assertEquals(x, actual.x(), EPSILON, message + " x");
    assertEquals(y, actual.y(), EPSILON, message + " y");
    assertEquals(z, actual.z(), EPSILON, message + " z");
  }

  @Test
  void atEyeAddsTheEyeHeightToTheFeetPosition() {
    CameraPose pose = CameraPose.atEye(1, 64, 2, 0, 0, 1.62);

    assertEquals(1, pose.x(), EPSILON);
    assertEquals(65.62, pose.y(), EPSILON);
    assertEquals(2, pose.z(), EPSILON);
  }

  @Test
  void forwardFollowsYawOnly() {
    // Vanilla: yaw 0 looks south (+Z), positive yaw turns toward west (-X).
    assertVector(0, 0, 1, new CameraPose(0, 0, 0, 0, 0).forward());
    assertVector(-1, 0, 0, new CameraPose(0, 0, 0, 90, 0).forward());
    assertVector(0, 0, -1, new CameraPose(0, 0, 0, 180, 0).forward());
  }

  @Test
  void rightIsForwardTurnedRight() {
    // Facing south, your right hand points west.
    assertVector(-1, 0, 0, new CameraPose(0, 0, 0, 0, 0).right());
    assertVector(0, 0, -1, new CameraPose(0, 0, 0, 90, 0).right());
  }

  @Test
  void viewIncludesPitch() {
    assertVector(0, 0, 1, new CameraPose(0, 0, 0, 0, 0).view());
    assertVector(0, -1, 0, new CameraPose(0, 0, 0, 0, 90).view(), "pitch 90 looks down");
    assertVector(0, 1, 0, new CameraPose(0, 0, 0, 0, -90).view(), "pitch -90 looks up");
  }

  @Test
  void movedWalksAlongTheBasis() {
    CameraPose south = new CameraPose(0, 0, 0, 0, 0);
    assertEquals(3, south.moved(3, 0, 0).z(), EPSILON);
    assertEquals(-3, south.moved(0, 0, 3).x(), EPSILON, "right of south is west");
    assertEquals(3, south.movedForward(3).z(), EPSILON);

    CameraPose west = new CameraPose(10, 5, 10, 90, 0);
    assertEquals(7, west.moved(3, 0, 0).x(), EPSILON);
    assertEquals(10, west.moved(3, 0, 0).z(), EPSILON);
  }

  @Test
  void verticalMovementIgnoresRotation() {
    CameraPose pose = new CameraPose(0, 0, 0, 137, 42);

    assertEquals(4, pose.moved(0, 4, 0).y(), EPSILON);
    assertEquals(0, pose.moved(0, 4, 0).x(), EPSILON);
    assertEquals(0, pose.moved(0, 4, 0).z(), EPSILON);
  }

  @Test
  void mirroringTurnsAroundAndFlipsPitch() {
    CameraPose mirrored = new CameraPose(1, 2, 3, 30, 20).mirrored();

    assertEquals(-150, mirrored.yaw(), 1e-4, "30 + 180 normalised to (-180, 180]");
    assertEquals(-20, mirrored.pitch(), 1e-4);
    assertEquals(1, mirrored.x(), EPSILON);
    assertEquals(2, mirrored.y(), EPSILON);
    assertEquals(3, mirrored.z(), EPSILON);
  }

  @Test
  void yawIsKeptInRange() {
    assertEquals(0, new CameraPose(0, 0, 0, 360, 0).yaw(), 1e-4);
    assertEquals(-170, new CameraPose(0, 0, 0, 190, 0).yaw(), 1e-4);
    assertEquals(170, new CameraPose(0, 0, 0, -190, 0).yaw(), 1e-4);
    assertEquals(180, new CameraPose(0, 0, 0, -180, 0).yaw(), 1e-4);
  }

  @Test
  void pitchIsClampedToTheVanillaRange() {
    assertEquals(90, new CameraPose(0, 0, 0, 0, 100).pitch(), 1e-4);
    assertEquals(-90, new CameraPose(0, 0, 0, 0, -100).pitch(), 1e-4);
    assertEquals(45, new CameraPose(0, 0, 0, 0, 45).pitch(), 1e-4);
  }

  @Test
  void withRotationChangesOnlyTheAngles() {
    CameraPose moved = new CameraPose(5, 6, 7, 10, 20).withRotation(90, -30);

    assertEquals(5, moved.x(), EPSILON);
    assertEquals(6, moved.y(), EPSILON);
    assertEquals(7, moved.z(), EPSILON);
    assertEquals(90, moved.yaw(), 1e-4);
    assertEquals(-30, moved.pitch(), 1e-4);
  }
}
