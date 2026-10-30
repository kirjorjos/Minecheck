package net.minecheck.platform.api;

/**
 * Client-side operations backed by the running loader's Minecraft client.
 *
 * <p><b>Threading.</b> Script actions run inside a Lua coroutine, which after its first {@code
 * yield} executes on LuaJ's own {@code Coroutine-N} Java thread — <em>not</em> the client (render)
 * thread. Reads are harmless from there, but anything that <em>mutates</em> client state (screens,
 * input, GLFW, the interaction manager) must be dispatched with {@link
 * #runOnClientThread(Runnable)}: a direct call is a real race — Fabric API's dev check logs {@code
 * Attempted to set screen … outside the render thread. This will likely follow a crash!} for the
 * offenders.
 *
 * <p>Join/leave operations initiate an asynchronous state change; the script engine polls {@link
 * #isInWorld()} / {@link #isOnTitleScreen()} (yielding between ticks) until it completes.
 */
public interface ClientPlatform {

  /**
   * Run {@code action} on the client (render) thread — inline when the caller is already on it,
   * otherwise queued for an upcoming client tick. Queued work therefore has <em>not</em> run yet
   * when this returns; callers that need to observe its effect must yield until it is visible (e.g.
   * poll {@link #isScreenOpen(String)} after {@link #openChat(String)}) rather than assume it
   * happened inline. Used by the script engine to route client-mutating actions (see the threading
   * note above).
   */
  default void runOnClientThread(Runnable action) {
    action.run();
  }

  /** Start joining an existing local (singleplayer) world by name. */
  void joinLocalWorld(String worldName);

  /** Create a new world and start it (the client auto-joins once loaded). */
  void createWorld(CreateWorldOptions options);

  /** Start connecting to a multiplayer server ("host" or "host:port"). */
  void joinServer(String address);

  /**
   * True when {@code address} ("host" or "host:port", port defaulting to 25565) currently accepts a
   * TCP connection — i.e. something is listening there.
   *
   * <p>The {@code joinServer} action probes with this before every Minecraft-level connect attempt,
   * because a connect to a closed port is a guaranteed failure and every failed connect leaves the
   * client on a failure screen — on Forge that is exactly the state a retry collides with. A probe
   * costs one short-lived socket: the server sees a connection that never logs in, which it
   * ignores.
   *
   * <p>Kept on the platform (rather than in the scripting layer) so a test client can answer it
   * without a real listening socket.
   */
  default boolean canReachServer(String address) {
    String host = address;
    int port = 25565;
    int colon = address.lastIndexOf(':');
    if (colon > 0) {
      host = address.substring(0, colon);
      try {
        port = Integer.parseInt(address.substring(colon + 1));
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1); // bracketed IPv6 literal
    }
    try (java.net.Socket socket = new java.net.Socket()) {
      socket.connect(new java.net.InetSocketAddress(host, port), 250);
      return true;
    } catch (java.io.IOException | RuntimeException e) {
      return false;
    }
  }

  /** Leave the current world/server and return to the title screen. */
  void leaveWorld();

  /** Shut down the Minecraft client. */
  void quit();

  /** True when a world is loaded and the local player exists. */
  boolean isInWorld();

  /**
   * True when in a world <em>and</em> ready to interact: the player exists, no screen is open (e.g.
   * the vanilla "downloading terrain" screen right after joining) and no overlay is showing.
   * World-entry actions ({@code createWorld} / {@code joinServer} / {@code joinLocalWorld}) wait
   * for this before returning, so scripts can interact immediately afterwards.
   */
  boolean isWorldReady();

  /** True when on the title screen (no world loaded, not connecting). */
  boolean isOnTitleScreen();

  /**
   * True while a server connection attempt is in flight (e.g. the vanilla ConnectScreen is up). The
   * {@code joinServer} action uses this to avoid stacking connect attempts: it only re-initiates
   * after a previous attempt has concluded (failed, returned to a non-connecting screen) — so a
   * slow dedicated server (still generating its world) is retried instead of failing on the first
   * "server is still starting" disconnect.
   *
   * <p>Best-effort: a connection is <em>not</em> over when the connect screen goes away — the login
   * keeps the client without a player for a while afterwards (Forge's registry handshake takes over
   * a second here), and on Forge no screen is up for part of that window either.
   */
  default boolean isConnecting() {
    return false;
  }

