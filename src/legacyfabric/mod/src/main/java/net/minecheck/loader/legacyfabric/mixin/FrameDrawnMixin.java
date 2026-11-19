package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacyFabricClientPlatform;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts finished frames for the screenshot suite.
 *
 * <p>The tail of the HUD's render is a frame drawn with the chat laid out, which is the signal a
 * capture waits for: it has to read a frame drawn <em>after</em> the chat was cleared, and one tick
 * is not one frame — a client that falls behind runs several ticks inside one frame.
 */
@Mixin(InGameHud.class)
public class FrameDrawnMixin {

  @Inject(method = "render(F)V", at = @At("TAIL"), require = 0)
  private void minecheck$frameDrawn(float tickDelta, CallbackInfo ci) {
    LegacyFabricClientPlatform.notifyFrameDrawn();
  }
}
