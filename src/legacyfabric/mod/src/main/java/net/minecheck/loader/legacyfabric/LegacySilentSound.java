package net.minecheck.loader.legacyfabric;

import java.util.logging.Logger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecheck.common.config.AudioPolicy;
import net.minecraft.client.MinecraftClient;

/**
 * Keeps this client silent, when {@code muteAudio} says so.
 *
 * <p>Two halves, because either one alone leaks sound on this line: {@link
 * net.minecheck.loader.legacyfabric.mixin.SoundManagerMixin} refuses every play before the engine
 * mixes it, and the device vanilla opened during startup is closed on the first client tick. The
 * device exists for the few seconds between client start and that tick, which is the same caveat
 * the Forge line carries.
 */
public final class LegacySilentSound {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Set once the refusal has been reported, so the reason is logged once per run. */
  private static boolean reported;

  /** Set once closing the device has been attempted, so a failure is not retried every tick. */
  private static boolean quietAttempted;

  private LegacySilentSound() {}

  /** The config's answer, read through the same cached policy the other loaders use. */
  static boolean muted() {
    return AudioPolicy.muted(FabricLoader.getInstance().getConfigDir());
  }

  /** Whether one play should be dropped. Answers the mixin's question and reports once. */
  public static boolean refuse() {
    if (!muted()) {
      return false;
    }
    if (!reported) {
      reported = true;
      LOG.info(
          "Minecheck: muteAudio is on — no sound will be played (the device vanilla opened at"
              + " startup is closed on the first client tick)");
    }
    return true;
  }

  /**
   * Closes the device vanilla opened at startup. Called from the client tick, before the camera
   * work, until it has been attempted once.
   */
  static void shutDownDevice() {
    if (quietAttempted || !muted()) {
      return;
    }
    quietAttempted = true;
    MinecraftClient minecraft = MinecraftClient.getInstance();
    if (minecraft == null) {
      return;
    }
    try {
      // SoundManager.close() is what tears the system down, which closes the OpenAL device.
      minecraft.getSoundManager().close();
      minecraft.getSoundManager().stopAll();
      LOG.info("Minecheck: muteAudio is on — closed the audio device vanilla opened at startup");
    } catch (RuntimeException e) {
      LOG.warning(
          "Minecheck: muteAudio is on but the audio device could not be closed — plays are still"
              + " refused, so the run stays silent");
    }
  }
}
