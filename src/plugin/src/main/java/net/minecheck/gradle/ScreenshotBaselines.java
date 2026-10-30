package net.minecheck.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Compares a run's captures against a baseline tree, byte for byte.
 *
 * <p>This is the part of the screenshot suite that decides a verdict, kept free of Gradle so it can
 * be tested with three temp directories instead of a game boot. {@link
 * MinecheckScreenshotBaselines} is only the task wrapper around it.
 *
 * <p><b>Why exact bytes, and what makes them possible.</b> What a client draws is a function of the
 * render <em>delta</em> — the wall-clock fraction into the tick — because that fraction moves the
 * sun angle, the lightmap that modulates every lit pixel, the camera's interpolated pose and every
 * animated model. Left at vanilla's fraction, two runs of a frozen scene differ by a level across
 * the whole picture, and the verdict can only ever be a tolerance. The client pins that delta to 1
 * (see {@code ClientPlatform#pinRenderTickDelta}), so the fraction stops being an input and the
 * remaining variables are the scene and the capture profile:
 *
 * <ul>
 *   <li>a capture passes only when every pixel is identical to its baseline;
 *   <li>one changed pixel, one level off anywhere, is a failure.
 * </ul>
 *
 * <p>Semantics (Playwright's, deliberately):
 *
 * <ul>
 *   <li>identical pixels ⇒ pass;
 *   <li>missing baseline ⇒ write the actual into the active tree, then <b>fail</b> — a baseline is
 *       reviewed by a human or committed by CI, never invented silently;
 *   <li>{@code update} ⇒ write every actual into the active tree and pass;
 *   <li>a capture whose name starts with {@code _} is ad-hoc: written by the run, never compared;
 *   <li>a baseline with no matching capture is a warning, not a failure (a suite may cover only
 *       some of a tree).
 * </ul>
 */
public final class ScreenshotBaselines {

  /** The capture profile a set of baselines was generated with. */
  public static final String PROFILE_FILE = "profile.json";

  /**
   * How far one colour channel of one pixel may drift and still be the same picture (of 255).
   *
   * <p>Zero, deliberately. The render delta is pinned, the scene is static by construction and the
   * capture profile is fixed, so any difference at all is a change in the picture rather than
   * noise. The shipped spec has always asked for exact bytes; the tolerance is what hid the
   * provenance problems it was meant to expose.
   */
  public static final int CHANNEL_TOLERANCE = 0;

  /** How many pixels may exceed {@link #CHANNEL_TOLERANCE} before the picture is different. */
  public static final int MAX_OUTLIER_PIXELS = 0;

  private ScreenshotBaselines() {}

  /** What one comparison did; the task turns {@link #failures()} into a build failure. */
  public record Result(
      List<String> failures,
      List<String> written,
      List<String> matched,
      List<String> tolerated,
      List<String> skipped,
      List<String> unused) {

    public boolean failed() {
      return !failures.isEmpty();
    }

    public int total() {
      return written.size() + matched.size() + skipped.size();
    }
  }

  /**
   * Compares every {@code *.png} directly inside {@code capturesDir} against {@code activeDir}.
   *
   * @param loader the loader name used in messages ({@code fabric}, {@code forge}, {@code quilt})
   * @param capturesDir the run's {@code screenshots/} directory
   * @param activeDir the baseline directory to compare against and (on update/miss) write into
   * @param update write every actual over the baseline instead of comparing
   */
  public static Result compare(String loader, Path capturesDir, Path activeDir, boolean update)
      throws IOException {
    List<String> failures = new ArrayList<>();
    List<String> written = new ArrayList<>();
    List<String> matched = new ArrayList<>();
    List<String> tolerated = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    List<String> unused = new ArrayList<>();

    List<Path> captures = new ArrayList<>();
    if (Files.isDirectory(capturesDir)) {
      try (Stream<Path> files = Files.list(capturesDir)) {
        files
            .filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
            .forEach(captures::add);
      }
    }
    captures.sort(Comparator.comparing(p -> p.getFileName().toString()));

    Set<String> captureNames = new TreeSet<>();
    for (Path capture : captures) {
      String fileName = capture.getFileName().toString();
      String name = fileName.substring(0, fileName.length() - 4);
      captureNames.add(name);

      if (name.startsWith("_")) {
        skipped.add(loader + "/" + fileName);
        continue;
      }
      Path baseline = activeDir.resolve(fileName);
      if (update) {
        copy(capture, baseline);
        written.add(loader + "/" + fileName);
        continue;
      }
      if (!Files.exists(baseline)) {
        copy(capture, baseline);
        failures.add(
            "no baseline for "
                + loader
                + "/"
                + fileName
                + " — wrote "
                + baseline
                + ", review it and commit it (or re-run with -Pminecheck.screenshots.update=true)");
        continue;
      }
      byte[] actual = Files.readAllBytes(capture);
      byte[] expected = Files.readAllBytes(baseline);
      String label = loader + "/" + fileName;
      if (java.util.Arrays.equals(actual, expected)) {
        matched.add(label);
        continue;
      }
      Difference difference = difference(capture, baseline);
      if (difference.outliers() <= MAX_OUTLIER_PIXELS) {
        matched.add(label);
        if (difference.maxDelta() > 0) {
          tolerated.add(
              label
                  + " (max channel delta "
                  + difference.maxDelta()
                  + ", "
                  + difference.outliers()
                  + " pixel(s) over "
                  + CHANNEL_TOLERANCE
                  + ")");
        }
        continue;
      }
      failures.add(
          "screenshot mismatch: "
              + label
              + "\n  actual     "
              + capture
              + "  sha256 "
              + shortSha(actual)
              + "  ("
              + actual.length
              + " B)"
              + "\n  baseline   "
              + baseline
              + "  sha256 "
              + shortSha(expected)
              + "  ("
              + expected.length
              + " B)"
              + "\n  "
              + difference
              + "\n  hint: -Pminecheck.screenshots.update=true re-baselines, or open the uploaded"
              + " artifact to see the difference");
    }

    Path actualProfile = capturesDir.resolve(PROFILE_FILE);
    Path baselineProfile = activeDir.resolve(PROFILE_FILE);
    if (Files.isRegularFile(actualProfile)) {
      if (update || !Files.exists(baselineProfile)) {
        copy(actualProfile, baselineProfile);
      } else if (!java.util.Arrays.equals(
          Files.readAllBytes(actualProfile), Files.readAllBytes(baselineProfile))) {
        failures.add(
            "capture profile changed for "
                + loader
                + " — every baseline of this loader is stale:"
                + "\n  actual     "
                + Files.readString(actualProfile, StandardCharsets.UTF_8).trim()
                + "\n  baseline   "
                + Files.readString(baselineProfile, StandardCharsets.UTF_8).trim()
                + "\n  hint: regenerate the baselines with -Pminecheck.screenshots.update=true");
      }
    }

    if (Files.isDirectory(activeDir)) {
      try (Stream<Path> files = Files.list(activeDir)) {
        files
            .filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
            .forEach(
                p -> {
                  String name = p.getFileName().toString();
                  String stem = name.substring(0, name.length() - 4);
                  // An ad-hoc capture is never compared, so an ad-hoc baseline is not a capture
                  // missing from this run either. This matters in CI's determinism gate, where the
                  // "baseline" tree is pass 1's captures and the settle probes differ in number.
                  if (stem.startsWith("_")) {
                    return;
                  }
                  if (!captureNames.contains(stem)) {
                    unused.add(loader + "/" + name);
                  }
                });
      }
    }
    unused.sort(String::compareTo);

    return new Result(failures, written, matched, tolerated, skipped, unused);
  }

  /**
   * How far two PNGs are apart: the largest per-channel difference anywhere, and how many pixels
   * exceed {@link #CHANNEL_TOLERANCE}. Undecodable files or different dimensions are reported as a
   * total mismatch (every pixel an outlier) so they can never pass.
   */
  private record Difference(int maxDelta, int outliers, int width, int height) {

    @Override
    public String toString() {
      if (width < 0) {
        return "the captures could not be decoded as PNGs";
      }
      return String.format(
          Locale.ROOT,
          "%d of %d pixels differ by more than %d levels (max channel delta %d)",
          outliers,
          width * height,
          CHANNEL_TOLERANCE,
          maxDelta);
    }
  }

  private static Difference difference(Path actual, Path baseline) throws IOException {
    java.awt.image.BufferedImage a = javax.imageio.ImageIO.read(actual.toFile());
    java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(baseline.toFile());
    if (a == null || b == null) {
      return new Difference(Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1);
    }
    if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
      return new Difference(
          Integer.MAX_VALUE, Integer.MAX_VALUE, Math.min(a.getWidth(), b.getWidth()), -1);
    }
    int maxDelta = 0;
    int outliers = 0;
    for (int y = 0; y < a.getHeight(); y++) {
      for (int x = 0; x < a.getWidth(); x++) {
        int p = a.getRGB(x, y);
        int q = b.getRGB(x, y);
        int delta =
            Math.max(
                Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff)),
                Math.max(
                    Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff)),
                    Math.abs((p & 0xff) - (q & 0xff))));
        if (delta > maxDelta) {
          maxDelta = delta;
        }
        if (delta > CHANNEL_TOLERANCE) {
          outliers++;
        }
      }
    }
    return new Difference(maxDelta, outliers, a.getWidth(), a.getHeight());
  }

  private static void copy(Path from, Path to) throws IOException {
    Files.createDirectories(to.getParent());
    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
  }

  private static String shortSha(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder(16);
      for (int i = 0; i < 8; i++) {
        hex.append(String.format("%02x", digest[i]));
      }
      return hex + "…";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }
}
