package net.minecheck.loader.legacyfabric.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops this line's second shutdown from deadlocking the process.
 *
 * <p>1.8 shuts a dedicated server down twice: {@code MinecraftServer.run} stops it, then calls
 * {@code MinecraftDedicatedServer.exit}, and {@code System.exit} runs a JVM shutdown hook that
 * stops it again. The second stop reaches {@code PlayerManager.disconnectAllPlayers} with a player
 * still listed, and {@code ServerPlayNetworkHandler.disconnect} hands the disconnection to {@code
 * MinecraftServer.submit} and blocks on the answer — work only the server thread can do, and the
 * server thread is the one sitting in {@code System.exit} waiting for the hook. Both threads stay
 * parked for ever, so a scripted run that finished cleanly never exits.
 *
 * <p>The first stop has already saved the worlds and disconnected the players, so a second pass has
 * nothing left to do; this makes it a no-op instead of a deadlock.
 */
@Mixin(MinecraftServer.class)
public class ServerShutdownMixin {

  /** Whether this server has already been stopped, so the repeated stop can be skipped. */
  @Unique private boolean minecheck$stopped;

  @Inject(method = "stopServer", at = @At("HEAD"), cancellable = true, require = 1)
  private void minecheck$stopOnce(CallbackInfo ci) {
    if (minecheck$stopped) {
      ci.cancel();
      return;
    }
    minecheck$stopped = true;
  }
}
