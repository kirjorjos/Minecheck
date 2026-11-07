package net.minecheck.mixin.mojmap;

import net.minecheck.loader.forge.ForgeCameraPlatform;
import net.minecraft.client.player.Input;
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
@Mixin(Input.class)
public class InputMixin {

  // 1.19.2 added the partial tick to Input#tick, so the handler has to take both parameters;
  // Mixin matches by descriptor and would otherwise find nothing to inject into.
  @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
  private void minecheck$idlePlayer(boolean movingSlowly, float partialTick, CallbackInfo ci) {
    if (!ForgeCameraPlatform.steering()) {
      return;
    }
    Input input = (Input) (Object) this;
    input.up = false;
    input.down = false;
    input.left = false;
    input.right = false;
    input.jumping = false;
    input.shiftKeyDown = false;
    input.forwardImpulse = 0.0f;
    input.leftImpulse = 0.0f;
    ci.cancel();
  }
}