  /**
   * True when the client holds a live connection to a server but has no player yet — the login has
   * been accepted and is still being finished (the server's registry snapshot applied, the level
   * built). False when not connected at all, and false once in a world.
   *
   * <p>This is the window in which {@link #isConnecting()} is already false while a connect attempt
   * is still in flight, so the {@code joinServer} action treats it as busy instead of starting
   * another attempt on top of it. A screen cannot be used as the signal: the login shows no screen
   * of its own, and what is on screen meanwhile belongs to whatever the client was doing before.
   */
  default boolean isLoggingIn() {
    return false;
  }

  /**
   * True when the client is showing a "could not connect" / "disconnected" screen — the visible end
   * of a connect attempt that failed. Together with {@link #isOnTitleScreen()} this is the only
   * state from which {@code joinServer} starts an attempt, because it is the only state that proves
   * the previous attempt is over.
   */
  default boolean isConnectFailed() {
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

  /**
   * Press a key down. {@code key} is either a key binding id (e.g. {@code "key.jump"}) or a key
   * name (e.g. {@code "F3"}); see the Lua action reference for the full resolution rules.
   *
   * <p>Called on the client thread from the script engine. Returns {@code false} (and does nothing)
   * if the key could not be resolved.
   */
  boolean keyDown(String key);

  /** Release a key previously pressed with {@link #keyDown(String)}. No-op if unknown. */
  void keyUp(String key);

  /**
   * Press a mouse button down (0 = left, 1 = right, 2 = middle). Returns {@code false} (and does
   * nothing) if the button cannot be dispatched.
   */
  boolean mouseDown(int button);

  /** Release a mouse button previously pressed with {@link #mouseDown(int)}. */
  void mouseUp(int button);

  /**
   * The local player: name, position (feet block coords), and dimension id. May return {@code null}
   * when not in a world (no player yet).
   */
  PlayerInfo getPlayer();

  /**
   * Set the camera rotation. Pitch: {@code 90} = straight down, {@code -90} = straight up, {@code
   * 0} = horizon. Yaw: {@code 0} = south, positive toward west (vanilla convention).
   */
  void setLook(float pitch, float yaw);

  /** Point the camera at the given world position (eye-to-target). */
  void lookAt(double x, double y, double z);

  /**
   * Raycast from the named entity's eyes along its look direction; returns the block position hit,
   * or {@code null} on a miss or if the entity isn't found. Only the local player is resolvable.
   */
  BlockPosition getTargetBlock(String entityName);

  /**
   * Query the entities the client currently knows about — the ones the server has synced into our
   * loaded chunks, which is what the local player can actually see and reach (and therefore what is
   * worth aiming at). The client-side counterpart of {@link ServerPlatform#findEntities(String,
   * String)}: same {@link EntityInfo} shape, but filtered by <b>type only</b>.
   *
   * <p><b>Why there is no tag filter here:</b> entity scoreboard tags never reach a client in
   * 1.19.2 — no client or network class calls {@code Entity.addTag} and no packet carries the tag
   * set — so {@link EntityInfo#tags()} is always empty client-side and a tag filter could only ever
   * return nothing. Client-side targeting keys off the entity type instead (or a mod coordinates
   * over Minecheck's packet channel); use {@link ServerPlatform#findEntities(String, String)} when
   * a scoreboard tag is the handle.
   *
   * <p>Returns an immutable list. An unknown type yields an empty list rather than an error, and an
   * empty list (never an error) is returned when the client is not in a world.
   *
   * @param type entity type registry id to filter by (e.g. {@code "minecraft:cow"}), or {@code
   *     null} for every entity the client knows about
   */
  java.util.List<EntityInfo> findNearbyEntities(String type);

  /**
   * Execute a chat/console-style command as the local player (sends a command packet to the
   * connected server or integrated world). Commands that need cheats require the player to be op'd
   * on a dedicated server or the world to have cheats enabled. The result count is not observable
   * client-side; callers should verify effects instead. Throws when not connected to a world.
   */
  void runCommand(String command);

  /**
   * Right-click (use item on) the block currently under the local player's crosshair. Routes
   * through the vanilla interaction manager directly (the same call the mouse handler makes), so it
   * is independent of cursor/focus state — used to open container GUIs deterministically. No-op
   * when the crosshair is not on a block. Throws when not in a world.
   */
  void useItemOnTarget();

  /**
   * Click the currently open screen at scaled GUI coordinates ({@code 0..scaledWidth} / {@code
   * 0..scaledHeight}), dispatching {@code mouseClicked} directly to the screen (the same call the
   * mouse handler makes for screens). {@code button}: 0 = left, 1 = right, 2 = middle. Returns
   * {@code true} when a screen was open and the click was routed to it (regardless of whether the
   * screen consumed it), {@code false} when no screen is open.
   */
  boolean clickGui(int button, double x, double y);

  /**
   * Clicks the <em>element</em> with the given id on the open screen, dispatching {@code
   * mouseClicked} to that widget from its own centre.
   *
   * <p>The id is matched against, in order: the widget's label (its displayed text, lower-cased and
   * trimmed — the portable form, since the class name differs per mapping) and the widget's class
   * simple name (e.g. {@code ButtonWidget} on yarn vs {@code Button} on mojmap). {@code index}
   * picks among several matches, 1-based; values below 1 mean the first. Returns {@code false} when
   * no screen is open or nothing matches.
   *
   * <p>Dispatching to the widget itself — rather than through the screen's hit-testing — is what
   * makes this deterministic: the caller named the element, so an overlapping overlay must not
   * swallow the click. Use {@link #clickGui(int, double, double)} to exercise real hit-testing
   * instead.
   */
  boolean clickGuiElement(String elementId, int button, int index);

  /**
   * Send one vanilla container click for the open screen's container (e.g. {@code "pickup"}
   * left-click: pick up the slot's stack, or place the held stack). {@code button}: 0 = left, 1 =
   * right. Action types: {@code "pickup"} (default), {@code "quick_move"}, {@code "swap"}, {@code
   * "clone"}, {@code "throw"}, {@code "quick_craft"}, {@code "pickup_all"}. Returns {@code false}
   * when no container screen is open. The server validates each click against the container's
   * current revision, so callers should let a server round-trip land between dependent clicks.
   */
  boolean clickSlot(int slot, int button, String actionType);

  /**
   * Ask the client to reload its resources — the reload a player triggers with F3+T. Dispatched on
   * the client thread and asynchronous: the reload (and everything registered as a resource-reload
   * listener, e.g. a recipe viewer's index) applies over the following ticks, so callers poll
   * {@link #isResourceReloadSettled()}. Useful for making mods that build their indexes in a reload
   * listener pick up data that was not there when they first started (JEI indexes recipes on world
   * join, before the server's recipe sync has landed).
   */
  void reloadResources();

  /**
   * True when no requested resource reload is still in flight — i.e. the last {@link
   * #reloadResources()} has finished (also true before any request).
   */
  boolean isResourceReloadSettled();

  /**
   * Re-assert the server's time of day on the client's level, once per client tick.
   *
   * <p>The client's clock is <em>not</em> the server's: {@code ClientLevel.tickTime} advances the
   * level's day time every tick, and the server only syncs it about once a second. The drift is
   * invisible to a player (a second of "time" is a fraction of a degree of sun angle) but it is not
   * invisible to a capture: the sky gradient, the sky-light colour and the lightmap that modulates
   * every lit surface are all derived from it, so two captures of the same scene a few ticks apart
   * differ by a rounding step across the whole frame. Pointing the client's clock back at the
   * server's before each frame removes the drift, which makes captures byte-reproducible whether
   * the script froze the clock with {@code gamerule doDaylightCycle false} or let the server's own
   * time stand — and it makes the client's picture what the server says the time is, which is also
   * what a player would see the instant after a sync.
   *
   * <p>Only an <em>integrated</em> server is re-asserted (that is the server this process owns and
   * the one a lifecycle test creates); against a remote server the client's clock is left alone,
   * because there is no authoritative value here to re-assert and stepping a remote world's sky is
   * not this mod's business.
   */
  default void pinClientClock() {}

  /**
   * The partial tick every frame is drawn with once {@link #pinRenderTickDelta()} has run.
   *
   * <p>One is the end of the tick: the pose, the lighting and every animation are drawn at the
   * state the tick left them in, with no interpolation to the next one.
   */
  float PINNED_RENDER_DELTA = 1.0f;

  /**
   * Draw every frame with a fixed partial tick instead of the wall-clock fraction of a tick.
   *
   * <p>The client interpolates between two ticks when it renders: the camera, the lightmap, the sky
   * colour and the pose of every animated model are drawn at {@code previous + (current - previous)
   * * delta}, where delta is how far into the tick the frame happened to land. That fraction comes
   * from wall-clock time, so it is different in every run — which is one of the reasons two
   * captures of the same paused scene differ by a rounding step across the whole frame, and why an
   * animation (the clock item's dial, an idle arm) can sit a frame apart.
   *
   * <p>Pinning the delta removes both: a frame becomes a function of the game state alone, which is
   * what lets the screenshot suite compare captures byte for byte. Tick <em>scheduling</em> is left
   * exactly as vanilla computed it (the real remainder is still carried between frames, so the
   * client ticks at the same rate); only the value handed to the renderer is fixed.
   *
   * <p>Idempotent, and safe to call before the client has booted. A platform that cannot reach its
   * render timer leaves the default no-op and its captures stay wall-clock dependent.
   */
  default void pinRenderTickDelta() {}

  /**
   * Queue a capture of the client's next rendered frame. The platform schedules the GL readback on
   * the render thread, hands the pixels to its PNG encoder, resolves the output path against the
   * run directory ({@code <run>/screenshots/<name>.png}) and reports progress through the request's
   * state.
   *
   * <p>Never blocks and never captures on the calling thread: this is called once per tick by the
   * {@code captureScreenshot} action while it yields, so a platform that needs another tick (for
   * example to let a window resize apply) simply leaves the request queued and is asked again. A
   * change that has to be in the picture — chat emptied out of the HUD — needs a rendered frame
   * rather than a tick, since the client ticks more than once per frame when it falls behind.
   *
   * <p>The default fails the request rather than doing nothing, so a platform without a screenshot
   * implementation surfaces as a clear Lua error instead of a file that never appears.
   */
  default void requestScreenshot(ScreenshotRequest request) {
    request.fail("screenshots are not supported by this platform");
  }

  /**
   * Open the chat screen on the client thread, dispatched asynchronously (see {@link
   * #runOnClientThread(Runnable)}): the screen is <em>not</em> guaranteed to be up when this
   * returns. {@code mode} is {@code "command"} to pre-fill a leading {@code "/"} (so the text is
   * submitted as a command) or anything else for plain chat. Callers poll {@link
   * #isScreenOpen(String) isScreenOpen("chat")} before typing.
   */
  void openChat(String mode);

  /**
   * Type text into a text input on the client, character by character through the vanilla input
   * path (the same {@code charTyped} call a real keyboard makes). Must be called on the client
   * thread (see {@link #runOnClientThread(Runnable)}). {@code target} selects the destination:
   *
   * <ul>
   *   <li>{@code null}/{@code ""}/{@code "field"}: the open screen's focused text field (its first
   *       text field when none is focused). Returns {@code false} when no screen is open or it has
   *       no text field.
   *   <li>{@code "chat"}: the open chat screen (open it with {@link #openChat(String)} first).
   *   <li>{@code "command"}: the open chat screen, with a leading {@code "/"} prepended when the
   *       field does not already start with one, so the text is submitted as a command.
   * </ul>
   *
   * <p>Text is only typed, never submitted — send it with a key action (e.g. {@code press("enter")}
   * for chat). Returns {@code true} when the characters were dispatched to a text field.
   */
  boolean typeText(String text, String target);

  /**
   * Read the block at integer position {@code pos} from the client world: its registry id and, when
   * the block has one, its block entity NBT. Returns {@code null} when the position is not loaded
   * on the client (the client only knows blocks in chunks the server has sent it), so scripts can
   * tell "air" apart from "not known yet" — poll until the read is non-{@code null} after a
   * server-side change. Throws when not in a world.
   */
  BlockInfo getBlock(BlockPosition pos);

  /**
   * True when a screen matching {@code screenId} is currently open. {@code screenId} selects which
   * screen must be open: {@code null}/{@code ""} accepts any container/handled screen, a known id
   * ({@code "inventory"}, {@code "beacon"}, {@code "chest"}, {@code "crafting"}, {@code "furnace"},
   * {@code "chat"}) matches that screen class, and an unknown id never matches.
   */
  boolean isScreenOpen(String screenId);

  /**
   * Slot count of the open screen's container (see {@link #isScreenOpen(String)} for the {@code
   * screenId} rules). Returns 0 when no matching screen is open.
   */
  int slotCount(String screenId);

  /**
   * Read one slot of the open screen's container (see {@link #isScreenOpen(String)} for the {@code
   * screenId} rules). Slots are indexed exactly like the underlying vanilla container ({@code 0} =
   * first slot). Throws {@link IllegalStateException} when no matching screen is open.
   */
  SlotInfo getSlot(int index, String screenId);
}
