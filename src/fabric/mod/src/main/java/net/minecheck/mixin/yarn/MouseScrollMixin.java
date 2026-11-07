package net.minecheck.mixin.yarn;

import net.minecheck.platform.yarn.YarnCameraPlatform;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives Ctrl+scroll to the camera while it is the viewpoint: one notch changes the flight speed and
 * the scroll is not also a hotbar slot change.
 *
 * <p>The scroll callback is the only place a wheel notch is visible before it becomes a hotbar
 * change, so this is the one input method the camera has to reach into. {@code require = 0} on
 * purpose: if a future line renames the callback, the cost should be the speed shortcut, not a
 * client that will not start.
 */
@Mixin(Mouse.class)
public class MouseScrollMixin {

  @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true, require = 0)
  private void minecheck$cameraSpeed(
      long window, double horizontal, double vertical, CallbackInfo ci) {
    if (YarnCameraPlatform.handleScroll(vertical)) {
      ci.cancel();
    }
  }
}
