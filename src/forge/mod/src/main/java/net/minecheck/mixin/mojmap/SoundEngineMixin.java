package net.minecheck.mixin.mojmap;

import com.mojang.blaze3d.audio.Library;
import net.minecheck.common.config.AudioPolicy;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.openal.ALC10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The Forge/Mojmap twin of {@code net.minecheck.mixin.yarn.SoundEngineMixin}: refuses the audio
 * device when {@code muteAudio} says so, so that no ALC context, source set or mixer thread is ever
 * created. The reasoning lives in the Yarn mixin's javadoc — in short, a null device handle makes
 * Mojmap's {@code openDeviceOrFallback} throw the "Failed to open OpenAL device" state exception
 * that {@code SoundEngine} already catches, which is how the engine handles a machine with no sound
 * card.
 *
 * <p>Only the target names differ: in Mojmap the OpenAL wrapper is {@code
 * com.mojang.blaze3d.audio.Library}, and the member that touches the device is {@code
 * tryOpenDevice}.
 *
 * <p>A redirect handler for a static target method must itself be static.
 */
@Mixin(Library.class)
public class SoundEngineMixin {

  @Redirect(
      method = "tryOpenDevice",
      at =
          @At(
              value = "INVOKE",
              target = "Lorg/lwjgl/openal/ALC10;alcOpenDevice(Ljava/lang/CharSequence;)J"))
  private static long minecheck$neverOpenAudioDevice(CharSequence device) {
    if (AudioPolicy.muted(FMLPaths.CONFIGDIR.get())) {
      return 0L;
    }
    return ALC10.alcOpenDevice(device);
  }
}
