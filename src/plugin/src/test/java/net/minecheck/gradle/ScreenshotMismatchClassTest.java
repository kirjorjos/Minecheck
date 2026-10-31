package net.minecheck.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three mismatch classes, tested without booting a client.
 *
 * <p>The classes are what make the determinism pass a verdict instead of a comparison: an addition
 * or a change must be captured again and reproduce, while a deletion is the opposite claim — the
 * suite must not produce it at all. Without them a deletion is indistinguishable from a capture
 * that vanished between the two passes.
 */
class ScreenshotMismatchClassTest {

  @TempDir Path tempDir;

  private Path captures() {
    return tempDir.resolve("run/client/screenshots");
  }

  private Path baselines() {
    return tempDir.resolve("tests/local-baselines/fabric");
  }

  private void capture(String name, byte[] bytes) throws IOException {
    Files.createDirectories(captures());
    Files.write(captures().resolve(name + ".png"), bytes);
  }

  private void baseline(String name, byte[] bytes) throws IOException {
    Files.createDirectories(baselines());
    Files.write(baselines().resolve(name + ".png"), bytes);
  }

  private static byte[] png(int shade) throws IOException {
    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        image.setRGB(x, y, (shade << 16) | (shade << 8) | shade);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  @Test
  void aDeletionThatStaysAbsentPasses() throws Exception {
    baseline("gone", png(40));

    ScreenshotBaselines.Result result =
        ScreenshotBaselines.compare(
            "fabric",
            captures(),
            baselines(),
            false,
            Set.of("fabric/gone.png"),
            Map.of("fabric/gone.png", "deletion"));

    assertFalse(result.failed(), "a deleted baseline must not fail the rerun by being absent");
  }

  @Test
  void aDeletionThatGeneratesFails() throws Exception {
    capture("gone", png(40));
    baseline("gone", png(40));

    ScreenshotBaselines.Result result =
        ScreenshotBaselines.compare(
            "fabric",
            captures(),
            baselines(),
            false,
            Set.of("fabric/gone.png"),
            Map.of("fabric/gone.png", "deletion"));

    assertTrue(result.failed(), "a capture appearing where the branch deleted one is a failure");
    assertTrue(result.failures().get(0).startsWith("deletion generated"), result.failures().get(0));
  }

  @Test
  void anAdditionMustReproduce() throws Exception {
    Path firstPass = tempDir.resolve("first-pass/fabric");
    Files.createDirectories(firstPass);
    Files.write(firstPass.resolve("new.png"), png(60));
    capture("new", png(200));

    ScreenshotBaselines.Result changed =
        ScreenshotBaselines.compare(
            "fabric",
            captures(),
            firstPass,
            false,
            Set.of("fabric/new.png"),
            Map.of("fabric/new.png", "addition"));
    assertTrue(changed.failed(), "an addition that renders differently twice is nondeterminism");

    capture("new", png(60));
    ScreenshotBaselines.Result same =
        ScreenshotBaselines.compare(
            "fabric",
            captures(),
            firstPass,
            false,
            Set.of("fabric/new.png"),
            Map.of("fabric/new.png", "addition"));
    assertFalse(same.failed(), "an addition that reproduces byte for byte passes");
  }

  @Test
  void anAdditionThatVanishesFails() throws Exception {
    Path firstPass = tempDir.resolve("first-pass/fabric");
    Files.createDirectories(firstPass);

    ScreenshotBaselines.Result result =
        ScreenshotBaselines.compare(
            "fabric",
            captures(),
            firstPass,
            false,
            Set.of("fabric/new.png"),
            Map.of("fabric/new.png", "addition"));

    assertTrue(result.failed(), "a capture that disappears between passes is nondeterminism");
    assertTrue(result.failures().get(0).contains("no capture named"), result.failures().get(0));
  }
}
