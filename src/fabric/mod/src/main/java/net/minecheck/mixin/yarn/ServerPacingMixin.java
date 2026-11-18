package net.minecheck.mixin.yarn;

import net.minecheck.common.tick.TickClock;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Removes the server loop's fifty-millisecond cadence while a script asked for uncapped ticks.
 *
 * <p>1.14's loop does not sleep for the rest of the tick: it computes the next tick's deadline as
 * {@code Math.max(now + 50, tickStart)} and then spins in {@code runTasks} until that deadline
 * passes, draining the task queue in the meantime. That deadline is therefore the whole cadence —
 * others versions park on a {@code Thread.sleep}, this one parks on a value — so making it the
 * tick's own start time, already in the past, is enough to start the next tick as soon as the queue
 * is empty instead of on the next twentieth of a second.
 *
 * <p>Pausing still wins: a held clock runs no tick, so there is no deadline to reach.
 *
 * <p>{@code require = 1} on purpose. This hook is why {@code uncappedTicks} exists on this line, so
 * a Minecraft build whose loop spells the deadline differently must fail to start rather than
 * quietly keep twenty ticks a second. {@link TickClock#pacingHook()} reports whether it ran, which
 * is what {@code uncappedTicks} warns on.
 */
@Mixin(MinecraftServer.class)
public class ServerPacingMixin {

  /**
   * The redirect handler is an instance method because the method it is injected into — {@code
   * MinecraftServer#run} — is one: Mixin checks the handler against the target method, not against
   * the static call being redirected.
   */
  @Redirect(
      method = "run",
      at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(JJ)J"),
      require = 1)
  private long minecheck$nextTickDeadline(long deadline, long tickStart) {
    TickClock clock = TickClock.get();
    clock.notePacingHook();
    return clock.uncapped() ? tickStart : Math.max(deadline, tickStart);
  }
}
