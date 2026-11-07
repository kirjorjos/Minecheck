package net.minecheck.mixin.mojmap;

import net.minecheck.loader.forge.ForgeCameraPlatform;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mojmap half of the camera's Ctrl+scroll speed shortcut.
 *
 * <p>Same reasoning as the yarn mixin: the scroll callback is the only place a wheel notch is
 * visible before vanilla turns it into a hotbar change, and {@code require = 0} keeps a renamed
 * callback from being a startup crash.
 */
@Mixin(MouseHandler.class)
public class MouseScrollMixin {

  @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
  private void minecheck$cameraSpeed(long window, double xOffset, double yOffset, CallbackInfo ci) {
    if (ForgeCameraPlatform.handleScroll(yOffset)) {
      ci.cancel();
    }
  }
}
