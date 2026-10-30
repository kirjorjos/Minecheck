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
 * <p><b>Why exact bytes.</b> A capture is a claim that the client draws the same picture, and the
 * only version of that claim worth committing is one that can be re-checked without judgement: two
 * PNGs are either the same file or they are not. A tolerance would turn every run into a review of
 * how much has drifted, and its per-channel allowance would be spent silently on the very
 * differences a baseline exists to catch — a shifted HUD pixel, a hunting texture edge, a
 * half-transparent overlay. There is no tolerance knob here on purpose (spec D11, D26).
 *
 * <p>Exactness is affordable because a baseline set is only ever compared with captures from the
 * <em>same</em> environment: {@code tests/baselines/} holds pictures produced by the pinned CI
 * image and is compared in CI, {@code tests/local-baselines/} holds this machine's pictures and is
 * compared locally. The two trees are not interchangeable, so a different renderer never has to be
 * excused by a threshold — it is a different tree.
 *
 * <p>Semantics (Playwright's, deliberately):
 *
 * <ul>
 *   <li>equal bytes ⇒ pass;
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

  private ScreenshotBaselines() {}

  /** What one comparison did; the task turns {@link #failures()} into a build failure. */
  public record Result(
      List<String> failures,
      List<String> written,
      List<String> matched,
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
              + difference(capture, baseline)
              + "\n  hint: -Pminecheck.screenshots.update=true re-baselines from this tree, or open"
              + " the uploaded artifact to see the difference");
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
                  if (!captureNames.contains(name.substring(0, name.length() - 4))) {
                    unused.add(loader + "/" + name);
                  }
                });
      }
    }
    unused.sort(String::compareTo);

    return new Result(failures, written, matched, skipped, unused);
  }

  /**
   * How far two PNGs are apart, for the failure message only — the verdict is the byte comparison,
   * this just says whether the picture was nudged or replaced. Undecodable files or different
   * dimensions are reported as a total difference.
   */
  private record Difference(long pixels, long differing, int maxDelta, int width, int height) {

    @Override
    public String toString() {
      if (width < 0) {
        return "the captures could not be decoded as PNGs";
      }
      if (height < 0) {
        return "the captures have different dimensions";
      }
      return String.format(
          Locale.ROOT,
          "%d of %d pixels differ, max channel delta %d (verdict is exact bytes)",
          differing,
          pixels,
          maxDelta);
    }
  }

  private static Difference difference(Path actual, Path baseline) throws IOException {
    java.awt.image.BufferedImage a = javax.imageio.ImageIO.read(actual.toFile());
    java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(baseline.toFile());
    if (a == null || b == null) {
      return new Difference(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1);
    }
    if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
      return new Difference(
          Integer.MAX_VALUE,
          Integer.MAX_VALUE,
          Integer.MAX_VALUE,
          Math.min(a.getWidth(), b.getWidth()),
          -1);
    }
    int maxDelta = 0;
    long differing = 0;
    for (int y = 0; y < a.getHeight(); y++) {
      for (int x = 0; x < a.getWidth(); x++) {
        int p = a.getRGB(x, y);
        int q = b.getRGB(x, y);
        if (p == q) {
          continue;
        }
        differing++;
        int delta =
            Math.max(
                Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff)),
                Math.max(
                    Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff)),
                    Math.abs((p & 0xff) - (q & 0xff))));
        if (delta > maxDelta) {
          maxDelta = delta;
        }
      }
    }
    return new Difference(
        (long) a.getWidth() * a.getHeight(), differing, maxDelta, a.getWidth(), a.getHeight());
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
