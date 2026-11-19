package net.minecheck.loader.legacyfabric.mixin;

import net.minecheck.loader.legacyfabric.LegacyFabricEntrypoint;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server tick, which is where every server-side script step runs.
 *
 * <p>Same reason as {@link ClientTickMixin}: without Fabric API there is no server tick callback,
 * so both phases of the server's own tick method are injected. The start phase is where a held tick
 * clock parks the server thread, and the end phase is where the script engine resumes.
 *
 * <p>Injected on the integrated server too — that is what makes a client-created world's server
 * rules deterministic — and the entrypoint decides whether that tick also drives the engine (it
 * does not: the client tick is the single driver there).
 */
@Mixin(MinecraftServer.class)
public class ServerTickMixin {

  @Inject(method = "tick", at = @At("HEAD"), require = 1)
  private void minecheck$tickStart(CallbackInfo ci) {
    LegacyFabricEntrypoint.onServerTickStart();
  }

  @Inject(method = "tick", at = @At("TAIL"), require = 1)
  private void minecheck$tickEnd(CallbackInfo ci) {
    LegacyFabricEntrypoint.onServerTickEnd((MinecraftServer) (Object) this);
  }
}
