package net.minecheck.platform.api.plugin;

/**
 * What a {@link MinecheckPlugin} registers into. Names are script-visible identifiers and must be
 * unique across every plugin and must not collide with a built-in {@code minecheck.*} action.
 */
public interface MinecheckRegistry {

  /**
   * True when this run is a dedicated server (no client is loaded).
   *
   * <p>A plugin that can only work on one side should branch on this in {@link
   * MinecheckPlugin#register(MinecheckRegistry)} and register nothing on the side it cannot serve —
   * an action that touches client state would otherwise throw when a server script called it, and
   * the plugin has no other way to know which side it is on at registration time.
   */
  boolean dedicatedServer();

  /**
   * Registers a script action, callable from Lua as {@code minecheck.<name>(...)}.
   *
   * <p>An action may finish immediately (return {@link PluginStep#done()}) or suspend and be
   * re-invoked once per game tick until it returns {@link PluginStep#done(Object)} — see {@link
   * PluginAction}.
   *
   * @param name script-visible name; must not collide with a built-in action or another plugin
   * @param action the action implementation
   */
  void action(String name, PluginAction action);

  /**
   * Registers a wait condition, callable from Lua as {@code minecheck.<name>(...)}. The registered
   * function is a *waiter*: it yields each tick until the condition is satisfied and returns {@code
   * true}, or returns {@code false} once {@code config.defaultTimeout} seconds have elapsed. This
   * is the plugin-side equivalent of {@code minecheck.waitFor}.
   *
   * @param name script-visible name; must not collide with a built-in action or another plugin
   * @param condition the predicate to poll, one call per tick
   */
  void waitCondition(String name, PluginWaitCondition condition);
}
