package net.minecheck.loader.legacyfabric;

import java.util.logging.Logger;
import net.minecheck.common.config.AudioPolicy;

/**
 * Keeps this client silent, when {@code muteAudio} says so.
 *
 * <p>There is one half here, not the two the newer lines carry: the sound-system mixin refuses
 * every play before the engine mixes it, and the engine itself is left alone. 1.4.7's sound wrapper
 * keeps calling {@code setListenerPosition} on the paulscode engine every frame, so stopping that
 * engine nulls the state the render loop reads and crashes the client. A refused play never reaches
 * the device, which is the silence that matters.
 */
public final class LegacySilentSound {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Set once the refusal has been reported, so the reason is logged once per run. */
  private static boolean reported;

  /** Set once the one-time report has been made. */
  private static boolean quietAttempted;

  private LegacySilentSound() {}

  /** The config's answer, read through the same cached policy the other loaders use. */
  static boolean muted() {
    return AudioPolicy.muted(LegacyFabricLoaders.get().getConfigDir());
  }

  /** Whether one play should be dropped. Answers the mixin's question and reports once. */
  public static boolean refuse() {
    if (!muted()) {
      return false;
    }
    if (!reported) {
      reported = true;
      LOG.info(
          "Minecheck: muteAudio is on — every play is refused before it reaches the audio device");
    }
    return true;
  }

  /**
   * Reports the silence once, from the first client tick.
   *
   * <p>The device vanilla opened at startup is deliberately not touched: see the class comment.
   */
  static void shutDownDevice() {
    if (quietAttempted || !muted()) {
      return;
    }
    quietAttempted = true;
    LOG.info(
        "Minecheck: muteAudio is on — the audio device is opened by vanilla and left running; no"
            + " play reaches it");
  }
}
