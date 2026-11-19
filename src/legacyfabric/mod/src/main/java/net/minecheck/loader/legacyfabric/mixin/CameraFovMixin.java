package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacyCameraEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives the camera its field of view.
 *
 * <p>1.7.10's {@code GameRenderer.getFov} casts the render-view entity to a player outright, and
 * the camera is not one: the cast is the first thing after the debug-zoom check, so a frame drawn
 * while the camera is the viewpoint fails there. Answering the field of view for the camera skips
 * the cast, and 70° is what the method itself uses as its base value, so the picture keeps the
 * vanilla field of view instead of an arbitrary one.
 */
@Mixin(GameRenderer.class)
public class CameraFovMixin {

  @Inject(method = "getFov(FZ)F", at = @At("HEAD"), cancellable = true, require = 0)
  private void minecheck$fovForCamera(
      float tickDelta, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
    MinecraftClient client = MinecraftClient.getInstance();
    if (client != null && client.field_6279 instanceof LegacyCameraEntity) {
      cir.setReturnValue(70.0f);
    }
  }
}
