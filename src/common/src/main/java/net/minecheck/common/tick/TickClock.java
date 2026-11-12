package net.minecheck.common.tick;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The game tick clock, owned by the script instead of by the wall clock.
 *
 * <p>Minecheck starts the server its scripts drive (on a client, the singleplayer world's
 * integrated server), so that server's tick loop is ours to hold: {@link #pause()} stops it before
 * the next tick's world work, {@link #step(long)} releases exactly that many ticks, and {@link
 * #resume()} hands it back to the 20-per-second clock. A released tick runs as soon as the loop
 * comes back round rather than on the next 50 ms boundary, so stepping 400 ticks costs a burst of
 * work instead of twenty seconds of game time — that is what makes a scene reproducible on a slow
 * runner as well as a fast one, and what makes a long test cheaper than real time.
 *
 * <p>With {@link #setUncapped(boolean)} the loop also stops pacing itself, so the ticks come as
 * fast as the machine can run them instead of twenty a second; the client is unaffected, because
 * frames are still drawn at whatever rate the renderer manages.
 *
 * <p>The <em>client</em> is deliberately not gated. The script engine is resumed off the client's
 * own tick and frames are drawn off the client's render loop, so a paused server leaves both alive
 * while the world stands still: a script can pause, capture a frozen frame, and step on.
 *
 * <p><b>Threading.</b> {@link #awaitTick()} runs on the server thread (from the loader's
 * start-of-tick event) and blocks there while the clock is held; {@link #step(long)} and friends
 * are called from a Lua coroutine on the client thread. Every field is atomic or volatile for that
 * reason. A pause is a capture-length affair, not a way to idle: a paused server sends nothing, so
 * a client playing on one will eventually time out.
 */
public final class TickClock {

  private static final TickClock GLOBAL = new TickClock();

  /** How recently a tick must have run for {@link #running()} to call this clock live. */
  private static final long RUNNING_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);

  /**
   * One permit per tick the loop may run while held. Fair, so a burst of steps is consumed in the
   * order the script asked for them and no thread can starve another.
   */
  private final Semaphore permits = new Semaphore(0, true);

  private final AtomicBoolean paused = new AtomicBoolean();
  private final AtomicBoolean bound = new AtomicBoolean();

  /** Every server tick this process has run, held or free. */
  private final AtomicLong ticks = new AtomicLong();

  /** Ticks that came out of a step budget — what a step call counts to know it finished. */
  private final AtomicLong stepped = new AtomicLong();

  private volatile String owner;
  private volatile long lastTickNanos = Long.MIN_VALUE;

  /**
   * True when the server loop should run on the machine rather than on vanilla's 50 ms cadence.
   * Read by the loaders' pacing hook; cleared by {@link #resume()}, so a run that never asked for
   * it can never leave a server spinning.
   */
  private volatile boolean uncapped;

  /** Set by the loader mixin that owns the loop's sleep; see {@link #pacingHook()}. */
  private volatile boolean pacingHookSeen;

  /** Package-private: one global clock per JVM, and tests make their own. */
  TickClock() {}

  public static TickClock get() {
    return GLOBAL;
  }

  /**
   * Records that the loader wired the gate into its tick events. Called from the entrypoint before
   * any script exists — the clock belongs to the server, not to a run.
   */
  public void bind(String loader) {
    this.owner = loader;
    this.bound.set(true);
  }

  /** True when a loader bound the gate, i.e. the clock can be held at all. */
  public boolean bound() {
    return bound.get();
  }

  /** The loader that bound the gate, for error messages. Never null once {@link #bound()}. */
  public String owner() {
    String boundOwner = owner;
    return boundOwner == null ? "unbound" : boundOwner;
  }

  /** True while the world's ticks are being held. */
  public boolean paused() {
    return paused.get();
  }

  /** Every server tick this process has run, stepped or free. */
  public long ticks() {
    return ticks.get();
  }

  /** Ticks that came out of a step budget. */
  public long stepped() {
    return stepped.get();
  }

  /**
   * True when a server tick has run in this process within the last few seconds — the difference
   * between a clock that can be stepped and a client whose world lives in somebody else's process.
   */
  public boolean running() {
    long last = lastTickNanos;
    return last != Long.MIN_VALUE && System.nanoTime() - last < RUNNING_WINDOW_NANOS;
  }

  /**
   * Holds the clock before the next tick's world work. Permits left over from a step are dropped.
   */
  public void pause() {
    paused.set(true);
    permits.drainPermits();
  }

  /**
   * Releases {@code count} ticks. The loop runs them back to back, so this is the fast path: it is
   * not a request to wait {@code count} twentieths of a second.
   */
  public void step(long count) {
    if (count <= 0) {
      return;
    }
    paused.set(true);
    permits.release((int) count);
  }

  /** True while the loop is free to run faster than twenty ticks a second. */
  public boolean uncapped() {
    return uncapped;
  }

  /**
   * Lets the loop run back to back instead of on the 50 ms cadence: a tick starts as soon as the
   * previous one finished, so throughput is whatever the machine's milliseconds-per-tick allows
   * rather than a fixed twenty a second. Pausing still wins — a held clock runs nothing.
   *
   * <p>Off by default and cleared when the run finishes: this is a deliberate "spend the CPU" mode,
   * not a state a run can fall into. It survives {@link #pause()} and {@link #resume()}, because a
   * script that asked for it means the whole run rather than the stretch between two scenes.
   */
  public void setUncapped(boolean uncapped) {
    this.uncapped = uncapped;
  }

  /**
   * True once a loader's pacing hook has run. It is the difference between an uncapped run and a
   * request that no loader honoured, which a script should not have to guess about.
   */
  public boolean pacingHook() {
    return pacingHookSeen;
  }

  /** Called by the loaders' pacing hook each time it sees the loop reach its sleep. */
  public void notePacingHook() {
    pacingHookSeen = true;
  }

  /** Hands the loop back to the wall clock, and un-parks a thread already waiting in the gate. */
  public void resume() {
    paused.set(false);
    permits.drainPermits();
    // One permit of its own: a thread parked in awaitTick() is waiting on the semaphore, not on the
    // flag, so clearing the flag alone would leave it parked until the next step.
    permits.release();
  }

  /**
   * Called at the start of a server tick. Blocks there while the clock is held, which is what keeps
   * the world from advancing between two captures.
   */
  public void awaitTick() {
    if (!paused.get()) {
      return;
    }
    try {
      permits.acquire();
      // The permit {@link #resume()} hands back is there to un-park this thread, not to run a tick
      // the script did not ask for.
      if (paused.get()) {
        stepped.incrementAndGet();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // An interrupted gate must not leave the server parked forever with nothing to wake it.
      paused.set(false);
    }
  }

  /** Called at the end of a server tick. */
  public void countTick() {
    ticks.incrementAndGet();
    lastTickNanos = System.nanoTime();
  }
}
