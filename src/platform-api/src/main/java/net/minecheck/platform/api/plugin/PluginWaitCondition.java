package net.minecheck.platform.api.plugin;

/**
 * A wait condition registered by a plugin. The bridge polls it once per game tick and yields the
 * script's coroutine in between, so a Lua call to it behaves like {@code minecheck.waitFor}: it
 * blocks the script (without blocking the game) until the condition holds, then returns {@code
 * true} — or {@code false} once the default timeout elapses.
 *
 * <p>It runs on the game thread, so it may read game state directly. It must not suspend.
 */
@FunctionalInterface
public interface PluginWaitCondition {

  /**
   * @return true when the script may continue
   */
  boolean satisfied(PluginCall call);
}
