package net.minecheck.common.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether the run may open an audio device at all — answered at the moment the sound engine asks,
 * and remembered for the rest of the run.
 *
 * <p>The question is asked from inside the sound engine's device open (see the loader mixins),
 * which the client reaches during startup, and it must not depend on anything having run before:
 * {@link net.minecheck.common.bootstrap.MinecheckBootstrap} is not guaranteed to have executed yet
 * on every loader, so the file is read here, directly, and cached. One run has one answer, so the
 * engine cannot end up half-muted.
 *
 * <p><b>A file that cannot be read falls back to muting.</b> That is the opposite of {@link
 * GameWindowPolicy}'s choice, and deliberately so: a window hidden by a broken config leaves
 * someone with an invisible client and nothing to click, while muted audio is what an ordinary run
 * wants anyway. Nothing in a run checks sound, so the worst a wrong mute costs is silence.
 */
public final class AudioPolicy {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private static volatile Boolean decided;

  private AudioPolicy() {}

  /**
   * The decision, read from {@code <loaderConfigDir>/minecheck/config.json} the first time it is
   * asked for. Missing file and missing field both mean {@link ConfigData#DEFAULT_MUTE_AUDIO}.
   *
   * @param loaderConfigDir the loader's config directory (the parent of {@code minecheck})
   */
  public static boolean muted(Path loaderConfigDir) {
    Boolean cached = decided;
    if (cached != null) {
      return cached;
    }
    synchronized (AudioPolicy.class) {
      if (decided != null) {
        return decided;
      }
      boolean mute = ConfigData.DEFAULT_MUTE_AUDIO;
      try {
        mute = new JsonConfigManager(loaderConfigDir).load().muteAudio();
      } catch (IOException | RuntimeException e) {
        LOG.log(
            Level.WARNING,
            "Minecheck: could not read the config before the sound engine started —"
                + " leaving audio off",
            e);
        mute = ConfigData.DEFAULT_MUTE_AUDIO;
      }
      if (mute) {
        // Logged where the decision is made, not where the device would have been opened: this
        // line existing in a run's output is the proof that the sound hook ran at all.
        LOG.info(
            "Minecheck: muteAudio is on — no audio device will be opened (vanilla reports this once"
                + " as \"Error starting SoundSystem. Turning off sounds & music\"; that line is this"
                + " setting, not a fault)");
      }
      decided = mute;
      return mute;
    }
  }
}
