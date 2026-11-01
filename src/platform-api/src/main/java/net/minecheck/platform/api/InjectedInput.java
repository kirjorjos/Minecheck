package net.minecheck.platform.api;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Marks the window in which Minecheck is dispatching synthetic keyboard/mouse input.
 *
 * <p>Every {@code press}/{@code click}/{@code type} action reaches the game through the vanilla
 * handler that owns the input type — {@code Keyboard#onKey}, {@code MouseHandler#onPress} — which
 * is also where another mod hooks the same input. A mod that owns input for itself therefore cannot
 * tell the two apart by origin, and the interesting case is exactly the one where it must: a
 * detached camera that reroutes the human's physical keys must ignore Minecheck's virtual ones, or
 * a script would fly the camera instead of driving the player.
 *
 * <p>So the dispatch is marked: {@link #run} raises the flag around the dispatch and clears it
 * afterwards, and a mod checks {@link #active()} first. It is deliberately <em>not</em> a Minecraft
 * type — this module has no game dependency — so the check compiles anywhere.
 *
 * <p><b>Scope.</b> A flag, not a thread-local: a mod may consult it from a listener the game
 * invokes on a thread of its own, and being conservative is the point — while a dispatch is in
 * flight, treating input as virtual is the safe answer. Nesting is counted, so a dispatch that runs
 * another (a screen click that presses a key) does not clear it early. The count never goes below
 * zero, so a stray {@link #end()} cannot leave the marker stuck on.
 *
 * <p><b>What is marked.</b> Only input that goes through the vanilla input handlers — the layer a
 * mod sees. Input dispatched straight at a screen or widget ({@code clickGui}, {@code typeText})
 * does not pass through it and is not wrapped.
 */
public final class InjectedInput {

  private static final AtomicInteger DEPTH = new AtomicInteger();

  private InjectedInput() {}

  /** True while Minecheck is dispatching input of its own. Never throws; cheap to poll. */
  public static boolean active() {
    return DEPTH.get() > 0;
  }

  /** Runs {@code action} with the marker raised. */
  public static void run(Runnable action) {
    begin();
    try {
      action.run();
    } finally {
      end();
    }
  }

  /** Calls {@code action} with the marker raised, returning its result. */
  public static <T> T call(Supplier<T> action) {
    begin();
    try {
      return action.get();
    } finally {
      end();
    }
  }

  /** Raises the marker; must be matched by {@link #end()}. */
  public static void begin() {
    DEPTH.incrementAndGet();
  }

  /** Clears one raise of the marker. */
  public static void end() {
    DEPTH.updateAndGet(depth -> depth > 0 ? depth - 1 : 0);
  }
}
