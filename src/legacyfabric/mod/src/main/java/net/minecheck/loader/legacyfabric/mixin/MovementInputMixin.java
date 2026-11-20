package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacyFabricCameraPlatform;
import net.minecraft.client.input.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the player idle while the human is flying the camera.
 *
 * <p>Vanilla turns the same movement input into both the body's walk and the camera's look, so a
 * camera run that let the body keep its input walked the player across the scene being filmed. The
 * newer lines zero the input from their own input hook; here the tick that just filled it is the
 * hook.
 */
@Mixin(Input.class)
public class MovementInputMixin {

  @Inject(method = "tick", at = @At("TAIL"), require = 0)
  private void minecheck$idlePlayer(CallbackInfo ci) {
    if (!LegacyFabricCameraPlatform.steering()) {
      return;
    }
    Input input = (Input) (Object) this;
    input.movementForward = 0.0f;
    input.movementSideways = 0.0f;
    input.jumping = false;
    input.sneaking = false;
  }
}
