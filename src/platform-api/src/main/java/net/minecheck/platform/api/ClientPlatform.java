package net.minecheck.platform.api;

/**
 * Client-side operations backed by the running loader's Minecraft client.
 *
 * <p>All methods are called on the client (render) thread. Join/leave operations initiate an
 * asynchronous state change; the script engine polls {@link #isInWorld()} / {@link
 * #isOnTitleScreen()} (yielding between ticks) until it completes.
 */
public interface ClientPlatform {

  /** Start joining an existing local (singleplayer) world by name. */
  void joinLocalWorld(String worldName);

  /** Create a new world and start it (the client auto-joins once loaded). */
  void createWorld(CreateWorldOptions options);

  /** Start connecting to a multiplayer server ("host" or "host:port"). */
  void joinServer(String address);

  /** Leave the current world/server and return to the title screen. */
  void leaveWorld();

  /** Shut down the Minecraft client. */
  void quit();

  /** True when a world is loaded and the local player exists. */
  boolean isInWorld();

  /** True when on the title screen (no world loaded, not connecting). */
  boolean isOnTitleScreen();

  /**
   * True while a server connection attempt is in flight (e.g. the vanilla ConnectScreen is up). The
   * {@code joinServer} action uses this to avoid stacking connect attempts: it only re-initiates
   * after a previous attempt has concluded (failed, returned to a non-connecting screen) — so a
   * slow dedicated server (still generating its world) is retried instead of failing on the first
   * "server is still starting" disconnect.
   */
  default boolean isConnecting() {
    return false;
  }

  /**
   * True once the client has finished booting (startup splash / resource reload done) and is safe
   * for script actions. Scripts must not start before this: world-entry actions
   * (createWorld/joinLocalWorld) drive the integrated-server loading screen synchronously and
   * deadlock if the SplashOverlay is still up.
   */
  default boolean isBootComplete() {
    return true;
  }

  /**
   * True once world chunk generation has settled and it is safe to leave the current world (e.g.
   * before {@link #leaveWorld()}, so the save captures fully-generated spawn chunks instead of
   * re-generating them on rejoin).
   *
   * <p>Semantics:
   *
   * <ul>
   *   <li><b>Outside a local/integrated world</b> (title screen, connecting to a remote server):
   *       returns {@code true} immediately — not applicable, nothing to wait for. The
   *       implementation logs a console warning so scripts calling it in the wrong place are
   *       obvious.
   *   <li><b>In a local/integrated world</b>: {@code true} once the integrated server's
   *       spawn-region (overworld) chunk generation has no pending work. The client has no
   *       independent chunk generator — it renders the integrated server's output — so the server's
   *       pending-task count is the authoritative "fully generated".
   * </ul>
   *
   * Called on the client (render) thread from the script's wait loop; treat it as a fresh poll
   * every tick.
   */
  default boolean isWorldGenSettled() {
    return true;
  }
}
