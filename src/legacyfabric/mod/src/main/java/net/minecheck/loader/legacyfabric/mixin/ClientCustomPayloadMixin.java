package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacyFabricNetworkPlatform;
import net.minecraft.client.network.ClientLoopbackPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees every clientbound custom payload as it arrives.
 *
 * <p>1.12.2 hands the play handler every custom payload, including channels it does not know, so
 * this hook is both the mod's own receiver and the observation point for another mod's channel — no
 * netty tap is needed below the handler, and one would see nothing anyway.
 *
 * <p>{@code HEAD} and not {@code TAIL}: vanilla's own handling of an unknown channel only logs, but
 * a payload on a channel that is also registered by another mod must be observed whether or not
 * that mod's handler consumes it.
 */
@Mixin(ClientLoopbackPlayNetworkHandler.class)
public class ClientCustomPayloadMixin {

  @Inject(method = "onCustomPayload", at = @At("HEAD"), require = 1)
  private void minecheck$payload(CustomPayloadC2SPacket packet, CallbackInfo ci) {
    LegacyFabricNetworkPlatform.onClientPayload(packet);
  }
}
