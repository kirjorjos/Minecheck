package net.minecheck.platform.api;

/**
 * Dedicated-server operations backed by the running loader's Minecraft server.
 *
 * <p>All methods are called on the server thread (the script engine is driven from the server tick
 * event), so command execution needs no marshaling.
 */
public interface ServerPlatform {

  /**
   * Execute a console command exactly as if typed at the server console.
   *
   * @param command the command WITHOUT the leading {@code /}
   * @return the command result count (what {@code executeWithPrefix} returns); 0 for commands that
   *     ran nothing or failed
   */
  int runCommand(String command);

  /** True if a player with the given name is currently online. */
  boolean isPlayerOnline(String name);

  /**
   * True once this server's own overworld chunk generation has settled (no pending chunk work).
   * Mirrors {@link ClientPlatform#isWorldGenSettled()} for the dedicated-server case: a server
   * script can wait on this before e.g. asserting worldgen-related state, and it is never "not
   * applicable" since a running server always has its world generated. Called on the server thread;
   * fresh poll each tick.
   */
  default boolean isWorldGenSettled() {
    return true;
  }
}
