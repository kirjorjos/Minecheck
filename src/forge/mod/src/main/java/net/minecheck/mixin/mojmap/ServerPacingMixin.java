package net.minecheck.mixin.mojmap;

import java.util.function.BooleanSupplier;
import net.minecheck.common.tick.TickClock;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The Forge counterpart of the yarn {@code ServerPacingMixin}: with uncapped ticks on, the loop
 * stops waiting out the rest of its fifty milliseconds and starts the next tick immediately, so the
 * world runs at the machine's own rate.
 *
 * <p>1.21 performs that wait in {@code waitUntilNextTick}, whose {@code managedBlock} call is the
 * loop's only pacing; the {@code runAllTasks()} before it still drains the queue. Skipping the
 * blocking call is the lever the old {@code Thread.sleep} redirect pulled.
 *
 * <p>{@code require = 0}: an optimisation may not break a launch. {@link TickClock#pacingHook()}
 * reports whether it bound, which is what {@code uncappedTicks} warns on.
 */
@Mixin(net.minecraft.server.MinecraftServer.class)
public class ServerPacingMixin {

  @Redirect(
      method = "waitUntilNextTick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/MinecraftServer;managedBlock(Ljava/util/function/BooleanSupplier;)V"),
      require = 0)
  private void minecheck$pace(MinecraftServer server, BooleanSupplier condition) {
    TickClock clock = TickClock.get();
    clock.notePacingHook();
    if (clock.uncapped()) {
      // Nothing to wait for: the loop's next iteration starts the next tick immediately.
      return;
    }
    server.managedBlock(condition);
  }
}
