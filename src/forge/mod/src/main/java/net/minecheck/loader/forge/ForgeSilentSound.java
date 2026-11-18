package net.minecheck.loader.forge;

import com.mojang.blaze3d.audio.Library;
import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.common.config.AudioPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Keeps this client silent, when {@code muteAudio} says so.
 *
 * <p>Forge 28 has no Mixin and no event that fires early enough to stop the engine before it
 * starts. {@code SoundSetupEvent} is posted at the end of the engine's constructor, but that
 * happens before a mod's listeners exist — measured: a listener registered from the mod constructor
 * never sees it, while events fired during play are delivered normally. What Forge does offer is
 * the play path: {@code PlaySoundEvent} is posted for every sound before the engine mixes it, and
 * clearing its result sound is Forge's own "play nothing" answer.
 *
 * <p>So silence is enforced twice. Every play is refused through that event, and the device vanilla
 * opened during startup is closed on the first client tick — {@link SoundEngine#destroy()} stops
 * everything, cleans the {@link Library} up (closing the ALC device and context), and marks the
 * engine unloaded. The engine's library is then replaced with one whose {@code init()} fails, so
 * any later resource reload takes vanilla's no-sound-card path instead of opening a device again.
 *
 * <p>Together that is the documented behaviour — nothing is ever mixed or heard, and no device
 * stays open — with the one caveat this loader cannot avoid: the device vanilla opens before any
 * mod runs exists for the few seconds between client start and the first tick.
 */
final class ForgeSilentSound {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Set once the refusal has been reported, so the reason is logged once per run. */
  private static boolean reported;

  /** Set once the startup device has been closed and the library replaced. */
  private static boolean quieted;

  /** Set once closing the device has been attempted, so a failure is not retried every tick. */
  private static boolean quietAttempted;

  private static Field libraryField;

  private static boolean libraryFieldLookedUp;

  private ForgeSilentSound() {}

  /** The config's answer, read through the same cached policy the other loaders use. */
  static boolean muted() {
    return AudioPolicy.muted(FMLPaths.CONFIGDIR.get());
  }

  /**
   * Refuses one play. Forge replaces the sound with whatever the event's result holds, so {@code
   * null} means "play nothing" — the engine never sees it and nothing is mixed.
   */
  static void refuse(PlaySoundEvent event) {
    if (!muted()) {
      return;
    }
    event.setResultSound(null);
    if (!reported) {
      reported = true;
      LOG.info(
          "Minecheck: muteAudio is on — no sound will be played (Forge 28 has no hook before the"
              + " engine starts, so vanilla's startup device is closed on the first client tick)");
    }
  }

  /**
   * Closes the device vanilla opened at startup and makes sure a later reload cannot open another.
   * Called from the client tick, before the camera work, until it has succeeded once.
   */
  static void shutDownDevice() {
    if (quieted || quietAttempted || !muted()) {
      return;
    }
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft == null) {
      return;
    }
    SoundManager manager = minecraft.getSoundManager();
    if (manager == null) {
      return;
    }
    SoundEngine engine = engine(manager);
    if (engine == null) {
      LOG.warning(
          "Minecheck: muteAudio is on, but this client's sound manager has no sound engine —"
              + " audio stays enabled");
      quietAttempted = true;
      return;
    }
    quietAttempted = true;
    try {
      // Before the swap: destroy() is what actually cleans up (and closes) the open device.
      engine.destroy();
      Field field = libraryField(engine.getClass());
      if (field == null) {
        LOG.warning(
            "Minecheck: muteAudio is on, but this sound engine has no device library to refuse —"
                + " a later reload may reopen the device");
        return;
      }
      field.set(engine, new SilentLibrary());
      quieted = true;
      LOG.info(
          "Minecheck: muteAudio is on — closed the audio device vanilla opened at startup; a later"
              + " sound reload stays silent");
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Minecheck: muteAudio is on but the audio device could not be closed — plays are still"
              + " refused, so the run stays silent",
          e);
    }
  }

  /** The sound manager's {@link SoundEngine}, by type — dev and production names differ. */
  private static SoundEngine engine(SoundManager manager) {
    for (Field field : manager.getClass().getDeclaredFields()) {
      if (field.getType() == SoundEngine.class) {
        try {
          field.setAccessible(true);
          return (SoundEngine) field.get(manager);
        } catch (ReflectiveOperationException | RuntimeException e) {
          LOG.log(Level.WARNING, "Minecheck: could not read the sound engine", e);
          return null;
        }
      }
    }
    return null;
  }

  /** The engine's {@link Library} field, by type; {@code null} if this build has none. */
  private static Field libraryField(Class<?> engineType) {
    if (!libraryFieldLookedUp) {
      libraryFieldLookedUp = true;
      for (Field field : engineType.getDeclaredFields()) {
        if (field.getType() == Library.class) {
          field.setAccessible(true);
          libraryField = field;
          break;
        }
      }
    }
    return libraryField;
  }

  /**
   * A device library that fails its own start-up, exactly as a machine with no sound card does.
   *
   * <p>Throwing rather than quietly doing nothing keeps the engine honest: the failure marks it
   * unloaded, which is the state every other sound method already guards for.
   */
  private static final class SilentLibrary extends Library {

    @Override
    public void init() {
      throw new IllegalStateException("Failed to open OpenAL device");
    }
  }
}
