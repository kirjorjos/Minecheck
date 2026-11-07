package net.minecheck.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecheck.common.camera.CameraMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mixin-facing gate: whether the camera is installed, decided once per run.
 *
 * <p>The interesting cases are the ones a run actually meets: no config at all, a broken one, and
 * {@code restoreVanillaCamera} disagreeing with {@code cameraMode}. The memo is cleared around each
 * test because one JVM answers for many runs here.
 */
class CameraPolicyTest {

  @TempDir Path tempDir;

  @BeforeEach
  @AfterEach
  void forgetTheMemo() {
    CameraPolicy.reset();
  }

  private void write(String json) throws IOException {
    Path file = tempDir.resolve("minecheck/config.json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, json, StandardCharsets.UTF_8);
  }

  @Test
  void missingFileLeavesTheCameraOff() {
    assertEquals(CameraMode.OFF, CameraPolicy.mode(tempDir));
    assertFalse(CameraPolicy.enabled(tempDir));
  }

  @Test
  void aConfiguredModeEnablesTheCamera() throws Exception {
    write("{\"cameraMode\": \"assist\"}");

    assertTrue(CameraPolicy.enabled(tempDir));
    assertEquals(CameraMode.ASSIST, CameraPolicy.mode(tempDir));
  }

  @Test
  void theAnswerIsMemoisedForTheRun() throws Exception {
    write("{\"cameraMode\": \"assist\"}");
    assertEquals(CameraMode.ASSIST, CameraPolicy.mode(tempDir));

    write("{\"cameraMode\": \"off\"}");

    assertEquals(
        CameraMode.ASSIST, CameraPolicy.mode(tempDir), "one run has one mode, decided at boot");
  }

  @Test
  void restoreVanillaCameraForcesTheCameraOff() throws Exception {
    write("{\"cameraMode\": \"fullControl\", \"restoreVanillaCamera\": true}");

    assertEquals(CameraMode.OFF, CameraPolicy.mode(tempDir));
    assertFalse(CameraPolicy.enabled(tempDir));
  }

  @Test
  void anUnreadableConfigLeavesTheCameraOff() throws Exception {
    write("{ not json");

    assertEquals(CameraMode.OFF, CameraPolicy.mode(tempDir), "a broken file installs nothing");
  }
}
