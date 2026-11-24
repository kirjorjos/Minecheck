package net.minecheck.loader.forge;

import cpw.mods.fml.common.Loader;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.common.config.AudioPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundManager;
import net.minecraftforge.client.event.sound.PlaySoundEvent;

/**
 * Keeps this client silent, when {@code muteAudio} says so.
 *
 * <p>1.7.10 has neither Mixin nor an event that fires before the sound engine starts, so silence is
 * enforced twice: {@code PlaySoundEvent} refuses every play before the engine mixes it, and the
 * sound manager is marked unloaded on the first client tick so every sound call returns before it
 * reaches the engine.
 *
 * <p>This line's event carries the replacement as a public {@code result} field (a {@code
 * SoundPoolEntry}) rather than a setter, and holding {@code null} there is what "play nothing"
 * means to it.
 */
final class ForgeSilentSound {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Set once the refusal has been reported, so the reason is logged once per run. */
  private static boolean reported;

  /** Set once closing the device has been attempted, so a failure is not retried every tick. */
  private static boolean quietAttempted;

  private ForgeSilentSound() {}

  /** The config's answer, read through the same cached policy the other loaders use. */
  static boolean muted() {
    return AudioPolicy.muted(Loader.instance().getConfigDir().toPath());
  }

  /** Refuses one play: a {@code null} result is the engine's own "nothing to play". */
  static void refuse(PlaySoundEvent event) {
    if (!muted()) {
      return;
    }
    event.result = null;
    if (!reported) {
      reported = true;
      LOG.info(
          "Minecheck: muteAudio is on — no sound will be played (1.7.10 has no hook before the"
              + " engine starts, so vanilla's startup device is closed on the first client tick)");
    }
  }

  /**
   * Stops vanilla's mixer listening. Called from the client tick, before the camera work, until it
   * has been attempted once.
   *
   * <p>Deliberately not {@code SoundManager.closeMinecraft()}: that runs the paulscode engine's
   * {@code cleanup()}, which drops the engine's command thread but leaves the manager's own {@code
   * loaded} flag set. Every sound call then keeps going through the dead engine, and the first
   * {@code setListener} — which runs once per frame as soon as a world is up — dies with a {@code
   * NullPointerException} in {@code SoundSystem.setListenerPosition} and takes the client down.
   * Clearing {@code loaded} instead is this line's own "no sound" state: every entry point, {@code
   * setListener} included, returns immediately, and nothing is left pointing at a torn-down engine.
   */
  static void shutDownDevice() {
    if (quietAttempted || !muted()) {
      return;
    }
    Minecraft minecraft = Minecraft.getMinecraft();
    if (minecraft == null) {
      return;
    }
    SoundManager manager = soundManager(minecraft);
    if (manager == null) {
      LOG.warning(
          "Minecheck: muteAudio is on, but this client's sound handler has no sound manager —"
              + " audio stays enabled");
      quietAttempted = true;
      return;
    }
    quietAttempted = true;
    try {
      manager.stopAllSounds();
      if (markUnloaded()) {
        LOG.info("Minecheck: muteAudio is on — vanilla's sound manager is unloaded for this run");
      } else {
        LOG.warning(
            "Minecheck: muteAudio is on, but vanilla's sound manager could not be unloaded —"
                + " plays are still refused, so the run stays silent");
      }
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Minecheck: muteAudio is on but the sound manager could not be stopped — plays are still"
              + " refused, so the run stays silent",
          e);
    }
  }

  /**
   * Clears the sound manager's {@code loaded} flag, which is the only thing every entry point of it
   * checks before touching the engine. 1.4.7 keeps it static, so it is written as one; a line that
   * keeps it per instance is handled too, since the field is looked up by name.
   */
  private static boolean markUnloaded() {
    Minecraft minecraft = Minecraft.getMinecraft();
    if (minecraft == null) {
      return false;
    }
    try {
      java.lang.reflect.Field field = SoundManager.class.getDeclaredField("loaded");
      field.setAccessible(true);
      boolean isStatic = java.lang.reflect.Modifier.isStatic(field.getModifiers());
      field.setBoolean(isStatic ? null : minecraft.sndManager, false);
      return true;
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.log(Level.FINE, "Minecheck: could not clear the sound manager's loaded flag", e);
      return false;
    }
  }

  /** The client's {@link SoundManager}. 1.4.7 keeps it in a public field on the client. */
  private static SoundManager soundManager(Minecraft minecraft) {
    return minecraft.sndManager;
  }
}
