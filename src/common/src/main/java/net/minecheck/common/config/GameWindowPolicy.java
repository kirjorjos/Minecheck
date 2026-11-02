package net.minecheck.common.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether the game's own window is created at all — answered at the one moment the answer is still
 * useful, and remembered for the rest of the run.
 *
 * <p>The question is asked from inside Minecraft's window constructor (see the loader mixins),
 * which runs <em>before</em> any mod entrypoint: on Fabric the client's window exists by the time
 * {@code onInitialize} is called, so {@link net.minecheck.common.bootstrap.MinecheckBootstrap}
 * cannot have set this yet. The file is therefore read here, directly, and cached — one run has one
 * answer, and the decision cannot change between the window being created and the bootstrap
 * running.
 *
 * <p><b>A file that cannot be read leaves the window visible.</b> Hiding it is only right when the
 * config says so; a broken config would otherwise leave someone with an invisible client and no
 * window to close, which is a far worse failure than an unexpected window.
 */
public final class GameWindowPolicy {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private static volatile Boolean decided;

  private GameWindowPolicy() {}

  /**
   * The decision, read from {@code <loaderConfigDir>/minecheck/config.json} the first time it is
   * asked for. Missing file and missing field both mean {@link
   * ConfigData#DEFAULT_HIDE_GAME_WINDOW}.
   *
   * @param loaderConfigDir the loader's config directory (the parent of {@code minecheck})
   */
  public static boolean hide(Path loaderConfigDir) {
    Boolean cached = decided;
    if (cached != null) {
      return cached;
    }
    synchronized (GameWindowPolicy.class) {
      if (decided != null) {
        return decided;
      }
      boolean hide = ConfigData.DEFAULT_HIDE_GAME_WINDOW;
      try {
        hide = new JsonConfigManager(loaderConfigDir).load().hideGameWindow();
      } catch (IOException | RuntimeException e) {
        LOG.log(
            Level.WARNING,
            "Minecheck: could not read the config before the game window was created —"
                + " leaving the window visible",
            e);
        hide = false;
      }
      if (hide) {
        // Logged where the decision is made, not where the window is created: this line existing in
        // a run's output is the proof that the window hook ran at all.
        LOG.info("Minecheck: hideGameWindow is on — creating the client's window hidden");
      }
      decided = hide;
      return hide;
    }
  }
}
