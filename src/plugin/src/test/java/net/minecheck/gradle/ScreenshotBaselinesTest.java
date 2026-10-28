package net.minecheck.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntUnaryOperator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The screenshot verdict, tested without booting a client.
 *
 * <p>The cases that matter are the ones a green build could otherwise hide: a missing baseline that
 * silently passes, a re-baseline that does not actually write, an ad-hoc capture that becomes a
 * committed baseline, and a tolerance that has quietly grown wide enough to accept a broken frame.
 */
class ScreenshotBaselinesTest {

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

  private ScreenshotBaselines.Result compare(boolean update) throws IOException {
    return ScreenshotBaselines.compare("fabric", captures(), baselines(), update);
  }

  /** A solid grey PNG, {@code size} pixels square, offset by {@code shade} levels. */
  private static byte[] png(int size, int shade) throws IOException {
    return png(size, ignored -> shade);
  }

  /** A solid grey PNG whose colour comes from {@code shade}, which is given the pixel index. */
  private static byte[] png(int size, IntUnaryOperator shade) throws IOException {
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int value = Math.max(0, Math.min(255, shade.applyAsInt(y * size + x)));
        image.setRGB(x, y, (value << 16) | (value << 8) | value);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  @Test
  void identicalCapturesPass() throws Exception {
    capture("title", png(16, 40));
    baseline("title", png(16, 40));

    ScreenshotBaselines.Result result = compare(false);

    assertFalse(result.failed());
    assertEquals(1, result.matched().size());
    assertEquals("fabric/title.png", result.matched().get(0));
    assertTrue(result.tolerated().isEmpty(), "identical bytes are not a tolerated difference");
  }

  @Test
  void aFewLevelsOfDriftAreToleratedAndReported() throws Exception {
    // The partial tick moves the sun angle, and with it every lit pixel by a level or so.
    capture("world-settled", png(16, shade -> shade % 2 == 0 ? 100 : 104));
    baseline("world-settled", png(16, shade -> (shade % 2 == 0 ? 100 : 104) + 2));

    ScreenshotBaselines.Result result = compare(false);

    assertFalse(result.failed(), "a two-level drift must not fail a build");
    assertEquals(1, result.matched().size());
    assertEquals(1, result.tolerated().size());
    assertTrue(
        result.tolerated().get(0).contains("max channel delta 2"), result.tolerated().get(0));
  }

  @Test
  void theMeasuredCiDriftPassesButALitFrameShiftDoesNot() throws Exception {
    // Between two CI client processes the whole frame sits a level or two brighter or darker, with
    // a 99.9th percentile of 10 levels: the drift the tolerance is sized for.
    capture("drift", png(16, shade -> Math.min(255, shade + 10)));
    baseline("drift", png(16, shade -> shade));
    assertFalse(compare(false).failed(), "the measured CI drift must not fail a build");

    // One level past the tolerance on the whole frame is a different picture, not noise.
    capture(
        "lit-shift",
        png(16, shade -> Math.min(255, shade + ScreenshotBaselines.CHANNEL_TOLERANCE + 1)));
    baseline("lit-shift", png(16, shade -> shade));

    ScreenshotBaselines.Result result = compare(false);

    assertTrue(result.failed(), "a frame this far off is a change, not drift");
    assertTrue(result.failures().get(0).contains("lit-shift"), result.failures().get(0));
  }

  @Test
  void aDifferentPictureFailsAndNamesBothFiles() throws Exception {
    capture("title", png(16, 200));
    baseline("title", png(16, 60));

    ScreenshotBaselines.Result result = compare(false);

    assertTrue(result.failed());
    String failure = result.failures().get(0);
    assertTrue(failure.contains("screenshot mismatch: fabric/title.png"), failure);
    assertTrue(failure.contains("actual"), failure);
    assertTrue(failure.contains("baseline"), failure);
    assertTrue(
        failure.contains("sha256"),
        "a mismatch must be diagnosable from the log alone, got: " + failure);
    assertTrue(failure.contains("differ by more than"), failure);
  }

  @Test
  void aHandfulOfOutliersPassesButMoreDoNot() throws Exception {
    // A few block edges can land on the other side of a rounding step between runs.
    int allowed = ScreenshotBaselines.MAX_OUTLIER_PIXELS;
    capture("edges", png(16, shade -> shade < allowed ? 0 : 120));
    baseline("edges", png(16, shade -> shade < allowed ? 200 : 120));
    assertFalse(compare(false).failed(), "exactly the allowed number of outliers still passes");

    capture("edges", png(16, shade -> shade <= allowed ? 0 : 120));
    ScreenshotBaselines.Result result = compare(false);
    assertTrue(result.failed(), "one pixel past the allowance is a mismatch");
    assertTrue(
        result.failures().get(0).contains("pixels differ by more than"), result.failures().get(0));
  }

  @Test
  void aCaptureThatIsNotAPngFailsInsteadOfPassing() throws Exception {
    capture("title", new byte[] {1, 2, 3});
    baseline("title", new byte[] {1, 2, 4});

    ScreenshotBaselines.Result result = compare(false);

    assertTrue(result.failed(), "an undecodable capture must never pass");
    assertTrue(result.failures().get(0).contains("could not be decoded"), result.failures().get(0));
  }

  @Test
  void aMissingBaselineIsWrittenAndStillFails() throws Exception {
    capture("inventory-open", png(8, 90));

    ScreenshotBaselines.Result result = compare(false);

    assertTrue(result.failed(), "a new baseline must not pass silently");
    assertTrue(
        result.failures().get(0).contains("no baseline for fabric/inventory-open.png"),
        result.failures().get(0));
    assertTrue(
        Files.readAllBytes(baselines().resolve("inventory-open.png")).length > 0,
        "the actual is left where it can be reviewed");
  }

  @Test
  void updateRewritesTheBaselineAndPasses() throws Exception {
    capture("title", png(8, 200));
    baseline("title", png(8, 10));

    ScreenshotBaselines.Result result = compare(true);

    assertFalse(result.failed());
    assertEquals(1, result.written().size());
    assertTrue(
        Files.readAllBytes(baselines().resolve("title.png")).length > 0,
        "update mode must leave the new baseline in place");
  }

  @Test
  void anAdHocCaptureIsNeverComparedOrWritten() throws Exception {
    capture("_hud-region", png(8, 30));

    ScreenshotBaselines.Result result = compare(false);

    assertFalse(result.failed());
    assertEquals(1, result.skipped().size());
    assertFalse(
        Files.exists(baselines().resolve("_hud-region.png")),
        "an underscore capture is exploratory and must not become a baseline");
  }

  @Test
  void aChangedProfileFailsInsteadOfReportingEveryImage() throws Exception {
    capture("title", png(8, 50));
    baseline("title", png(8, 50));
    Files.writeString(
        captures().resolve(ScreenshotBaselines.PROFILE_FILE), "{\"width\": 1280}", UTF8);
    Files.writeString(
        baselines().resolve(ScreenshotBaselines.PROFILE_FILE), "{\"width\": 854}", UTF8);

    ScreenshotBaselines.Result result = compare(false);

    assertTrue(result.failed());
    assertTrue(
        result.failures().get(0).contains("capture profile changed for fabric"),
        result.failures().get(0));
    assertEquals(1, result.matched().size(), "the pixels themselves still matched");
  }

  @Test
  void aMissingProfileIsCopiedWithTheFirstBaseline() throws Exception {
    capture("title", png(8, 50));
    Files.writeString(captures().resolve(ScreenshotBaselines.PROFILE_FILE), "{}", UTF8);

    compare(false);

    assertTrue(Files.isRegularFile(baselines().resolve(ScreenshotBaselines.PROFILE_FILE)));
  }

  @Test
  void aBaselineNoCaptureCoveredIsAWarning() throws Exception {
    capture("title", png(8, 50));
    baseline("title", png(8, 50));
    baseline("world-settled", png(8, 51));

    ScreenshotBaselines.Result result = compare(false);

    assertFalse(result.failed());
    assertEquals(1, result.unused().size());
    assertEquals("fabric/world-settled.png", result.unused().get(0));
  }

  @Test
  void anEmptyRunComparesNothing() throws Exception {
    ScreenshotBaselines.Result result = compare(false);

    assertFalse(result.failed(), "the task fails on an empty run; the comparison just reports it");
    assertEquals(0, result.total());
  }

  private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
}
