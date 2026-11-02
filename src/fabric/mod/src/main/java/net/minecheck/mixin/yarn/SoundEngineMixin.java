package net.minecheck.mixin.yarn;

import net.fabricmc.loader.api.FabricLoader;
import net.minecheck.common.config.AudioPolicy;
import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.openal.ALC10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Never opens an audio device, when {@code muteAudio} says so.
 *
 * <p>The device open is the first thing that costs anything: every instruction after it in {@code
 * SoundEngine.init} builds an ALC context, reads its capabilities, allocates the static and
 * streaming source sets and starts the pooled source bookkeeping — all of which is work a scripted
 * run does for nobody, on a machine whose window is hidden anyway. Making the open fail skips all
 * of it, because {@code openDeviceOrFallback} throws {@code IllegalStateException("Failed to open
 * OpenAL device")} once no device comes back, and {@code SoundSystem} already catches exactly that:
 * this is the path every machine with no sound card takes, so nothing downstream needs to be taught
 * about the muted case, and {@code SoundEngine}'s own {@code EMPTY_SOURCE_SET}/no-device handling
 * is the one that runs.
 *
 * <p>Returning {@code 0L} — the null device handle — rather than cancelling {@code init} outright
 * is what keeps the run honest: the caller's error path is what marks the engine unavailable, while
 * a cancelled {@code init} would leave {@code SoundSystem} believing audio works and NPE on the
 * first sound it tried to play.
 *
 * <p>A redirect handler for a static target method must itself be static.
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {

  @Redirect(
      method = "openDevice",
      at =
          @At(
              value = "INVOKE",
              target = "Lorg/lwjgl/openal/ALC10;alcOpenDevice(Ljava/lang/CharSequence;)J"))
  private static long minecheck$neverOpenAudioDevice(CharSequence device) {
    if (AudioPolicy.muted(FabricLoader.getInstance().getConfigDir())) {
      return 0L;
    }
    return ALC10.alcOpenDevice(device);
  }
}
