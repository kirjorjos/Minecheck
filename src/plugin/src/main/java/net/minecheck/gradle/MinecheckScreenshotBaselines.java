package net.minecheck.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Verifies one loader's screenshot captures against its baseline tree, within a small per-channel
 * tolerance (see {@link ScreenshotBaselines} for why exact bytes cannot work).
 *
 * <p>Runs after the loader's {@code run*Shots} lifecycle task, which writes the PNGs. The verdict
 * is the {@link ScreenshotBaselines comparison}; this class is only the Gradle surface around it.
 *
 * <p><b>Which tree is compared against</b> is the point of {@link #getCiMode()}: locally a capture
 * is compared against {@code tests/local-baselines/<loader>/} (git-ignored scratch generated on
 * this machine), in CI against {@code tests/baselines/<loader>/} (the committed pictures generated
 * by the pinned CI image). Comparing a local capture against CI's pictures would only ever report
 * "different GPU", which says nothing about the code under test.
 *
 * <p>{@code -Pminecheck.screenshots.update=true} rewrites the active tree instead of comparing —
 * the re-baseline path. A missing baseline is written and still fails, so a new capture cannot slip
 * into the tree as a silent pass.
 */
public abstract class MinecheckScreenshotBaselines extends DefaultTask {

  /** Loader name used in messages and as the baseline subdirectory: fabric, forge, quilt. */
  @Input
  public abstract Property<String> getLoader();

  /** The run's {@code screenshots/} directory (captures written by the client). */
  @Internal
  public abstract DirectoryProperty getCapturesDir();

  /** The committed baseline tree ({@code tests/baselines}); each loader has a subdirectory. */
  @Internal
  public abstract DirectoryProperty getBaselinesDir();

  /** The local scratch tree ({@code tests/local-baselines}), used when not in CI mode. */
  @Internal
  public abstract DirectoryProperty getLocalBaselinesDir();

  /** True ⇒ compare against the committed tree; false ⇒ against the local scratch tree. */
  @Input
  public abstract Property<Boolean> getCiMode();

  /** True ⇒ write every actual over the active baseline instead of comparing. */
  @Input
  @Optional
  public abstract Property<Boolean> getUpdate();

  /**
   * True ⇒ compare and *report* but never fail on a mismatch or a missing baseline.
   *
   * <p>This is the pull-request half of the baseline flow: a PR compares its own captures against
   * the base branch's pictures, and a difference there is expected — it is what the PR reports and
   * what the post-merge chore then regenerates on the base branch. The gate that must actually be
   * enforced on a PR is determinism (the same code capturing twice), which the workflow runs as a
   * second pass against the first pass's captures.
   */
  @Input
  @Optional
  public abstract Property<Boolean> getReport();

  /**
   * When set, the tree to compare against, replacing the CI/local baseline resolution.
   *
   * <p>Used by the determinism pass, which points this at the first pass's captures: comparing a
   * run against its own pictures says whether the client renders deterministically, independent of
   * what the base branch has committed.
   */
  @Internal
  public abstract DirectoryProperty getCompareDir();

  /**
   * Where report mode writes the baselines a run diverged from, one {@code tests/baselines/…} path
   * per line. The pull-request workflow concatenates these into the list the comment job posts and
   * the post-merge chore regenerates.
   */
  @Internal
  public abstract RegularFileProperty getReportFile();

  /**
   * The captures, as a file tree rather than a directory property, so a run that produced nothing
   * is reported by this task ("no captures") instead of failing Gradle's input validation.
   */
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public FileCollection getCaptureFiles() {
    return getProject().fileTree(getCapturesDir());
  }

  /**
   * Both baseline trees are inputs: a changed (or newly committed) baseline must re-run the verdict
   * even when the capture code did not change.
   */
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public FileCollection getBaselineFiles() {
    return getProject()
        .fileTree(getBaselinesDir())
        .plus(getProject().fileTree(getLocalBaselinesDir()));
  }

  @TaskAction
  public void compareScreenshots() {
    String loader = getLoader().get();
    Path captures = getCapturesDir().get().getAsFile().toPath();
    Path active = activeBaselineDirectory(loader);
    boolean update = getUpdate().getOrElse(false);

    getLogger()
        .lifecycle(
            "Minecheck: verifying {} screenshots against {}{}",
            loader,
            active,
            update ? " (update mode: writing instead of comparing)" : "");

    ScreenshotBaselines.Result result;
    try {
      result = ScreenshotBaselines.compare(loader, captures, active, update);
    } catch (IOException e) {
      throw new GradleException("Minecheck: could not compare the " + loader + " screenshots", e);
    }

    for (String skipped : result.skipped()) {
      getLogger().lifecycle("Minecheck: {} is an ad-hoc capture — not compared", skipped);
    }
    for (String written : result.written()) {
      getLogger().lifecycle("Minecheck: wrote baseline {}", written);
    }
    for (String tolerated : result.tolerated()) {
      getLogger().lifecycle("Minecheck: {} is within tolerance", tolerated);
    }
    for (String unused : result.unused()) {
      getLogger().warn("Minecheck: baseline {} matches no capture in this run", unused);
    }

    if (result.total() == 0) {
      throw new GradleException(
          "Minecheck: the "
              + loader
              + " screenshots run produced no captures in "
              + captures
              + " — the script did not call captureScreenshot");
    }
    if (result.failed()) {
      String message =
          "Minecheck screenshot baselines FAILED ("
              + loader
              + "):\n\n"
              + String.join("\n\n", result.failures())
              + "\n";
      if (getReport().getOrElse(false)) {
        // Report mode: the divergence is the PR's finding, not its failure. Same text, printed
        // where the workflow and the comment job can pick it up, plus the paths on their own.
        getLogger().warn(message);
        getLogger()
            .lifecycle(
                "Minecheck: {} of {} {} capture(s) differ from the compared tree{}",
                result.failures().size(),
                result.total(),
                loader,
                REPORT_HINT);
        writeReport(result.failures(), active);
      } else {
        throw new GradleException(message);
      }
    }
    getLogger()
        .lifecycle(
            "Minecheck: {} of {} {} capture(s) match their baseline (tolerance {} levels)",
            result.matched().size(),
            result.total(),
            loader,
            ScreenshotBaselines.CHANNEL_TOLERANCE);
  }

  /**
   * An explicit comparison tree, else {@code <baselines>/<loader>} in CI, the local tree otherwise.
   */
  private Path activeBaselineDirectory(String loader) {
    if (getCompareDir().isPresent()) {
      return getCompareDir().get().getAsFile().toPath().resolve(loader);
    }
    boolean ci = getCiMode().getOrElse(false);
    Path root = (ci ? getBaselinesDir() : getLocalBaselinesDir()).get().getAsFile().toPath();
    return root.resolve(loader);
  }

  /** The suffix report mode adds after a divergence count, naming what happens to them. */
  private static final String REPORT_HINT =
      "; they are regenerated on the base branch after the merge";

  /**
   * Writes the baseline paths behind report-mode failures ({@code
   * tests/baselines/<loader>/<file>}), taken from each failure's first line — the comparator starts
   * every block with the label it is about. Anything unparseable is skipped: this list drives a
   * comment and a regeneration, so a missing entry degrades to "not reported", never to a wrong
   * file.
   */
  private void writeReport(List<String> failures, Path active) {
    if (getReportFile().isPresent()) {
      Path file = getReportFile().get().getAsFile().toPath();
      List<String> paths = divergedBaselines(failures);
      if (paths.isEmpty()
          && failures.stream().anyMatch(f -> f.startsWith("capture profile changed"))) {
        // A changed profile says every picture of this loader is stale — the failure names no
        // single file, so the whole loader's set is what the comment and the chore have to name.
        paths = allBaselines(active);
      }
      try {
        Files.createDirectories(file.getParent());
        Files.write(file, paths);
      } catch (IOException e) {
        throw new GradleException("Minecheck: could not write the screenshot report " + file, e);
      }
    }
  }

  /** Every {@code tests/baselines/<loader>/<png>} in a baseline directory, sorted. */
  static List<String> allBaselines(Path activeDir) {
    List<String> paths = new ArrayList<>();
    String loader = activeDir.getFileName().toString();
    try (java.util.stream.Stream<Path> files = Files.list(activeDir)) {
      files
          .filter(p -> p.getFileName().toString().endsWith(".png"))
          .forEach(p -> paths.add("tests/baselines/" + loader + "/" + p.getFileName()));
    } catch (IOException e) {
      return paths;
    }
    java.util.Collections.sort(paths);
    return paths;
  }

  /** The {@code tests/baselines/…} paths named by a comparison's failure blocks, deduplicated. */
  static List<String> divergedBaselines(List<String> failures) {
    List<String> paths = new ArrayList<>();
    for (String failure : failures) {
      String first = failure.split("\n", 2)[0];
      String label = first.replaceFirst("^(no baseline for |screenshot mismatch: )", "").trim();
      String token = label.split(" ", 2)[0];
      if (!token.endsWith(".png")) {
        continue;
      }
      String path = "tests/baselines/" + token;
      if (!paths.contains(path)) {
        paths.add(path);
      }
    }
    java.util.Collections.sort(paths);
    return paths;
  }
}
