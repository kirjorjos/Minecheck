package net.minecheck.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecheck.common.camera.CameraInteractionMode;
import net.minecheck.common.camera.CameraMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The camera block of {@code config.json}: its defaults, the two mode-dependent ones, and the ways
 * a hand-edited file can be wrong.
 *
 * <p>The camera is off unless the file asks for it — the one property everything else rests on —
 * and an unknown mode is an error rather than a silent fallback to off, because the mode is the
 * single field that decides whether the camera exists at all.
 */
class CameraConfigTest {

  @TempDir Path tempDir;

  private JsonConfigManager manager() {
    return new JsonConfigManager(tempDir);
  }

  private void write(String json) throws IOException {
    Path file = manager().configFile();
    Files.createDirectories(file.getParent());
    Files.writeString(file, json, StandardCharsets.UTF_8);
  }

  @Test
  void theCameraIsOffWithTheDocumentedDefaults() throws Exception {
    ConfigData defaults = manager().load();

    assertEquals(CameraMode.OFF, defaults.effectiveCameraMode());
    assertEquals(1.0, defaults.cameraHorizontalSpeed(), 1e-9);
    assertEquals(1.0, defaults.cameraVerticalSpeed(), 1e-9);
    assertTrue(
        defaults.cameraIgnoreAll(), "the camera passes through blocks unless told otherwise");
    assertEquals(CameraInteractionMode.CAMERA, defaults.cameraInteractionMode());
    assertTrue(defaults.cameraShowPlayer(), "the body is visible by default");
    assertFalse(defaults.cameraFullBright());
    assertFalse(defaults.cameraDisableOnDamage());
    assertFalse(defaults.cameraFreezePlayer());
    assertFalse(defaults.cameraNotifications());
  }

  @Test
  void theTwoModeDependentDefaultsComeFromTheMode() throws Exception {
    write("{\"cameraMode\": \"assist\"}");
    ConfigData assist = manager().load();
    assertTrue(assist.cameraDisableOnDamage(), "a human wants the view back on damage");
    assertTrue(assist.cameraNotifications());

    write("{\"cameraMode\": \"fullControl\"}");
    ConfigData scripted = manager().load();
    assertFalse(scripted.cameraDisableOnDamage(), "the script decides when the camera leaves");
    assertFalse(scripted.cameraNotifications(), "a notice could enter a capture");
  }

  @Test
  void aWrittenValueBeatsTheModeDefault() throws Exception {
    write(
        "{\"cameraMode\": \"assist\", \"cameraDisableOnDamage\": false,"
            + " \"cameraNotifications\": true}");

    ConfigData config = manager().load();

    assertFalse(config.cameraDisableOnDamage());
    assertTrue(config.cameraNotifications());
  }

  @Test
  void restoreVanillaCameraWinsWithoutRewritingTheFilesChoice() throws Exception {
    write("{\"cameraMode\": \"assist\", \"restoreVanillaCamera\": true}");

    ConfigData config = manager().load();

    assertEquals(CameraMode.ASSIST, config.cameraMode(), "the user's choice is kept in the record");
    assertEquals(CameraMode.OFF, config.effectiveCameraMode(), "behaviour uses the effective mode");
  }

  @Test
  void anUnknownModeIsRejectedNamingTheFieldAndTheValue() throws Exception {
    write("{\"cameraMode\": \"spectate\"}");

    IOException thrown = assertThrows(IOException.class, () -> manager().load());

    assertTrue(thrown.getMessage().contains("cameraMode"), "got: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains("spectate"), "got: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains("assist"), "the allowed values are named");
  }

  @Test
  void theModeIsCaseInsensitive() throws Exception {
    write("{\"cameraMode\": \"  ASSIST \"}");

    assertEquals(CameraMode.ASSIST, manager().load().effectiveCameraMode());
  }

  @Test
  void speedsAreBounded() throws Exception {
    write("{\"cameraHorizontalSpeed\": 11}");
    assertThrows(IOException.class, () -> manager().load());

    write("{\"cameraVerticalSpeed\": -0.5}");
    assertThrows(IOException.class, () -> manager().load());

    write("{\"cameraHorizontalSpeed\": 10}");
    assertEquals(10.0, manager().load().cameraHorizontalSpeed(), 1e-9);
  }

  @Test
  void theInteractionModeIsCaseInsensitive() throws Exception {
    write("{\"cameraInteractionMode\": \"player\"}");

    assertEquals(CameraInteractionMode.PLAYER, manager().load().cameraInteractionMode());
  }

  @Test
  void savingWritesTheDocumentedModeNames() throws Exception {
    write("{\"cameraMode\": \"assist\"}");
    ConfigData loaded = manager().load();

    manager().save(loaded);

    String json = Files.readString(manager().configFile());
    assertTrue(json.contains("\"cameraMode\": \"assist\""), "got: " + json);
    assertEquals(CameraMode.ASSIST, manager().load().effectiveCameraMode(), "it round-trips");
  }
}
