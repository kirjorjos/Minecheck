package net.minecheck.platform.api.plugin;

/**
 * A script action registered by a plugin.
 *
 * <p>Actions run on the game thread, inside the script's coroutine, so they may read game state
 * directly. To wait for something, return {@link PluginStep#waiting()} instead of blocking: the
 * bridge yields the coroutine and invokes the action again on the next game tick, until it returns
 * a {@link PluginStep#done()} step. A throw propagates as a Lua error, which fails the run — wrap
 * failures in {@link PluginStep#done(Object)} with an error value instead when the script should be
 * able to handle them.
 *
 * <p>Example:
 *
 * <pre>{@code
 * registry.action("greet", call -> PluginStep.done("hello " + call.requireString(1)));
 * }</pre>
 */
@FunctionalInterface
public interface PluginAction {

  /**
   * Advances the action by at most one tick.
   *
   * @param call the script's arguments (stable across steps)
   * @return {@link PluginStep#waiting()} to be called again next tick, or a {@code done} step
   */
  PluginStep run(PluginCall call);
}
