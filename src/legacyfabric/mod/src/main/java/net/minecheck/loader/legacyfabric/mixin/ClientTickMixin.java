package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacyFabricEntrypoint;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client tick, which is where every client-side script step runs.
 *
 * <p>Legacy Fabric ships the loader and Mixin but not Fabric API, so the tick callbacks the newer
 * lines get from {@code ClientTickEvents} are taken from the client's own tick method here instead.
 * Both phases are injected: the camera and the leave request run before vanilla ticks the player
 * (a rotation the server has already seen must not be sent afterwards), and the script step runs
 * after the tick that produced the frame it may capture.
 *
 * <p>{@code require = 1}: a moved tick method would silently stop every client script, and a client
 * that boots and never runs is worse than one that fails to start.
 */
@Mixin(MinecraftClient.class)
public class ClientTickMixin {

  @Inject(method = "tick", at = @At("HEAD"), require = 1)
  private void minecheck$tickStart(CallbackInfo ci) {
    LegacyFabricEntrypoint.onClientTickStart();
  }

  @Inject(method = "tick", at = @At("TAIL"), require = 1)
  private void minecheck$tickEnd(CallbackInfo ci) {
    LegacyFabricEntrypoint.onClientTickEnd();
  }
}
