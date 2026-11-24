package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacyFabricServerPlatform;
import net.minecraft.server.ServerPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Answers the block-writing commands this line does not have.
 *
 * <p>1.4.7's command table holds teleport, give and gamerule, but nothing that writes a block:
 * {@code /setblock} arrives in 1.5 and {@code /fill} in 1.8. A script's line therefore reaches the
 * server as an unknown command and changes nothing, which reads as a placement that silently did
 * not happen. The line is applied through the world here instead, on the thread the command already
 * runs on, and only for an operator — the permission the command itself would have required.
 */
@Mixin(ServerPacketListener.class)
public class ServerCommandMixin {

  @Inject(method = "executeCommand", at = @At("HEAD"), cancellable = true)
  private void minecheck$setBlock(String command, CallbackInfo ci) {
    ServerPacketListener self = (ServerPacketListener) (Object) this;
    if (LegacyFabricServerPlatform.handleChatCommand(self.player, command)) {
      ci.cancel();
    }
  }
}
