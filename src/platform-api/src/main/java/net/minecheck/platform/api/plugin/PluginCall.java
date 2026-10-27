package net.minecheck.platform.api.plugin;

import java.util.Map;

/**
 * The arguments a Lua script passed to a plugin action or wait condition, as plain Java values (no
 * LuaJ types leak into the plugin API).
 *
 * <p>Indexes are 1-based, matching Lua: argument 1 is the first value the script passed.
 * Out-of-range and {@code nil} arguments read as absent: {@code opt*} variants return their
 * fallback whereas the {@code require*} variants throw.
 */
public interface PluginCall {

  /** Number of arguments the script passed (extra trailing {@code nil}s are not counted). */
  int argCount();

  /** True when argument {@code index} is absent or {@code nil}. */
  boolean isNil(int index);

  /** Argument {@code index} as a string, or {@code fallback} when absent/not convertible. */
  String optString(int index, String fallback);

  /** Argument {@code index} as a string; throws a Lua error when absent/not convertible. */
  String requireString(int index);

  /** Argument {@code index} as a number, or {@code fallback} when absent/not a number. */
  double optNumber(int index, double fallback);

  /** Argument {@code index} as a number; throws a Lua error when absent/not a number. */
  double requireNumber(int index);

  /** Argument {@code index} as a boolean, or {@code fallback} when absent. */
  boolean optBool(int index, boolean fallback);

  /**
   * Argument {@code index} as a string-keyed map: a Lua table's keys are stringified ({@code 1},
   * {@code 2}, ... for array entries) and values converted recursively (see {@link #arg}). Nested
   * tables become nested maps. Returns an empty map when the argument is absent or not a table.
   */
  Map<String, Object> tableArg(int index);

  /**
   * The single value at argument {@code index} converted to plain Java: {@code null}, {@link
   * Boolean}, {@link Double}, {@link String}, {@link java.util.List} for a Lua array ({@code {1, 2,
   * 3}}), or {@link Map} for any other table (keys stringified, values converted recursively).
   */
  Object arg(int index);
}
