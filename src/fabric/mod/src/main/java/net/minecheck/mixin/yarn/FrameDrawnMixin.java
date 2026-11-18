package net.minecheck.mixin.yarn;

import net.minecheck.platform.yarn.YarnClientPlatform;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts finished frames for the screenshot suite.
 *
 * <p>The lines above this one get the same signal from Fabric's HUD render callback; 1.14's
 * fabric-rendering-v1 has none, so this hooks the method that callback is implemented on: {@code
 * InGameHud#render}, whose tail is a frame drawn with the HUD — chat included — laid out.
 *
 * <p>A capture needs a frame count and not a delay: the read has to pick up a frame drawn
 * <em>after</em> the chat was cleared, and one tick is not one frame (a client that falls behind
 * runs several ticks inside one frame).
 */
@Mixin(InGameHud.class)
public class FrameDrawnMixin {

  @Inject(method = "render", at = @At("TAIL"))
  private void minecheck$frameDrawn(float tickDelta, CallbackInfo ci) {
    YarnClientPlatform.notifyFrameDrawn();
  }
}
