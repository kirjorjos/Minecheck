package net.minecheck.loader.legacyfabric.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the packets vanilla 1.12 cannot apply any more, instead of letting them kill the client.
 *
 * <p>The handlers below take the client's player or world and dereference it without a guard —
 * Forge 1.12.2's patched {@code NetHandlerPlayClient} has the guard, but the unpatched class this
 * loader runs does not. A packet the server queued just before the client disconnects is then
 * applied to a client that no longer has a world, and because {@code NetworkThreadUtils} hands the
 * work to the main thread through {@code Util.executeTask}, the {@link NullPointerException}
 * rethrows out of the game loop and the process dies with 255.
 *
 * <p>That is a shutdown race, not a feature of any script: the packet has nothing to apply to, so
 * skipping it is what the guarded version does. The guard is the client's own running flag, not a
 * null check on the player: the world-join teleport arrives while the player is still being
 * created, and dropping that one leaves the client on the loading screen forever. A client that is
 * still running always applies the packet; one that is stopping, and whose teardown has already
 * dropped the world, never can. {@code require = 1} keeps a moved method name a boot failure rather
 * than a guard that silently stops existing.
 */
@Mixin(net.minecraft.client.network.ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

  @Inject(method = "onPlayerPositionLook", at = @At("HEAD"), cancellable = true, require = 1)
  private void minecheck$skipPositionLookWhileStopping(
      PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
    if (!MinecraftClient.getInstance().running) ci.cancel();
  }

  @Inject(method = "onWorldTimeUpdate", at = @At("HEAD"), cancellable = true, require = 1)
  private void minecheck$skipTimeUpdateWhileStopping(
      WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
    if (!MinecraftClient.getInstance().running) ci.cancel();
  }

  @Inject(method = "onEntityTrackerUpdate", at = @At("HEAD"), cancellable = true, require = 1)
  private void minecheck$skipTrackerUpdateWhileStopping(
      EntityTrackerUpdateS2CPacket packet, CallbackInfo ci) {
    if (!MinecraftClient.getInstance().running) ci.cancel();
  }
}
