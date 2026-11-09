package net.minecheck.common.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The mode enum: what each mode means for the two mode-dependent defaults, and how it is spelled.
 */
class CameraModeTest {

  @Test
  void offIsTheDefaultAndInstallsNothing() {
    assertFalse(CameraMode.OFF.enabled(), "off installs nothing");
    assertFalse(CameraMode.OFF.defaultDisableOnDamage(), "nobody is watching, so nothing to exit");
    assertFalse(CameraMode.OFF.defaultNotifications(), "no notices for a camera that is not there");
  }

  @Test
  void assistIsTheHumanMode() {
    assertTrue(CameraMode.ASSIST.enabled());
    assertTrue(CameraMode.ASSIST.defaultDisableOnDamage(), "damage should hand the view back");
    assertTrue(CameraMode.ASSIST.defaultNotifications(), "a human wants to be told what happened");
  }

  @Test
  void fullControlLeavesTheDefaultsToTheScript() {
    assertTrue(CameraMode.FULL_CONTROL.enabled());
    assertFalse(
        CameraMode.FULL_CONTROL.defaultDisableOnDamage(),
        "a script may well want to photograph the hit");
    assertFalse(
        CameraMode.FULL_CONTROL.defaultNotifications(), "an action-bar line can enter a capture");
  }

  @Test
  void parseForgivesCaseAndSurroundingSpace() {
    assertEquals(CameraMode.OFF, CameraMode.parse("off"));
    assertEquals(CameraMode.ASSIST, CameraMode.parse("ASSIST"));
    assertEquals(CameraMode.ASSIST, CameraMode.parse("  Assist  "));
    assertEquals(CameraMode.FULL_CONTROL, CameraMode.parse("fullControl"));
    assertEquals(CameraMode.FULL_CONTROL, CameraMode.parse("FullControl"));
  }

  @Test
  void parseRejectsEverythingElse() {
    for (String raw : new String[] {"", "   ", "on", "full_control", "spectate", "true"}) {
      assertThrows(
          IllegalArgumentException.class, () -> CameraMode.parse(raw), "must reject: " + raw);
    }
    assertThrows(IllegalArgumentException.class, () -> CameraMode.parse(null));
  }

  @Test
  void jsonNamesRoundTripThroughParse() {
    for (CameraMode mode : CameraMode.values()) {
      assertEquals(mode, CameraMode.parse(mode.jsonName()), mode.name());
    }
    assertEquals("off", CameraMode.OFF.jsonName());
    assertEquals("assist", CameraMode.ASSIST.jsonName());
    assertEquals("fullControl", CameraMode.FULL_CONTROL.jsonName());
  }

  @Test
  void interactionModesParseCaseInsensitively() {
    assertEquals(CameraInteractionMode.CAMERA, CameraInteractionMode.parse("CAMERA"));
    assertEquals(CameraInteractionMode.PLAYER, CameraInteractionMode.parse("player"));
    assertEquals(CameraInteractionMode.PLAYER, CameraInteractionMode.parse(" Player "));
    assertThrows(IllegalArgumentException.class, () -> CameraInteractionMode.parse("north"));
    assertThrows(IllegalArgumentException.class, () -> CameraInteractionMode.parse("both"));
  }
}
