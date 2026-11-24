package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacyFabricNetworkPlatform;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.server.ServerPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees every serverbound custom payload as it arrives, on the connection it arrived on.
 *
 * <p>The server-side mirror of {@link ClientCustomPayloadMixin}: 1.12.2's play handler is the
 * single receiver per channel, so this is the only place a mod can watch a channel another mod
 * already owns. Nothing is cancelled or replaced — the payload still reaches vanilla and any
 * channel handler below it.
 */
@Mixin(ServerPacketListener.class)
public class ServerCustomPayloadMixin {

  @Inject(method = "onCustomPayload", at = @At("HEAD"), require = 1)
  private void minecheck$payload(CustomPayloadC2SPacket packet, CallbackInfo ci) {
    LegacyFabricNetworkPlatform.onServerPayload(packet);
  }
}
