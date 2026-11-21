package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacySilentSound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
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
 * still closed on the first tick either way.
 */
@Mixin(SoundManager.class)
public class SoundManagerMixin {

  @Inject(
      method = "play(Lnet/minecraft/client/sound/SoundInstance;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void minecheck$refuse(SoundInstance sound, CallbackInfo ci) {
    if (LegacySilentSound.refuse()) {
      ci.cancel();
    }
  }
}
