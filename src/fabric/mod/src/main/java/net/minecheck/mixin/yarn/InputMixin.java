package net.minecheck.mixin.yarn;

import net.minecheck.platform.yarn.YarnCameraPlatform;
import net.minecraft.client.input.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the player standing still while the camera has the human's movement keys.
 *
 * <p>The keys are read by both the camera and the game, so without this a human flying the camera
 * would also walk their body around unseen — which is exactly what an observer must not be able to
 * see. The game fills this object from the key bindings at the start of the player's own tick, so
 * the fill is cancelled and the fields cleared: the player's movement reads a tick of no input, the
 * same thing an idle player produces.
 *
 * <p>The camera does not depend on this object — it reads the key bindings directly — so cancelling
 * here removes nothing from the camera. {@code require = 0}: a renamed method costs the freeze, not
 * the client.
 */
@Mixin(Input.class)
public class InputMixin {

  @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
  // 1.19.2 added the partial tick to Input#tick; Mixin matches the descriptor exactly, so a
  // handler taking only the boolean fails the whole config at join time.
  private void minecheck$idlePlayer(boolean slowDown, float tickDelta, CallbackInfo ci) {
    if (!YarnCameraPlatform.steering()) {
      return;
    }
    Input input = (Input) (Object) this;
    input.pressingForward = false;
    input.pressingBack = false;
    input.pressingLeft = false;
    input.pressingRight = false;
    input.jumping = false;
    input.sneaking = false;
    input.movementForward = 0.0f;
    input.movementSideways = 0.0f;
    ci.cancel();
  }
}
