package net.minecheck.mixin.mojmap;

import net.minecheck.loader.forge.ForgeCameraPlatform;
import net.minecraft.client.player.ClientInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mojmap half of the idle-player rule: while the camera has the human's movement keys, the
 * game's own input object is cleared instead of filled, so the body stands still like an idle
 * player's rather than walking wherever the camera flies.
 *
 * <p>Same {@code require = 0} reasoning as the yarn mixin: a renamed method costs the freeze, not
 * the client.
 */
@Mixin(ClientInput.class)
public class InputMixin {

  // 1.21.2 renamed the class to ClientInput, collapsed the key state into one `keyPresses`
  // record and dropped tick's partial-tick parameter; Mixin matches the descriptor exactly.
  @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
  private void minecheck$idlePlayer(CallbackInfo ci) {
    if (!ForgeCameraPlatform.steering()) {
      return;
    }
    ClientInput input = (ClientInput) (Object) this;
    input.keyPresses = net.minecraft.world.entity.player.Input.EMPTY;
    input.forwardImpulse = 0.0f;
    input.leftImpulse = 0.0f;
    ci.cancel();
  }
}
