package net.minecheck.common.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.common.camera.CameraMode;

/**
 * Whether the camera is installed at all, answered at the moment a hook needs to know and
 * remembered for the rest of the run.
 *
 * <p>The question is asked from inside loaders' camera mixins, which are applied before {@link
 * net.minecheck.common.bootstrap.MinecheckBootstrap} runs on every loader — so the file is read
 * here, directly, exactly as {@link GameWindowPolicy} does for the game window. One run has one
 * answer: the mode cannot change between the first hook and the last.
 *
 * <p><b>A file that cannot be read leaves the camera off.</b> The camera is an addition to a run,
 * and a run whose config is broken should still do its job; treating an unreadable file as "install
 * the camera" would put render-path code into a run that never asked for it.
 */
public final class CameraPolicy {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private static volatile CameraMode decided;

  private CameraPolicy() {}

  /**
   * The effective mode, read from {@code <loaderConfigDir>/minecheck/config.json} the first time it
   * is asked for. A missing file, a missing field and {@code restoreVanillaCamera} all mean {@link
   * CameraMode#OFF}.
   *
   * @param loaderConfigDir the loader's config directory (the parent of {@code minecheck})
   */
  public static CameraMode mode(Path loaderConfigDir) {
    CameraMode cached = decided;
    if (cached != null) {
      return cached;
    }
    synchronized (CameraPolicy.class) {
      if (decided != null) {
        return decided;
      }
      CameraMode mode = ConfigData.DEFAULT_CAMERA_MODE;
      try {
        mode = new JsonConfigManager(loaderConfigDir).load().effectiveCameraMode();
      } catch (IOException | RuntimeException e) {
        LOG.log(
            Level.WARNING,
            "Minecheck: could not read the config before the camera was set up —"
                + " leaving the camera off",
            e);
        mode = CameraMode.OFF;
      }
      if (mode.enabled()) {
        // Logged where the decision is made, not where the first hook runs: this line existing in a
        // run's output is the proof that the camera was installed on purpose.
        LOG.info("Minecheck: cameraMode is " + mode.jsonName() + " — the camera is installed");
      }
      decided = mode;
      return mode;
    }
  }

  /** Whether the camera is installed for this run. */
  public static boolean enabled(Path loaderConfigDir) {
    return mode(loaderConfigDir).enabled();
  }

  /**
   * Forgets the memo, so the next question reads the file again.
   *
   * <p>Public for tests, which answer the question from several configs in one JVM, and for a
   * future config-screen save — a run's mode is decided once, so the only thing a reload can
   * legitimately change is nothing at all.
   */
  public static void reset() {
    decided = null;
  }
}
