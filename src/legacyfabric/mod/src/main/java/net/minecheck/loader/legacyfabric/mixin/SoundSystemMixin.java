package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacySilentSound;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops every play when {@code muteAudio} is on.
 *
 * <p>Refusing the play is what makes a muted run silent: the sound never reaches the engine, so
 * nothing is mixed and nothing reaches the device. Legacy Fabric ships no sound event to hook
 * instead, so this is the hook.
 *
 * <p>{@code require = 0}: a moved method disables silence rather than the client, and the device is
 * still stopped on the first tick either way.
 */
@Mixin(SoundSystem.class)
public class SoundSystemMixin {

  @Inject(
      method = "playSound(Ljava/lang/String;FFFFF)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void minecheck$refuse(
      String sound, float volume, float pitch, float x, float y, float z, CallbackInfo ci) {
    if (LegacySilentSound.refuse()) {
      ci.cancel();
    }
  }
}
