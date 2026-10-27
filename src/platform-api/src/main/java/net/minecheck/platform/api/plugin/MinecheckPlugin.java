package net.minecheck.platform.api.plugin;

/**
 * Extension point for other mods: register script actions and wait conditions that Lua scripts can
 * then call.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}, so a mod ships one file:
 *
 * <pre>{@code
 * META-INF/services/net.minecheck.platform.api.plugin.MinecheckPlugin
 * }</pre>
 *
 * containing the implementation's fully-qualified class name (one per line). Discovery is
 * loader-agnostic: the mod's jar is on the same classpath as Minecheck on every supported loader,
 * so no per-loader entrypoint or metadata is needed.
 *
 * <p>Registering happens once, before the run's script is loaded. A plugin that throws while
 * registering is skipped with a {@code SEVERE} log line — a broken third-party plugin must not
 * break the harness.
 */
public interface MinecheckPlugin {

  /**
   * Short id used to namespace this plugin's actions ({@code minecheck.ext[<id>][<name>]}) and to
   * name it in errors. Examples: {@code "mymod"}, {@code "globalcontrols"}.
   */
  String id();

  /**
   * Called once before the script runs. Register actions and wait conditions on {@code registry}.
   *
   * @param registry the registry to add names to
   */
  void register(MinecheckRegistry registry);
}
