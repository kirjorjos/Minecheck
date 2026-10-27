package net.minecheck.platform.api.plugin;

/**
 * One step of a {@link PluginAction}: either the action is finished (optionally with a result for
 * the script) or it needs another tick.
 *
 * <p>The result is converted to Lua by the bridge. Supported types: {@code null} (nil), {@link
 * Boolean}, {@link Number}, {@link String}, {@link java.util.Map} (string-keyed table) and {@link
 * java.util.List}/arrays (array table); nested values convert recursively. Any other type is passed
 * through as {@code toString()}.
 */
public record PluginStep(boolean finished, Object result) {

  private static final PluginStep WAITING = new PluginStep(false, null);

  /** The action is finished and returns nothing. */
  public static PluginStep done() {
    return new PluginStep(true, null);
  }

  /**
   * The action is finished and returns {@code result} to the script ({@code null} becomes nil).
   *
   * @param result a value the bridge converts (see the class docs)
   */
  public static PluginStep done(Object result) {
    return new PluginStep(true, result);
  }

  /** The action is not finished; call it again on the next game tick. */
  public static PluginStep waiting() {
    return WAITING;
  }
}
