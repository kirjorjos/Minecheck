package net.minecheck.mixin.yarn;

import java.util.function.BooleanSupplier;
import net.minecheck.common.tick.TickClock;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Drops the server loop's fifty-millisecond cadence while a script asked for uncapped ticks, so a
 * tick starts as soon as the previous one finished and throughput is whatever the machine's
 * milliseconds-per-tick allow.
 *
 * <p>1.21 no longer spells the wait as a {@code Thread.sleep} inside {@code runServer}: the loop
 * ends each tick in {@code runTasksTillTickEnd}, whose blocking {@code runTasks(condition)} call is
 * the loop's only pacing. Skipping that call is the same lever the old redirect pulled; the
 * non-blocking {@code runTasks()} right before it still drains the queue. A paused clock still wins
 * — the gate in the tick event runs the wait later, not sooner.
 *
 * <p>{@code require = 0}: the injection's whole job is an optimisation, and a Minecraft build whose
 * loop spells this differently must keep the vanilla cadence rather than fail to start. {@link
 * TickClock#pacingHook()} reports whether it bound, which is what {@code uncappedTicks} warns on.
 */
@Mixin(net.minecraft.server.MinecraftServer.class)
public class ServerPacingMixin {

  @Redirect(
      method = "runTasksTillTickEnd",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/MinecraftServer;runTasks(Ljava/util/function/BooleanSupplier;)V"),
      require = 0)
  private void minecheck$pace(MinecraftServer server, BooleanSupplier condition) {
    TickClock clock = TickClock.get();
    clock.notePacingHook();
    if (clock.uncapped()) {
      // Nothing to wait for: the loop's next iteration starts the next tick immediately.
      return;
    }
    // The wait also ends when the server is no longer running: a server halted while parked here
    // would otherwise keep its thread (and the whole JVM) alive after the client has stopped.
    server.runTasks(() -> condition.getAsBoolean() || !server.isRunning());
  }
}
