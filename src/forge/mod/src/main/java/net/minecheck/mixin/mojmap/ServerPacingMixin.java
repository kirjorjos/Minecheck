package net.minecheck.mixin.mojmap;

import net.minecheck.common.tick.TickClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The Forge counterpart of the yarn {@code ServerPacingMixin}: with uncapped ticks on, the loop
 * stops waiting out the rest of its fifty milliseconds and starts the next tick immediately, so the
 * world runs at the machine's own rate.
 *
 * <p>{@code require = 0}: an optimisation may not break a launch. {@link TickClock#pacingHook()}
 * reports whether it bound, which is what {@code uncappedTicks} warns on.
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
