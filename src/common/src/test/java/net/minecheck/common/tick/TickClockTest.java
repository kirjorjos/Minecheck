package net.minecheck.common.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The clock a script owns: a held loop runs nothing, a step runs exactly that many ticks back to
 * back, and a resume un-parks a thread already waiting in the gate.
 *
 * <p>The "server" here is a thread that calls the two loader hooks in order — start of tick, then
 * end of tick — exactly as the entrypoints do.
 */
class TickClockTest {

  private final TickClock clock = new TickClock();
  private final AtomicBoolean stop = new AtomicBoolean();
  private Thread server;

  private void startServer() {
    server =
        new Thread(
            () -> {
              while (!stop.get()) {
                clock.awaitTick();
                clock.countTick();
                sleep(1);
              }
            },
            "fake-server");
    server.setDaemon(true);
    server.start();
    assertTrue(waitUntil(clock::running, 2000), "the fake server never ticked");
  }

  @AfterEach
  void stopServer() {
    stop.set(true);
    clock.resume();
    if (server != null) {
      server.interrupt();
    }
  }

  @Test
  void aHeldClockRunsNothing() {
    startServer();
    clock.pause();
    long atPause = restingTicks();

    sleep(150);

    assertEquals(atPause, clock.ticks(), "a held clock let the world advance");
    assertTrue(clock.paused());
  }

  @Test
  void aStepRunsExactlyTheRequestedTicks() {
    startServer();
    clock.pause();
    long before = restingTicks();
    long steppedBefore = clock.stepped();

    clock.step(5);

    assertTrue(
        waitUntil(() -> clock.stepped() == steppedBefore + 5, 2000), "the step never finished");
    assertEquals(before + 5, clock.ticks(), "a five-tick step ran a different number of ticks");
    assertTrue(clock.paused(), "a step must leave the clock held for the capture that follows");
  }

  @Test
  void steppedTicksDoNotWaitForTheWallClock() {
    startServer();
    clock.pause();
    restingTicks();
    long startedAt = System.nanoTime();

    clock.step(200);

    assertTrue(waitUntil(() -> clock.stepped() >= 200, 5000), "200 stepped ticks never ran");
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
    // 200 ticks are ten seconds of game time at twenty a second; a burst must be far quicker.
    assertTrue(elapsedMillis < 2000, "200 stepped ticks took " + elapsedMillis + " ms");
  }

  @Test
  void resumingUnparksAThreadAlreadyInTheGate() {
    startServer();
    clock.pause();
    long atPause = restingTicks();
    assertTrue(clock.paused());

    clock.resume();

    assertTrue(waitUntil(() -> clock.ticks() > atPause, 2000), "resume left the server parked");
    assertFalse(clock.paused());
  }

  /** Waits until the loop has come to rest on the held clock, and returns that tick count. */
  private long restingTicks() {
    long previous = clock.ticks();
    while (true) {
      sleep(50);
      long now = clock.ticks();
      if (now == previous) {
        return now;
      }
      previous = now;
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean waitUntil(BooleanSupplier condition, long timeoutMillis) {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      sleep(5);
    }
    return condition.getAsBoolean();
  }
}
