package net.minecheck.gradle;

import java.io.IOException;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
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
      throw new GradleException(
          "Minecheck screenshot baselines FAILED ("
              + loader
              + "):\n\n"
              + String.join("\n\n", result.failures())
              + "\n");
    }
    getLogger()
        .lifecycle(
            "Minecheck: {} of {} {} capture(s) match their baseline (tolerance {} levels)",
            result.matched().size(),
            result.total(),
            loader,
            ScreenshotBaselines.CHANNEL_TOLERANCE);
  }

  /** {@code <baselines>/<loader>} in CI, {@code <local-baselines>/<loader>} otherwise. */
  private Path activeBaselineDirectory(String loader) {
    boolean ci = getCiMode().getOrElse(false);
    Path root = (ci ? getBaselinesDir() : getLocalBaselinesDir()).get().getAsFile().toPath();
    return root.resolve(loader);
  }
}
