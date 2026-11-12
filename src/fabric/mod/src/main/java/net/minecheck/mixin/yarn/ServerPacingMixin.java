package net.minecheck.mixin.yarn;

import net.minecheck.common.tick.TickClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Drops the server loop's fifty-millisecond cadence while a script asked for uncapped ticks, so a
 * tick starts as soon as the previous one finished and throughput is whatever the machine's
 * milliseconds-per-tick allow.
 *
 * <p>The sleep is the loop's only pacing: every other piece of the tick is the work itself, so
 * replacing the sleep is enough to turn twenty ticks a second into "as many as fit". A paused clock
 * still wins — the gate in the tick event runs the sleep later, not sooner.
 *
 * <p>{@code require = 0}: the injection's whole job is an optimisation, and a Minecraft build whose
 * loop spells this differently must keep the vanilla cadence rather than fail to start. {@link
 * TickClock#pacingHook()} reports whether it bound, which is what {@code uncappedTicks} warns on.
 */
@Mixin(net.minecraft.server.MinecraftServer.class)
public class ServerPacingMixin {

  @Redirect(
      method = "runServer",
      at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V"),
      require = 0)
  private void minecheck$pace(long millis) throws InterruptedException {
    TickClock clock = TickClock.get();
    clock.notePacingHook();
    if (clock.uncapped()) {
      // Nothing to wait for: the loop's next iteration starts the next tick immediately.
      return;
    }
    Thread.sleep(millis);
  }
}
