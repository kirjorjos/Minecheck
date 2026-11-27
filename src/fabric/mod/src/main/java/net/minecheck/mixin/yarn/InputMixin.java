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
  // 1.21.2 collapsed the key state into one `playerInput` record and dropped tick's partial-tick
  // parameter; Mixin matches the descriptor exactly, so the handler takes no arguments now.
  private void minecheck$idlePlayer(CallbackInfo ci) {
    if (!YarnCameraPlatform.steering()) {
      return;
    }
    Input input = (Input) (Object) this;
    input.playerInput = net.minecraft.util.PlayerInput.DEFAULT;
    input.movementForward = 0.0f;
    input.movementSideways = 0.0f;
    ci.cancel();
  }
}
