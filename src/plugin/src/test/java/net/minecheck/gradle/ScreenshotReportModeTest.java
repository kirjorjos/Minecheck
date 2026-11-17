package net.minecheck.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Report mode's two outputs — the path list and the mismatch manifest — tested without a client.
 *
 * <p>The manifest is the only thing the post-merge chore applies: an {@code addition} or {@code
 * change} is copied out of the run's artifact, a {@code deletion} is removed with {@code git rm}. A
 * baseline no capture produced is deliberately a warning rather than a failure, so a write gated on
 * the comparison failing never recorded the deletion, and a picture whose scene was dropped stayed
 * in the tree indefinitely. These pin that it is written for a warning too — and that a run which
 * captured nothing at all still fails instead of declaring every baseline a deletion.
 */
class ScreenshotReportModeTest {

  @TempDir Path tempDir;

  private Path captures() {
    return tempDir.resolve("run/client/screenshots");
  }

  private Path baselines() {
    return tempDir.resolve("tests/baselines/fabric");
  }

  private Path manifest() {
    return tempDir.resolve("build/minecheck/manifest.jsonl");
  }

  private MinecheckScreenshotBaselines task(boolean report) {
    Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
    MinecheckScreenshotBaselines task =
        project.getTasks().create("testFabricShots", MinecheckScreenshotBaselines.class);
    task.getLoader().set("fabric");
    task.getCapturesDir().set(captures().toFile());
    task.getBaselinesDir().set(tempDir.resolve("tests/baselines").toFile());
    task.getLocalBaselinesDir().set(tempDir.resolve("tests/local-baselines").toFile());
    task.getCiMode().set(true);
    task.getReport().set(report);
    task.getReportFile().set(tempDir.resolve("build/minecheck/shots-report/fabric.txt").toFile());
    task.getManifestFile().set(manifest().toFile());
    return task;
  }

  private void capture(String name, byte[] bytes) throws IOException {
    Files.createDirectories(captures());
    Files.write(captures().resolve(name + ".png"), bytes);
  }

  private void baseline(String name, byte[] bytes) throws IOException {
    Files.createDirectories(baselines());
    Files.write(baselines().resolve(name + ".png"), bytes);
  }

  @Test
  void aBaselineNoCaptureProducedIsManifestedAsADeletion() throws Exception {
    capture("world-settled", png(60));
    baseline("world-settled", png(60));
    baseline("inventory-open", png(40));

    task(true).compareScreenshots();

    assertEquals(
        List.of("{\"path\":\"tests/baselines/fabric/inventory-open.png\",\"class\":\"deletion\"}"),
        Files.readAllLines(manifest(), StandardCharsets.UTF_8));
  }

  @Test
  void aMismatchIsStillManifestedAsAChange() throws Exception {
    capture("world-settled", png(60));
    baseline("world-settled", png(40));

    task(true).compareScreenshots();

    assertTrue(
        Files.readString(manifest(), StandardCharsets.UTF_8).contains("\"class\":\"change\""));
  }

  @Test
  void aRunThatCapturedNothingFailsInsteadOfDeletingTheWholeTree() throws Exception {
    baseline("world-settled", png(60));

    assertThrows(GradleException.class, () -> task(true).compareScreenshots());
    assertFalse(
        Files.exists(manifest()),
        "every baseline looks stale without captures, so an empty run must not write deletions");
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
}
