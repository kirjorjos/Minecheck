package net.minecheck.loader.forge;

import cpw.mods.fml.common.Loader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * device vanilla opened during startup is shut down on the first client tick. The engine's own
 * {@code Library} object is asked to clean up, which is what closes the OpenAL device.
 *
 * <p>This line's event carries the replacement as a public {@code result} field (a {@code
 * SoundPoolEntry}) rather than a setter, and holding {@code null} there is what "play nothing" means
 * to it.
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
   * Closes the device vanilla opened at startup. Called from the client tick, before the camera
   * work, until it has been attempted once.
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
    Object library = library(manager);
    if (library == null) {
      LOG.warning(
          "Minecheck: muteAudio is on, but the sound manager has no device library to close —"
              + " plays are still refused, so the run stays silent, but the device stays open");
      return;
    }
    try {
      // Library.cleanup() is what closes the OpenAL device and context.
      Method cleanup = library.getClass().getMethod("cleanup");
      cleanup.setAccessible(true);
      cleanup.invoke(library);
      LOG.info("Minecheck: muteAudio is on — closed the audio device vanilla opened at startup");
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Minecheck: muteAudio is on but the audio device could not be closed — plays are still"
              + " refused, so the run stays silent",
          e);
    }
  }

  /** The sound handler's {@link SoundManager}, by type — dev and production names differ. */
  private static SoundManager soundManager(Minecraft minecraft) {
    try {
      Object handler = minecraft.getSoundHandler();
      for (Field field : handler.getClass().getDeclaredFields()) {
        if (SoundManager.class.isAssignableFrom(field.getType())) {
          field.setAccessible(true);
          return (SoundManager) field.get(handler);
        }
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.log(Level.WARNING, "Minecheck: could not read the sound manager", e);
    }
    return null;
  }

  /** The manager's {@code Library} field, by type; {@code null} if this build has none. */
  private static Object library(SoundManager manager) {
    try {
      for (Field field : manager.getClass().getDeclaredFields()) {
        if (field.getType().getName().equals("net.minecraft.client.audio.Library")) {
          field.setAccessible(true);
          return field.get(manager);
        }
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.log(Level.WARNING, "Minecheck: could not read the sound device library", e);
    }
    return null;
  }
}
