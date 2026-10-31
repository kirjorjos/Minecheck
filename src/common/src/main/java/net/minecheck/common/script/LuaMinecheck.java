package net.minecheck.common.script;

import java.util.function.LongSupplier;
import java.util.logging.Logger;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.live.LiveConnection;
import net.minecheck.common.live.LiveException;
import net.minecheck.common.live.LiveListener;
import net.minecheck.common.net.PacketChannel;
import net.minecheck.common.net.PacketValues;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.ServerPlatform;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecheck.platform.api.net.WireReader;
import net.minecheck.platform.api.plugin.PluginCall;
import net.minecheck.platform.api.plugin.PluginStep;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Registers the {@code minecheck} Lua API surface (spec: lua-action-reference.md).
 *
 * <p>All actions run on the client thread inside a coroutine (see ScriptEngine). Actions that need
 * to wait suspend the coroutine with {@code globals.yield} and re-check their condition on the next
 * resume; local state (initiated flags, deadlines) survives across yields.
 */
public final class LuaMinecheck {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** How long to wait for the client thread to run a dispatched action before giving up on it. */
  private static final long CLIENT_THREAD_TIMEOUT_MS = 5000;

  /** How long to wait for {@code openChat} to put the chat screen up before typing into it. */
  private static final long CHAT_OPEN_TIMEOUT_MS = 5000;

  private final Globals globals;
  private final PlatformServices services;
  private final ClientPlatform client;
  private final ServerPlatform server;
  private final ConfigData config;
  private final LongSupplier clock;
  private final ScriptEngine engine;
  private final PacketChannel packets;

  /**
   * Actions and wait conditions registered by third-party plugins (empty when none are present).
   */
  private final PluginRegistry plugins;

  public LuaMinecheck(
      Globals globals,
      PlatformServices services,
      ConfigData config,
      LongSupplier clock,
      ScriptEngine engine,
      PacketChannel packets,
      PluginRegistry plugins) {
    this.globals = globals;
    this.services = services;
    this.client = services.client();
    this.server = services.server();
    this.config = config;
    this.clock = clock;
    this.engine = engine;
    this.packets = packets;
    this.plugins = plugins;
  }

  public void register() {
    LuaTable minecheck = new LuaTable();
    globals.set("minecheck", minecheck);

    // World entry / exit
    minecheck.set("joinLocalWorld", new JoinLocalWorld());
    minecheck.set("createWorld", new CreateWorld());
    minecheck.set("joinServer", new JoinServer());
    minecheck.set("leaveWorld", new LeaveWorld());
    minecheck.set("disconnect", new Disconnect());
    minecheck.set("quit", new Quit());

    // Assertions / result reporting
    minecheck.set("expect", new Expect());
    minecheck.set("fail", new Fail());

    // Utilities
    minecheck.set("log", new LogAction());
    minecheck.set("sleep", new Sleep());
    minecheck.set("wait", new Wait());
    minecheck.set("waitFor", new WaitFor());
    minecheck.set("sleepUntil", new WaitFor());
    minecheck.set("worldGenSettled", new WorldGenSettled());
    minecheck.set("side", new Side());
    minecheck.set("minecraftVersion", new MinecraftVersion());
    minecheck.set("isModLoaded", new IsModLoaded());
    minecheck.set("isInWorld", new IsInWorld());
    minecheck.set("isOnTitleScreen", new IsOnTitleScreen());
    minecheck.set("config", new ConfigView());

    // Input actions (client)
    minecheck.set("press", new Press());
    minecheck.set("pressCombination", new PressCombination());
    minecheck.set("mouse", new MouseAction());
    minecheck.set("keyPress", new KeyPress());
    minecheck.set("mousePress", new MousePress());

    // World / entity queries (client)
    minecheck.set("getPlayer", new GetPlayer());
    minecheck.set("look", new Look());
    minecheck.set("lookAt", new LookAt());
    minecheck.set("getTargetBlock", new GetTargetBlock());
    minecheck.set("getCrosshairBlock", new GetCrosshairBlock());
    minecheck.set("getBlock", new GetBlock());
    minecheck.set("findNearbyEntities", new FindNearbyEntities());

    // GUI / inventory (client)
    minecheck.set("openScreen", new OpenScreen());
    minecheck.set("isScreenOpen", new IsScreenOpen());
    minecheck.set("slotCount", new SlotCount());
    minecheck.set("getSlot", new GetSlot());
    minecheck.set("clickGui", new ClickGui());
    minecheck.set("moveItem", new MoveItem());
    minecheck.set("typeText", new TypeText());
    minecheck.set("reloadResources", new ReloadResources());

    // Screenshots (client)
    minecheck.set("captureScreenshot", new CaptureScreenshot());

    // Recipe viewers (soft dependencies: EMI / JEI / ...)
    minecheck.set("recipeViewer", new RecipeViewerInfo());
    minecheck.set("getRecipeSlots", new GetRecipeSlots());
    minecheck.set("fillFromRecipe", new FillFromRecipe());

    // Dedicated-server actions
    minecheck.set("runCommand", new RunCommand());
    minecheck.set("waitForPlayer", new WaitForPlayer());
    minecheck.set("waitForPlayerLeave", new WaitForPlayerLeave());
    minecheck.set("spawnEntity", new SpawnEntity());
    minecheck.set("spawnBlock", new SpawnBlock());
    minecheck.set("spawnItemDrop", new SpawnItemDrop());
    minecheck.set("spawnStructure", new SpawnStructure());
    minecheck.set("findEntities", new FindEntities());

    // Packets (both sides; the loader supplies the transport)
    minecheck.set("sendPacket", new SendPacket());
    minecheck.set("sendPacketTo", new SendPacketTo());
    minecheck.set("waitForPacket", new WaitForPacket());
    minecheck.set("onPacket", new OnPacket());
    minecheck.set("watchPacketChannel", new WatchPacketChannel());
    minecheck.set("registerPacketDecoder", new RegisterPacketDecoder());
    minecheck.set("bytes", new Bytes());
    minecheck.set("int64", new Int64());
    minecheck.set("reader", new Reader());

    // Live session: a script-owned loopback socket, plus the builtin served breakpoint on top.
    minecheck.set("listen", new Listen());
    minecheck.set("liveAttach", new LiveAttach());

    // Third-party plugins (registered last so their names can be checked against every built-in):
    // flat minecheck.<name> plus the minecheck.ext[<plugin>][<name>] index.
    minecheck.set("ext", new LuaTable());
    registerPlugins(minecheck);
  }

  // ------------------------------------------------------------ plugin extension points

  /**
   * Exposes registered plugin actions and wait conditions to Lua, both flat as {@code
   * minecheck.<name>} and indexed as {@code minecheck.ext[<pluginId>][<name>]}.
   *
   * <p>A name that collides with a built-in action is skipped with a SEVERE log line rather than
   * shadowing it: no third-party mod may silently replace something a script already relies on.
   * Plugin-vs-plugin collisions are already rejected by {@link PluginRegistry} at registration
   * time.
   */
  private void registerPlugins(LuaTable minecheck) {
    LuaTable ext = minecheck.get("ext").checktable();
    int registered = 0;
    for (PluginRegistry.RegisteredAction action : plugins.actions()) {
      if (!claim(minecheck, action.name(), action.pluginId(), "action")) {
        continue;
      }
      PluginActionFn fn = new PluginActionFn(action);
      minecheck.set(action.name(), fn);
      extFor(ext, action.pluginId()).set(action.name(), fn);
      registered++;
    }
    for (PluginRegistry.RegisteredWait wait : plugins.waits()) {
      if (!claim(minecheck, wait.name(), wait.pluginId(), "wait condition")) {
        continue;
      }
      PluginWaitFn fn = new PluginWaitFn(wait);
      minecheck.set(wait.name(), fn);
      extFor(ext, wait.pluginId()).set(wait.name(), fn);
      registered++;
    }
    if (registered > 0) {
      LOG.info(String.format("Minecheck: %s plugin action(s) registered", registered));
    }
  }

  private static boolean claim(LuaTable minecheck, String name, String pluginId, String kind) {
    if (minecheck.get(name).isnil()) {
      return true;
    }
    LOG.severe(
        String.format(
            "Minecheck: plugin '%s' %s '%s' collides with an existing minecheck.%s — skipped",
            pluginId, kind, name, name));
    return false;
  }

  private static LuaTable extFor(LuaTable ext, String pluginId) {
    LuaValue existing = ext.get(pluginId);
    if (existing.istable()) {
      return existing.checktable();
    }
    LuaTable table = new LuaTable();
    ext.set(pluginId, table);
    return table;
  }

  /**
   * Converts a plugin action's result to Lua. The types the plugin API documents pass through
   * (null/Boolean/Number/String/Map/List/array, converted recursively); any other type becomes
   * {@code toString()}, so a plugin's own value class still shows up in the script rather than
   * silently turning into nil.
   */
  private static LuaValue pluginValueToLua(Object v) {
    if (v == null) {
      return LuaValue.NIL;
    }
    if (v instanceof Boolean b) {
      return LuaValue.valueOf(b);
    }
    if (v instanceof Long l) {
      // A 64-bit value (an NBT long tag, or a long a plugin returned): a double would round it,
      // so it keeps its exact form. int/short/byte/float/double are all exact as doubles.
      return LuaInt64.of(l);
    }
    if (v instanceof Number n) {
      return LuaValue.valueOf(n.doubleValue());
    }
    if (v instanceof String s) {
      return LuaValue.valueOf(s);
    }
    if (v instanceof java.util.Map<?, ?> m) {
      LuaTable t = new LuaTable();
      for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
        t.set(String.valueOf(e.getKey()), pluginValueToLua(e.getValue()));
      }
      return t;
    }
    if (v instanceof java.util.List<?> l) {
      LuaTable t = new LuaTable();
      int i = 1;
      for (Object o : l) {
        t.set(i++, pluginValueToLua(o));
      }
      return t;
    }
    if (v.getClass().isArray()) {
      LuaTable t = new LuaTable();
      int n = java.lang.reflect.Array.getLength(v);
      for (int i = 0; i < n; i++) {
        t.set(i + 1, pluginValueToLua(java.lang.reflect.Array.get(v, i)));
      }
      return t;
    }
    return LuaValue.valueOf(String.valueOf(v));
  }

  // ------------------------------------------------------------ helpers

  private void requireClient() {
    if (client == null) {
      throw new LuaError("client-only function called on dedicated server");
    }
  }

  private void requireServer() {
    if (server == null) {
      throw new LuaError("server-only function called on client");
    }
  }

  /**
   * Timeout deadline in millis for the optional timeout argument (seconds) at {@code argIndex},
   * defaulting to config.defaultTimeout.
   */
  private long deadline(Varargs args, int argIndex) {
    double seconds =
        argIndex <= args.narg()
            ? args.arg(argIndex).optdouble(config.defaultTimeout())
            : config.defaultTimeout();
    return clock.getAsLong() + (long) (seconds * 1000);
  }

  /**
   * A world-entry wait's budget: the call's timeout as a ceiling, plus a stall window that only
   * arms once the platform's {@link ClientPlatform#worldLoadProgress()} has been seen to move.
   *
   * <p>A ceiling alone cannot tell a slow machine from a stuck load. Size it for the slowest
   * contributor and a real hang sits there for minutes before anyone hears about it; size it for
   * the usual case and a loaded CI runner fails a perfectly healthy run — which is exactly how the
   * Forge client's first world load came to be reported as "createWorld failed" while the server
   * was still preparing spawn. The progress signal closes that gap: while it moves the load is
   * alive and may take as long as it needs, and once it has not moved for {@link #STALL_MILLIS} it
   * is stuck, so the wait gives up at once.
   *
   * <p>The window stays disarmed until the signal moves, so a platform that reports none (or a load
   * that never reports one) keeps plain ceiling behaviour instead of failing early.
   */
  private static final class WaitBudget {
    /** How long a load that has been seen to move may sit still before it counts as stuck. */
    private static final long STALL_MILLIS = 60_000L;

    private final long ceiling;
    private long lastMovement;
    private float lastSignal = Float.NaN;
    private boolean armed;

    WaitBudget(long ceiling, long now) {
      this.ceiling = ceiling;
      this.lastMovement = now;
    }

    /** Feeds one sample; a change means the load moved, which pushes the stall window out. */
    void observe(long now, float signal) {
      if (signal < 0) {
        return;
      }
      if (!Float.isNaN(lastSignal) && signal != lastSignal) {
        armed = true;
        lastMovement = now;
      }
      lastSignal = signal;
    }

    /** True when the wait must stop: the ceiling is past, or a moving load has stalled. */
    boolean expired(long now) {
      return now > ceiling || (armed && now - lastMovement >= STALL_MILLIS);
    }
  }

  /** Converts a platform NBT map (as in {@code SlotInfo}/{@code BlockInfo}) into a Lua table. */
  private static LuaTable nbtToLua(java.util.Map<String, Object> nbt) {
    LuaTable out = new LuaTable();
    for (java.util.Map.Entry<String, Object> e : nbt.entrySet()) {
      out.set(e.getKey(), valueToLua(e.getValue()));
    }
    return out;
  }

  /** Converts one plain Java NBT value (map/list/string/number/boolean) into a Lua value. */
  private static LuaValue valueToLua(Object v) {
    if (v == null) {
      return LuaValue.NIL;
    }
    if (v instanceof String s) {
      return LuaValue.valueOf(s);
    }
    if (v instanceof Boolean b) {
      return LuaValue.valueOf(b);
    }
    if (v instanceof Long l) {
      // A 64-bit value (an NBT long tag, or a long a plugin returned): a double would round it,
      // so it keeps its exact form. int/short/byte/float/double are all exact as doubles.
      return LuaInt64.of(l);
    }
    if (v instanceof Number n) {
      return LuaValue.valueOf(n.doubleValue());
    }
    if (v instanceof java.util.Map<?, ?> m) {
      LuaTable t = new LuaTable();
      for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
        t.set(e.getKey().toString(), valueToLua(e.getValue()));
      }
      return t;
    }
    if (v instanceof java.util.List<?> l) {
      LuaTable t = new LuaTable();
      int i = 1;
      for (Object o : l) {
        t.set(i++, valueToLua(o));
      }
      return t;
    }
    return LuaValue.NIL;
  }

  private static LuaError wrap(String action, RuntimeException e) {
    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    return new LuaError(action + ": " + msg);
  }

  /** Parses a Lua position table {@code { x =, y =, z = }} into an SNBT-safe double array. */
  private static double[] parsePos(LuaValue pos) {
    LuaTable t = pos.checktable();
    double x = t.get("x").checkdouble();
    double y = t.get("y").checkdouble();
    double z = t.get("z").checkdouble();
    return new double[] {x, y, z};
  }

  /**
   * Serializes a Lua table to an SNBT string the platforms' native parsers understand. A table with
   * only string keys becomes a compound {@code {k:v,...}}; a table with integer keys 1..n becomes a
   * list {@code [v1,...]}; empty tables become {@code {}}. Strings are quoted with backslash/quote
   * escaping; numbers become int/long/double literals; booleans become {@code true}/{@code false}.
   * {@code nil} (absent) NBT is serialized as an empty string.
   */
  private static String toSnbt(LuaValue v) {
    if (v.isnil()) {
      return "";
    }
    // Checked before istable(): an int64 is a table, and a long tag must stay an exact 123L
    // literal rather than being serialized as a compound.
    if (v instanceof LuaInt64 i) {
      return i.exactText() + "L";
    }
    if (v.istable()) {
      LuaTable t = v.checktable();
      boolean hasStringKey = false;
      boolean hasAnyKey = false;
      Varargs n = t.next(LuaValue.NIL);
      while (!n.arg1().isnil()) {
        hasAnyKey = true;
        if (n.arg1().isstring() && !n.arg1().isint()) {
          hasStringKey = true;
          break;
        }
        n = t.next(n.arg1());
      }
      if (!hasAnyKey) {
        return "{}";
      }
      if (hasStringKey) {
        StringBuilder sb = new StringBuilder("{");
        n = t.next(LuaValue.NIL);
        boolean first = true;
        while (!n.arg1().isnil()) {
          if (!first) {
            sb.append(',');
          }
          sb.append(snbtQuote(n.arg1().checkjstring())).append(':').append(toSnbt(n.arg(2)));
          first = false;
          n = t.next(n.arg1());
        }
        return sb.append('}').toString();
      }
      StringBuilder sb = new StringBuilder("[");
      for (int i = 1; i <= t.length(); i++) {
        if (i > 1) {
          sb.append(',');
        }
        sb.append(toSnbt(t.get(i)));
      }
      return sb.append(']').toString();
    }
    if (v.isboolean()) {
      return v.toboolean() ? "true" : "false";
    }
    if (v.isint()) {
      return Integer.toString(v.toint());
    }
    if (v.islong()) {
      return v.tolong() + "L";
    }
    if (v.isnumber()) {
      double d = v.todouble();
      if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
        return (long) d + "L";
      }
      return Double.toString(d);
    }
    if (v.isstring()) {
      return snbtQuote(v.tojstring());
    }
    throw new LuaError("unsupported NBT value type: " + v.typename());
  }

  private static String snbtQuote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' || c == '"') {
        sb.append('\\').append(c);
      } else {
        sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  // ------------------------------------------------------------ world entry / exit

  /** minecheck.joinLocalWorld(name, [timeout]) -> boolean */
  private final class JoinLocalWorld extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String name = args.arg1().checkjstring();
      WaitBudget budget = new WaitBudget(deadline(args, 2), clock.getAsLong());
      boolean initiated = false;
      try {
        while (true) {
          if (!initiated) {
            client.joinLocalWorld(name);
            initiated = true;
          }
          if (client.isWorldReady()) {
            return LuaValue.TRUE;
          }
          long now = clock.getAsLong();
          budget.observe(now, client.worldLoadProgress());
          if (budget.expired(now)) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("joinLocalWorld", e);
      }
    }
  }

  /**
   * minecheck.createWorld(opts, [timeout]) -> boolean
   *
   * <p>opts: name (default "minecheck_world"), seed (number|string, default 0), gamemode (default
   * "creative"), difficulty (default "normal"), structures (default false), mobSpawning (default
   * false), allowCheats (default true), daylightCycle (default false), weatherCycle (default
   * false), insomnia (default false).
   *
   * <p>Deterministic defaults: superflat, no structures, no mob spawning, no daylight/weather
   * cycle, no insomnia — so nothing random can happen (spec §9.3). The client auto-joins once the
   * world is generated; returns true once in-world.
   */
  private final class CreateWorld extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      LuaTable opts = args.arg1().checktable();
      WaitBudget budget = new WaitBudget(deadline(args, 2), clock.getAsLong());
      CreateWorldOptions options = parseOptions(opts);
      boolean initiated = false;
      try {
        while (true) {
          if (!initiated) {
            client.createWorld(options);
            initiated = true;
          }
          if (client.isWorldReady()) {
            return LuaValue.TRUE;
          }
          long now = clock.getAsLong();
          budget.observe(now, client.worldLoadProgress());
          if (budget.expired(now)) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("createWorld", e);
      }
    }

    private CreateWorldOptions parseOptions(LuaTable opts) {
      String name = opts.get("name").optjstring("minecheck_world");
      long seed = parseSeed(opts.get("seed"));
      String gamemode = opts.get("gamemode").optjstring("creative");
      String difficulty = opts.get("difficulty").optjstring("normal");
      boolean structures = opts.get("structures").optboolean(false);
      boolean mobSpawning = opts.get("mobSpawning").optboolean(false);
      boolean allowCheats = opts.get("allowCheats").optboolean(true);
      boolean daylightCycle = opts.get("daylightCycle").optboolean(false);
      boolean weatherCycle = opts.get("weatherCycle").optboolean(false);
      boolean insomnia = opts.get("insomnia").optboolean(false);
      return new CreateWorldOptions(
          name,
          seed,
          gamemode,
          difficulty,
          structures,
          mobSpawning,
          allowCheats,
          daylightCycle,
          weatherCycle,
          insomnia);
    }

    private long parseSeed(LuaValue v) {
      if (v.isnil()) {
        return 0L;
      }
      if (v.isnumber()) {
        return v.tolong();
      }
      String s = v.tojstring();
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException e) {
        return s.hashCode();
      }
    }
  }

  /** minecheck.joinServer(address, [timeout]) -> boolean */
  private final class JoinServer extends VarArgFunction {

    /**
     * How often the address is probed for a listening server while there is nothing to connect to.
     * A closed port costs nothing to detect, and unlike a Minecraft-level attempt it leaves no
     * state behind to fight with.
     */
    private static final long PROBE_INTERVAL_MILLIS = 500L;

    /**
     * Minimum spacing between two Minecraft connect attempts. Generous on purpose: a login that is
     * still completing must never be replaced, and no login takes this long. On Forge there is no
     * observable "login in progress" state to test for — no connect screen, no connection object,
     * and the screen is a stale failure screen — so this interval is what keeps the retry from
     * aborting the attempt it was meant to help.
     */
    private static final long RETRY_INTERVAL_MILLIS = 20_000L;

    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String address = args.arg1().checkjstring();
      long deadline = deadline(args, 2);
      boolean initiated = false;
      boolean portOpen = false;
      long lastAttempt = 0L;
      long lastProbe = 0L;
      try {
        while (true) {
          long now = clock.getAsLong();
          if (client.isWorldReady()) {
            return LuaValue.TRUE;
          }
          // Only ever connect to a port that is actually listening. On Forge the dedicated server
          // does not accept anything until after it logs `Done` (~25 s in), so connecting earlier
          // is a guaranteed failure — and a failed Minecraft connect is exactly the state that used
          // to collide with the attempt that finally got in.
          if (now - lastProbe >= PROBE_INTERVAL_MILLIS) {
            lastProbe = now;
            portOpen = client.canReachServer(address);
          }
          if (portOpen
              && canStartAttempt()
              && (!initiated || now - lastAttempt >= RETRY_INTERVAL_MILLIS)) {
            client.joinServer(address);
            initiated = true;
            lastAttempt = now;
            portOpen = false; // re-probe before the next attempt: the server may have gone away
          }
          if (now > deadline) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("joinServer", e);
      }
    }
  }

  /**
   * True only when nothing of the previous attempt is visibly still live, so starting a new one
   * cannot abort a login that is already progressing: not in a world, no connect screen, no
   * logged-in-but-playerless login.
   *
   * <p>Necessary but not sufficient on Forge, which is why it is not the whole guard. During the
   * registry handshake the client has no player, no connect screen and no connection object, so all
   * three read "idle" while a login is a second away from succeeding — a retry fired into that
   * second replaced the login that was about to succeed, which is why the client used to join and
   * be dropped ~1 s later, over and over, while the server's script saw a player arrive and leave.
   * {@link JoinServer#RETRY_INTERVAL_MILLIS} is what keeps the two attempts apart; this only rules
   * out the states that <em>are</em> observable.
   */
  private boolean canStartAttempt() {
    return !client.isInWorld() && !client.isConnecting() && !client.isLoggingIn();
  }

  private final class LeaveWorld extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      long deadline = deadline(args, 1);
      boolean initiated = false;
      try {
        while (true) {
          if (!initiated) {
            client.leaveWorld();
            initiated = true;
          }
          if (!client.isInWorld()) {
            return LuaValue.TRUE;
          }
          if (clock.getAsLong() > deadline) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("leaveWorld", e);
      }
    }
  }

  /**
   * minecheck.disconnect([timeout]) -> boolean
   *
   * <p>Coordinator phase terminator: leaves the current world / disconnects from the server and
   * returns to the TITLE SCREEN (not a process quit). Waits until no world is entered; a later
   * world-entry action begins the next phase.
   */
  private final class Disconnect extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      long deadline = deadline(args, 1);
      boolean initiated = false;
      try {
        while (true) {
          if (!initiated) {
            client.leaveWorld();
            initiated = true;
          }
          if (!client.isInWorld()) {
            return LuaValue.TRUE;
          }
          if (clock.getAsLong() > deadline) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("disconnect", e);
      }
    }
  }

  /** minecheck.quit() -> nil (shuts down the client) */
  private final class Quit extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      client.quit();
      return LuaValue.NIL;
    }
  }

  // ------------------------------------------------------------ assertions / result reporting

  /**
   * minecheck.expect(cond, name) -> boolean
   *
   * <p>Records a named pass/fail into the run result and returns the boolean so the script can
   * branch on it (if/else style — expect never throws). If {@code name} is omitted it defaults to
   * "expect #&lt;count&gt;".
   */
  private final class Expect extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      boolean ok = args.arg1().toboolean();
      if (engine != null) {
        engine.recordExpect(checkName(args.arg(2)), ok);
      }
      return LuaValue.valueOf(ok);
    }

    /**
     * The check's label, or {@code null} for "let the engine number it".
     *
     * <p>This deliberately does not use {@code optjstring}: LuaJ's base implementation
     * <em>raises</em> for anything that is not nil/boolean/number/string, and {@code LuaTable} does
     * not override it. Since an int64 is a table, {@code expect(cond, minecheck.int64("5"))} used
     * to abort the whole script with <code>bad argument: String expected, got table</code> — the
     * opposite of this action's documented "never aborts the script". A label that is not text is
     * now reported and the check is numbered instead, so a run always keeps its recorded outcomes.
     */
    private String checkName(LuaValue arg) {
      if (arg.isnil()) {
        return null;
      }
      if (arg.isstring()) {
        // True for numbers as well, which read as their digits — the same as every other string
        // context in Lua.
        return arg.tojstring();
      }
      LOG.warning(
          String.format(
              "Minecheck: expect: a %s is not a check name — using the numbered default instead",
              arg.typename()));
      return null;
    }
  }

  /**
   * minecheck.fail(message) -> nil
   *
   * <p>Unconditionally fails the run with the given message and stops the script. Used as the
   * escape hatch after {@code expect} returns false, when the script logic decides it cannot
   * continue.
   */
  private final class Fail extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String message = args.optjstring(1, "minecheck.fail called");
      if (engine != null) {
        engine.failScript(message);
      }
      // Stop the script regardless: yield forever (never resume finished).
      while (true) {
        globals.yield(LuaValue.NIL);
      }
    }
  }

  // ------------------------------------------------------------ input (client)

  /**
   * Shared hold loop for input actions. Presses {@code press} (a boolean-returning platform
   * callback) down, then:
   *
   * <ul>
   *   <li>no {@code hold} and no {@code until} — releases on the next tick (a tap);
   *   <li>{@code hold} only — releases once the given number of seconds have elapsed, but never in
   *       the same client tick it was pressed in;
   *   <li>{@code until} — polls the Lua predicate each tick and releases when it returns truthy
   *       (bounded by {@code hold} if given, otherwise {@code config.defaultTimeout});
   *   <li>both — releases on whichever comes first.
   * </ul>
   *
   * Returns {@code { pressed = bool, held = bool, released = "timeout"|"cond"|nil }} — {@code held}
   * is true when the key was actually pressed down, {@code released} reports why the hold ended
   * (nil for a tap, "timeout" when the deadline elapsed, "cond" when the predicate fired).
   */
  private LuaTable holdLoop(
      java.util.function.BooleanSupplier press,
      java.util.function.Consumer<Boolean> setHeld,
      Varargs args) {
    LuaTable result = new LuaTable();
    if (!press.getAsBoolean()) {
      result.set("pressed", LuaValue.FALSE);
      result.set("held", LuaValue.FALSE);
      result.set("released", LuaValue.NIL);
      return result;
    }
    result.set(
        "pressed",
        LuaValue
            .TRUE); // Accept both `press(key, hold, until)` and the shorthand `press(key, until)`.
    LuaValue second = args.narg() >= 2 ? args.arg(2) : LuaValue.NIL;
    LuaValue third = args.narg() >= 3 ? args.arg(3) : LuaValue.NIL;
    double hold = second.isnumber() ? second.todouble() : -1.0;
    LuaFunction until = null;
    if (second.isfunction()) {
      until = second.checkfunction();
    } else if (third.isfunction()) {
      until = third.checkfunction();
    }

    if (hold < 0 && until == null) {
      // Tap: release on the next tick.
      globals.yield(LuaValue.NIL);
      setHeld.accept(true);
      return result;
    }

    double seconds = hold >= 0 ? hold : config.defaultTimeout();
    long deadline = clock.getAsLong() + (long) (seconds * 1000);
    boolean condFired = false;
    do {
      // A hold must span at least one client tick before the release can be dispatched, for the
      // same reason a tap yields first: a down and an up that land inside one client tick are never
      // sampled by the game's movement tick, so the key silently does nothing. Without this, a hold
      // whose deadline has already elapsed (a short hold, or the coroutine being descheduled by a
      // stall) would press and release in the same tick.
      globals.yield(LuaValue.NIL);
      if (until != null && until.call().toboolean()) {
        condFired = true;
        break;
      }
    } while (clock.getAsLong() <= deadline);
    setHeld.accept(condFired);
    result.set("held", LuaValue.valueOf(condFired));
    result.set("released", LuaValue.valueOf(condFired ? "cond" : "timeout"));
    return result;
  }

  /**
   * minecheck.press(key, [hold], [until]) -> table
   *
   * <p>Presses a key (binding id like "key.jump" or key name like "F3") down and releases it per
   * the hold/until rules above. See the Lua action reference for the return contract.
   */
  private final class Press extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String key = args.arg1().checkjstring();
      try {
        return holdLoop(() -> client.keyDown(key), held -> client.keyUp(key), args);
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("press", e);
      }
    }
  }

  /**
   * minecheck.pressCombination(keys, [hold], [until]) -> table
   *
   * <p>As {@code press} but for a table of keys, e.g. {@code { "key.sneak", "key.jump" }}. All keys
   * are pressed down together; if any key fails to resolve, nothing is pressed and {@code pressed =
   * false} is returned.
   */
  private final class PressCombination extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      LuaTable keys = args.arg1().checktable();
      int count = keys.length();
      String[] resolved = new String[count];
      for (int i = 1; i <= count; i++) {
        resolved[i - 1] = keys.get(i).checkjstring();
      }
      try {
        return holdLoop(
            () -> {
              // All-or-nothing, for real: the release below only runs when this lambda returns
              // true, so a key that fails to resolve after earlier keys were already pressed would
              // otherwise leave them held down forever. Roll back what this lambda pressed.
              java.util.List<String> down = new java.util.ArrayList<>(resolved.length);
              for (String key : resolved) {
                if (!client.keyDown(key)) {
                  for (String alreadyDown : down) {
                    client.keyUp(alreadyDown);
                  }
                  return false;
                }
                down.add(key);
              }
              return true;
            },
            held -> {
              for (String key : resolved) {
                client.keyUp(key);
              }
            },
            args);
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("pressCombination", e);
      }
    }
  }

  /**
   * minecheck.mouse(button, [hold], [until]) -> table
   *
   * <p>Mouse button down/up (0 = left, 1 = right, 2 = middle) with the same hold/until rules as
   * {@code press}.
   */
  private final class MouseAction extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      int button = args.arg1().checkint();
      try {
        return holdLoop(() -> client.mouseDown(button), held -> client.mouseUp(button), args);
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("mouse", e);
      }
    }
  }

  /** minecheck.keyPress(key) -> boolean (tap: down + release) */
  private final class KeyPress extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String key = args.arg1().checkjstring();
      try {
        if (!client.keyDown(key)) {
          return LuaValue.FALSE;
        }
        globals.yield(LuaValue.NIL);
        client.keyUp(key);
        return LuaValue.TRUE;
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("keyPress", e);
      }
    }
  }

  /** minecheck.mousePress(button) -> boolean (tap: down + release) */
  private final class MousePress extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      int button = args.arg1().checkint();
      try {
        if (!client.mouseDown(button)) {
          return LuaValue.FALSE;
        }
        globals.yield(LuaValue.NIL);
        client.mouseUp(button);
        return LuaValue.TRUE;
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("mousePress", e);
      }
    }
  }

  /** minecheck.getPlayer() -> table|nil: { name, pos = {x,y,z}, dimension } */
  private final class GetPlayer extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      net.minecheck.platform.api.PlayerInfo info;
      try {
        info = client.getPlayer();
      } catch (RuntimeException e) {
        throw wrap("getPlayer", e);
      }
      if (info == null) {
        return LuaValue.NIL;
      }
      LuaTable pos = new LuaTable();
      pos.set("x", LuaValue.valueOf(info.x()));
      pos.set("y", LuaValue.valueOf(info.y()));
      pos.set("z", LuaValue.valueOf(info.z()));
      LuaTable t = new LuaTable();
      t.set("name", LuaValue.valueOf(info.name()));
      t.set("pos", pos);
      t.set("dimension", LuaValue.valueOf(info.dimension()));
      return t;
    }
  }

  /** minecheck.look(pitch, yaw) -> boolean (90 = straight down) */
  private final class Look extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      float pitch = (float) args.arg1().checkdouble();
      float yaw = (float) args.arg(2).checkdouble();
      try {
        client.setLook(pitch, yaw);
      } catch (RuntimeException e) {
        throw wrap("look", e);
      }
      return LuaValue.TRUE;
    }
  }

  /** minecheck.lookAt(x, y, z) -> boolean */
  private final class LookAt extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      double x = args.arg1().checkdouble();
      double y = args.arg(2).checkdouble();
      double z = args.arg(3).checkdouble();
      try {
        client.lookAt(x, y, z);
      } catch (RuntimeException e) {
        throw wrap("lookAt", e);
      }
      return LuaValue.TRUE;
    }
  }

  /**
   * minecheck.getTargetBlock(entity) -> table|nil
   *
   * <p>Raycasts from the given entity's eyes along its look direction (reach 5) and returns the
   * block position hit as {@code { x =, y =, z = }}, or {@code nil} on a miss. Only the local
   * player is resolvable; pass the table returned by {@code minecheck.getPlayer()}.
   */
  private final class GetTargetBlock extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      LuaTable entity = args.arg1().checktable();
      String name = entity.get("name").optjstring(null);
      if (name == null) {
        throw new LuaError(
            "getTargetBlock: entity table needs a 'name' field (pass minecheck.getPlayer())");
      }
      net.minecheck.platform.api.BlockPosition pos;
      try {
        pos = client.getTargetBlock(name);
      } catch (RuntimeException e) {
        throw wrap("getTargetBlock", e);
      }
      if (pos == null) {
        return LuaValue.NIL;
      }
      LuaTable t = new LuaTable();
      t.set("x", LuaValue.valueOf(pos.x()));
      t.set("y", LuaValue.valueOf(pos.y()));
      t.set("z", LuaValue.valueOf(pos.z()));
      return t;
    }
  }

  /**
   * minecheck.getCrosshairBlock() -> table|nil
   *
   * <p>The block the renderer's crosshair is on — the target a click acts on, which the game
   * refreshes once per frame rather than on demand. Returns {@code { x =, y =, z = }}, or {@code
   * nil} when the crosshair is on nothing or the client is not in a world.
   */
  private final class GetCrosshairBlock extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      net.minecheck.platform.api.BlockPosition pos;
      try {
        pos = client.getCrosshairBlock();
      } catch (RuntimeException e) {
        throw wrap("getCrosshairBlock", e);
      }
      if (pos == null) {
        return LuaValue.NIL;
      }
      LuaTable t = new LuaTable();
      t.set("x", LuaValue.valueOf(pos.x()));
      t.set("y", LuaValue.valueOf(pos.y()));
      t.set("z", LuaValue.valueOf(pos.z()));
      return t;
    }
  }

  /**
   * minecheck.getBlock(pos) -> table|nil
   *
   * <p>Client: reads the block at integer position {@code pos} ({@code { x =, y =, z = }}) from the
   * client world: {@code { id = <registry id>, nbt = <table|nil> }} where {@code nbt} is the block
   * entity's NBT compound ({@code nil} for blocks without one). Returns {@code nil} when that
   * position is not loaded on the client (only chunks the server has sent are known) — poll until
   * it is non-{@code nil} after a server-side change. Raises an error when not in a world.
   */
  private final class GetBlock extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      double[] xyz = parsePos(args.arg1());
      net.minecheck.platform.api.BlockInfo info;
      try {
        info =
            client.getBlock(
                new net.minecheck.platform.api.BlockPosition(
                    (int) Math.floor(xyz[0]), (int) Math.floor(xyz[1]), (int) Math.floor(xyz[2])));
      } catch (RuntimeException e) {
        throw wrap("getBlock", e);
      }
      if (info == null) {
        return LuaValue.NIL;
      }
      LuaTable t = new LuaTable();
      t.set("id", LuaValue.valueOf(info.id()));
      if (info.nbt() != null) {
        t.set("nbt", nbtToLua(info.nbt()));
      }
      return t;
    }
  }

  // ------------------------------------------------------------ GUI / inventory (client)

  /**
   * minecheck.openScreen(id[, pos][, timeout]) -> boolean
   *
   * <p>Client: opens a container/handled screen and waits until it is open. {@code id} is the
   * screen id ({@code "inventory"} opens the player inventory via the inventory key; any other id
   * right-clicks the container block). For a block screen, {@code pos} ({@code { x =, y =, z = }})
   * is looked at before clicking; when omitted the current aim is used. Returns {@code true} once
   * {@code isScreenOpen(id)} is true, {@code false} on timeout.
   *
   * <p>The click waits for the aim it just made to land. {@code lookAt} is applied on the client
   * thread and the click raycasts at that moment, so clicking in the same breath as the rotation is
   * requested aims along the <em>previous</em> look direction: the ray hits air and vanilla's use
   * path drops the attempt silently, leaving the screen shut. Waiting for the crosshair to hold the
   * block {@code pos} names makes the click an act on the block the caller asked for.
   */
  private final class OpenScreen extends VarArgFunction {

    /**
     * How long the click waits for a requested aim to land before clicking anyway. A {@code pos}
     * that cannot be aimed at is a script error, and clicking straight away keeps that failure as
     * the caller's timeout rather than a longer wait for an aim that will never arrive.
     */
    private static final long AIM_GRACE_MILLIS = 5_000;

    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String id = args.arg1().checkjstring();
      LuaValue pos = args.arg(2);
      long deadline = deadline(args, 3);
      boolean initiated = false;
      boolean clicked = false;
      // The block `pos` names, and the point after which the click no longer waits for it.
      Integer aimX = null;
      Integer aimY = null;
      Integer aimZ = null;
      long aimDeadline = 0;
      try {
        while (true) {
          if (!initiated) {
            if (id.equals("inventory")) {
              client.keyDown("key.inventory");
              globals.yield(LuaValue.NIL);
              client.keyUp("key.inventory");
            } else if (!pos.isnil()) {
              double[] p = parsePos(pos);
              aimX = (int) Math.floor(p[0]);
              aimY = (int) Math.floor(p[1]);
              aimZ = (int) Math.floor(p[2]);
              aimDeadline = Math.min(deadline, clock.getAsLong() + AIM_GRACE_MILLIS);
              client.lookAt(p[0], p[1], p[2]);
            }
            initiated = true;
          }
          if (client.isScreenOpen(id)) {
            return LuaValue.TRUE;
          }
          if (!id.equals("inventory")
              && !clicked
              && aimReadyToClick(aimX, aimY, aimZ, aimDeadline)) {
            onClientThread(() -> client.useItemOnTarget());
            clicked = true;
          }
          if (clock.getAsLong() > deadline) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("openScreen", e);
      }
    }

    /**
     * True once clicking is the thing to do: no {@code pos} was given (the caller aimed already),
     * the aim was given up on, or the crosshair now holds the block {@code pos} names.
     */
    private boolean aimReadyToClick(Integer x, Integer y, Integer z, long aimDeadline) {
      if (x == null || clock.getAsLong() > aimDeadline) {
        return true;
      }
      net.minecheck.platform.api.PlayerInfo player = client.getPlayer();
      if (player == null) {
        return false;
      }
      net.minecheck.platform.api.BlockPosition hit = client.getTargetBlock(player.name());
      return hit != null && hit.x() == x && hit.y() == y && hit.z() == z;
    }
  }

  /**
   * minecheck.isScreenOpen([id]) -> boolean
   *
   * <p>Client: non-blocking check of whether a screen is open. No id (or {@code nil}) returns true
   * when any container screen is open; a known id ({@code "inventory"}, {@code "beacon"}, {@code
   * "chest"}, {@code "crafting"}, {@code "furnace"}) only matches that screen.
   */
  private final class IsScreenOpen extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String id = args.arg1().isnil() ? null : args.arg1().optjstring(null);
      try {
        return LuaValue.valueOf(client.isScreenOpen(id));
      } catch (RuntimeException e) {
        throw wrap("isScreenOpen", e);
      }
    }
  }

  /** minecheck.slotCount([id]) -> int */
  private final class SlotCount extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String id = args.arg1().isnil() ? null : args.arg1().optjstring(null);
      try {
        return LuaValue.valueOf(client.slotCount(id));
      } catch (RuntimeException e) {
        throw wrap("slotCount", e);
      }
    }
  }

  /**
   * minecheck.getSlot(index[, id]) -> table
   *
   * <p>Client: reads one slot of the open screen's container: {@code { empty = bool, id = string,
   * count = int, nbt = table|nil }}. Slots are indexed like the vanilla container (0 = first).
   * Raises an error when no matching screen is open.
   */
  private final class GetSlot extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      int index = args.arg1().checkint();
      String id = args.arg(2).isnil() ? null : args.arg(2).optjstring(null);
      net.minecheck.platform.api.SlotInfo info;
      try {
        info = client.getSlot(index, id);
      } catch (RuntimeException e) {
        throw wrap("getSlot", e);
      }
      LuaTable t = new LuaTable();
      t.set("empty", LuaValue.valueOf(info.empty()));
      if (info.id() != null) {
        t.set("id", LuaValue.valueOf(info.id()));
      }
      t.set("count", LuaValue.valueOf(info.count()));
      if (info.nbt() != null) {
        t.set("nbt", nbtToLua(info.nbt()));
      }
      return t;
    }
  }

  /**
   * minecheck.clickGui(x, y[, button]) -> boolean minecheck.clickGui(elementId[, button[, index]])
   * -> boolean
   *
   * <p>Client: clicks the currently open screen. With numeric arguments the click lands at scaled
   * GUI coordinates ({@code 0..scaledWidth} / {@code 0..scaledHeight}) and {@code mouseClicked} is
   * dispatched to the screen, so its own hit-testing decides. {@code button}: 0 = left (default), 1
   * = right, 2 = middle. Returns {@code true} when a screen was open and the click was routed to
   * it, {@code false} when no screen is open.
   *
   * <p>With a <b>string</b> first argument it names an on-screen <em>element</em> instead of a
   * point: the id is matched against the element's label (its displayed text — the portable form)
   * and then its class simple name (mapping-dependent), case-insensitively. {@code index} (1-based)
   * chooses among several matches. The click is dispatched to that element from its centre —
   * deterministic, because the caller named it. Returns {@code false} when no screen is open or
   * nothing matches.
   */
  private final class ClickGui extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      try {
        // The exact type, not isstring(): LuaJ reports true for numbers there (Lua's number→string
        // coercion), so a coordinate click would take the element path.
        if (args.arg1().type() == LuaValue.TSTRING) {
          String elementId = args.arg1().checkjstring();
          int button = args.arg(2).optint(0);
          int index = args.arg(3).optint(1);
          return LuaValue.valueOf(
              callOnClientThread(() -> client.clickGuiElement(elementId, button, index)));
        }
        double x = args.arg1().checkdouble();
        double y = args.arg(2).checkdouble();
        int button = args.arg(3).optint(0);
        return LuaValue.valueOf(callOnClientThread(() -> client.clickGui(button, x, y)));
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("clickGui", e);
      }
    }
  }

  /**
   * minecheck.moveItem(fromSlot, toSlot[, count][, screen]) -> boolean minecheck.moveItem(ghostRef,
   * toSlot[, count][, screen]) -> boolean
   *
   * <p>Client: moves {@code count} items (or the whole stack when {@code count} is omitted or at
   * least the stack size) from {@code fromSlot} to {@code toSlot} of the open screen's container
   * with vanilla container clicks: a left-click picks the stack up, then — for a partial move —
   * right-clicks drop one item at a time into the destination, and the leftover cursor stack is put
   * back into the source slot (the cursor is always left empty). {@code screen} optionally
   * restricts the open screen.
   *
   * <p>{@code fromSlot} may instead be a <b>ghost-slot reference</b>, {@code { recipe =
   * "<recipeId>", slot = <index>[, viewer = "<viewerId>"] }}, which names an ingredient the recipe
   * viewer shows for that recipe (the same {@code index} {@code getRecipeSlots} reports). The
   * ingredient is a <em>wanted item</em>, not a place to take from — the viewer's slots are
   * display-only, so the move still happens out of the open container: the first real slot holding
   * one of the ingredient's item ids (a tag-backed slot lists all its members) becomes the source.
   * A ghost reference as a <em>destination</em> raises a Lua error, because nothing can be put into
   * a display slot.
   *
   * <p>Every click is preceded by a confirmed server round-trip (the previous click's effect is
   * visible in the container), so no click is rejected on a stale container revision. Returns
   * {@code true} when the items landed in the destination, {@code false} when no matching screen is
   * open, {@code count} is {@code 0}, the source is empty, or the destination could not take them
   * all (leftovers stay in the source slot). A ghost reference additionally returns {@code false}
   * when there is no such recipe slot, no viewer is available, or the container holds none of the
   * ingredient. Verify the final contents with {@code getSlot}.
   */
  private final class MoveItem extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      if (args.arg(2).istable()) {
        throw new LuaError(
            "moveItem: a recipe ghost slot cannot be a destination — viewer slots are display-only. "
                + "Name the ingredient as the source instead "
                + "(moveItem({ recipe = \"…\", slot = n }, toSlot)), or fill the container with "
                + "fillFromRecipe(recipeId)");
      }
      int to = args.arg(2).checkint();
      LuaValue countArg = args.arg(3);
      Integer count = countArg.isnumber() ? countArg.toint() : null;
      String screen = args.arg(4).isnil() ? null : args.arg(4).optjstring(null);
      try {
        if (!client.isScreenOpen(screen)) {
          throw new LuaError("moveItem: no matching open screen");
        }
        if (count != null && count <= 0) {
          return LuaValue.FALSE;
        }
        final int from;
        if (args.arg1().istable()) {
          from = ghostSourceSlot(args.arg1().checktable(), screen);
          if (from < 0) {
            return LuaValue.FALSE;
          }
        } else {
          from = args.arg1().checkint();
        }
        net.minecheck.platform.api.SlotInfo source = client.getSlot(from, screen);
        if (source.empty()) {
          return LuaValue.FALSE;
        }
        final int total = source.count();
        final int want = count == null ? total : Math.min(count, total);
        if (want == total) {
          // Whole stack: one pickup, one place.
          if (!callOnClientThread(() -> client.clickSlot(from, 0, "pickup"))) {
            return LuaValue.FALSE;
          }
          if (!await(() -> client.getSlot(from, screen).empty(), 5000)) {
            return LuaValue.FALSE;
          }
          if (!callOnClientThread(() -> client.clickSlot(to, 0, "pickup"))) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
          return LuaValue.TRUE;
        }
        if (!callOnClientThread(() -> client.clickSlot(from, 0, "pickup"))) {
          return LuaValue.FALSE;
        }
        if (!await(() -> client.getSlot(from, screen).empty(), 5000)) {
          return LuaValue.FALSE;
        }
        net.minecheck.platform.api.SlotInfo dest = client.getSlot(to, screen);
        final int destStart = dest.empty() ? 0 : dest.count();
        int placed = 0;
        while (placed < want) {
          final int expected = destStart + placed + 1;
          if (!callOnClientThread(() -> client.clickSlot(to, 1, "pickup"))) {
            break;
          }
          if (!await(
              () -> {
                net.minecheck.platform.api.SlotInfo d = client.getSlot(to, screen);
                return !d.empty() && d.count() == expected;
              },
              2000)) {
            break;
          }
          placed++;
        }
        final int placedCount = placed;
        boolean restored = true;
        if (placedCount < total) {
          restored = callOnClientThread(() -> client.clickSlot(from, 0, "pickup"));
          if (restored) {
            restored =
                await(
                    () -> {
                      net.minecheck.platform.api.SlotInfo s = client.getSlot(from, screen);
                      return !s.empty() && s.count() == total - placedCount;
                    },
                    2000);
          }
        }
        return LuaValue.valueOf(restored && placedCount == want);
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("moveItem", e);
      }
    }
  }

  /**
   * Resolves a ghost-slot reference ({@code { recipe = "…", slot = n[, viewer = "…"] }}) to the
   * real container slot a move should take the ingredient from, or {@code -1} when it cannot be
   * resolved: no viewer available, no such recipe slot, an empty (unresolvable) slot, or none of
   * the ingredient's item ids is present in the open container. Every miss is logged with the
   * reason, so a {@code false} result is diagnosable from the run log.
   */
  private int ghostSourceSlot(LuaTable ref, String screen) {
    LuaValue recipeValue = ref.get("recipe");
    LuaValue slotValue = ref.get("slot");
    if (recipeValue.isnil() || slotValue.isnil()) {
      throw new LuaError(
          "moveItem: a ghost-slot reference needs { recipe = \"<recipeId>\", slot = <index> }");
    }
    String recipeId = recipeValue.checkjstring();
    int ghostIndex = slotValue.checkint();
    LuaValue viewerValue = ref.get("viewer");
    String viewerId = viewerValue.isnil() ? null : viewerValue.checkjstring();
    net.minecheck.platform.api.RecipeViewer v = viewer("moveItem", viewerId);
    if (v == null) {
      LOG.warning(
          String.format(
              "Minecheck: moveItem: no recipe viewer is available for ghost slot %d of %s",
              ghostIndex, recipeId));
      return -1;
    }
    java.util.List<String> wanted = null;
    java.util.List<net.minecheck.platform.api.GhostSlot> slots =
        callOnClientThread(() -> v.recipeSlots(recipeId));
    for (net.minecheck.platform.api.GhostSlot ghost : slots) {
      if (ghost.index() == ghostIndex) {
        wanted = ghost.itemIds();
        break;
      }
    }
    if (wanted == null || wanted.isEmpty()) {
      LOG.warning(
          String.format(
              "Minecheck: moveItem: the viewer has no ghost slot %d with items for recipe %s",
              ghostIndex, recipeId));
      return -1;
    }
    int slotCount = client.slotCount(screen);
    for (int i = 0; i < slotCount; i++) {
      net.minecheck.platform.api.SlotInfo slot = client.getSlot(i, screen);
      if (!slot.empty() && wanted.contains(slot.id())) {
        return i;
      }
    }
    LOG.warning(
        String.format(
            "Minecheck: moveItem: nothing in the container matches ghost slot %d of %s (wanted one of %s)",
            ghostIndex, recipeId, wanted));
    return -1;
  }

  /**
   * minecheck.isModLoaded(modId) -> boolean
   *
   * <p>True when the loader has that mod loaded (e.g. {@code "jei"}), on either side. The way to
   * branch around soft dependencies: a viewer that is installed but not ready is indistinguishably
   * absent from {@link RecipeViewerInfo}, while this reports "not installed" exactly.
   */
  private final class IsModLoaded extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String modId = args.arg1().checkjstring();
      try {
        return LuaValue.valueOf(services.isModLoaded(modId));
      } catch (RuntimeException e) {
        throw wrap("isModLoaded", e);
      }
    }
  }

  /**
   * minecheck.isInWorld() -> boolean
   *
   * <p>Client: true when the client is currently in a world (not on the title screen).
   * Non-blocking, which makes it the cheap predicate for a {@link WaitFor} condition or a branch
   * that must not pay a tick when it is already true.
   */
  private final class IsInWorld extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      try {
        return LuaValue.valueOf(client.isInWorld());
      } catch (RuntimeException e) {
        throw wrap("isInWorld", e);
      }
    }
  }

  /**
   * minecheck.isOnTitleScreen() -> boolean
   *
   * <p>Client: true when the client is at the title screen. The complement of {@link IsInWorld} in
   * the sense that a disconnect lands here, and the phase terminator the coordinator waits on.
   */
  private final class IsOnTitleScreen extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      try {
        return LuaValue.valueOf(client.isOnTitleScreen());
      } catch (RuntimeException e) {
        throw wrap("isOnTitleScreen", e);
      }
    }
  }

  /**
   * minecheck.recipeViewer([viewerId]) -> table|nil
   *
   * <p>Client: the recipe viewer Minecheck is bridged to, as {@code { id = "emi" }}, or {@code nil}
   * when no viewer is installed/ready. {@code viewerId} (e.g. {@code "jei"}) reports that specific
   * installed viewer instead of the priority one; {@code nil} when it is not installed. Viewers are
   * soft dependencies — a script should treat {@code nil} as "this feature is unavailable here"
   * rather than an error.
   */
  private final class RecipeViewerInfo extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      net.minecheck.platform.api.RecipeViewer viewer =
          viewer("recipeViewer", optionalViewerId(args.arg1()));
      if (viewer == null) {
        return LuaValue.NIL;
      }
      LuaTable t = new LuaTable();
      t.set("id", LuaValue.valueOf(viewer.id()));
      return t;
    }
  }

  /** The viewer id argument of an action, or {@code null} when the argument is absent. */
  private static String optionalViewerId(LuaValue value) {
    return value.isnil() ? null : value.checkjstring();
  }

  /**
   * The viewer an action should use: the one named by the script, or the priority viewer when the
   * script named none. Both resolve to {@code null} when that viewer is absent/unready.
   */
  private net.minecheck.platform.api.RecipeViewer viewer(String action, String viewerId) {
    try {
      return viewerId == null ? services.recipeViewer() : services.recipeViewer(viewerId);
    } catch (RuntimeException e) {
      throw wrap(action, e);
    }
  }

  /**
   * minecheck.getRecipeSlots(recipeId[, viewerId]) -> table
   *
   * <p>Client: the ghost ingredient slots the viewer shows for a recipe (by registry id, e.g.
   * {@code "minecraft:stick"}) as a list of {@code { index = int, items = { <item id>, ... } }}.
   * Empty when the recipe is unknown or no viewer is available. {@code viewerId} reads the named
   * viewer rather than the priority one.
   */
  private final class GetRecipeSlots extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String recipeId = args.arg1().checkjstring();
      LuaTable out = new LuaTable();
      net.minecheck.platform.api.RecipeViewer viewer =
          viewer("getRecipeSlots", optionalViewerId(args.arg(2)));
      if (viewer == null) {
        return out;
      }
      // On the client thread: a viewer's recipe lookup is client state (REI's display registry,
      // JEI's layout building), and every other client-reading action goes through here too.
      java.util.List<net.minecheck.platform.api.GhostSlot> slots;
      try {
        slots = callOnClientThread(() -> viewer.recipeSlots(recipeId));
      } catch (RuntimeException e) {
        throw wrap("getRecipeSlots", e);
      }
      int i = 1;
      for (net.minecheck.platform.api.GhostSlot slot : slots) {
        LuaTable t = new LuaTable();
        t.set("index", LuaValue.valueOf(slot.index()));
        LuaTable items = new LuaTable();
        int j = 1;
        for (String id : slot.itemIds()) {
          items.set(j++, LuaValue.valueOf(id));
        }
        t.set("items", items);
        out.set(i++, t);
      }
      return out;
    }
  }

  /**
   * minecheck.fillFromRecipe(recipeId[, maxItems[, viewerId]]) -> boolean
   *
   * <p>Client: asks the recipe viewer to fill the open container from the named recipe (its
   * recipe-transfer / fill action). {@code maxItems} caps how many items may be placed ({@code 0},
   * the default, uses the viewer's own choice; viewers without an item cap fall back to their
   * default transfer). {@code viewerId} drives the named viewer rather than the priority one.
   * Returns {@code false} when no viewer is available, the recipe is unknown, or the open container
   * cannot take that recipe.
   */
  private final class FillFromRecipe extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String recipeId = args.arg1().checkjstring();
      int maxItems = args.arg(2).optint(0);
      net.minecheck.platform.api.RecipeViewer viewer =
          viewer("fillFromRecipe", optionalViewerId(args.arg(3)));
      if (viewer == null) {
        return LuaValue.FALSE;
      }
      try {
        // The viewer's transfer actually moves items in the open container, so it must run on the
        // client thread like every other container mutation.
        return LuaValue.valueOf(
            callOnClientThread(() -> viewer.fillFromRecipe(recipeId, maxItems)));
      } catch (RuntimeException e) {
        throw wrap("fillFromRecipe", e);
      }
    }
  }

  /**
   * Yields the running script each tick until {@code condition} holds (true) or {@code timeoutMs}
   * elapses (false). The script engine drives the coroutine from tick events, so the condition is
   * re-evaluated once per tick.
   */
  private boolean await(java.util.function.BooleanSupplier condition, long timeoutMs) {
    long deadline = clock.getAsLong() + timeoutMs;
    while (true) {
      if (condition.getAsBoolean()) {
        return true;
      }
      if (clock.getAsLong() > deadline) {
        return false;
      }
      globals.yield(LuaValue.NIL);
    }
  }

  /**
   * Runs {@code action} on the client thread and suspends the script until it has run there,
   * returning its result.
   *
   * <p>Needed because the script body executes inside a Lua coroutine, which after its first yield
   * runs on LuaJ's own {@code Coroutine-N} Java thread — not the client thread. Every action that
   * mutates client state (screens, input, GLFW, the interaction manager) must go through here; a
   * direct call from an action is a real race (Fabric API's dev check logs {@code Attempted to set
   * screen … outside the render thread}). When the action runs inline (the script is still on the
   * client thread) this returns without yielding.
   */
  private <T> T callOnClientThread(java.util.function.Supplier<T> action) {
    java.util.concurrent.atomic.AtomicReference<T> result =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean done =
        new java.util.concurrent.atomic.AtomicBoolean();
    client.runOnClientThread(
        () -> {
          try {
            result.set(action.get());
          } catch (RuntimeException e) {
            failure.set(e);
          } finally {
            done.set(true);
          }
        });
    if (!await(done::get, CLIENT_THREAD_TIMEOUT_MS)) {
      throw new IllegalStateException(
          "client thread did not run the action within " + CLIENT_THREAD_TIMEOUT_MS + "ms");
    }
    if (failure.get() != null) {
      throw failure.get();
    }
    return result.get();
  }

  /** {@link #callOnClientThread} for actions with nothing to return. */
  private void onClientThread(Runnable action) {
    callOnClientThread(
        () -> {
          action.run();
          return null;
        });
  }

  /**
   * minecheck.reloadResources([timeout]) -> boolean
   *
   * <p>Client: requests a resource reload (the reload a player triggers with F3+T) and waits until
   * it has finished. Everything registered as a resource-reload listener rebuilds during the reload
   * — which is how a mod that indexed its data too early picks up what arrived later: JEI builds
   * its recipe index on world join, before the server's recipe sync lands, and only rebuilds on a
   * reload. Returns {@code true} when the reload finished, {@code false} on timeout.
   */
  private final class ReloadResources extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      long deadline = deadline(args, 1);
      try {
        client.reloadResources();
        while (!client.isResourceReloadSettled()) {
          if (clock.getAsLong() > deadline) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
        return LuaValue.TRUE;
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("reloadResources", e);
      }
    }
  }

  /**
   * minecheck.typeText(text[, target]) -> boolean
   *
   * <p>Client: types {@code text} character by character through the vanilla input path. {@code
   * target} selects where: {@code "chat"} opens the chat screen and types there; {@code "command"}
   * does the same but guarantees a leading {@code "/"} so the text is submitted as a command;
   * anything else (or no target) types into the open screen's focused text field (its first text
   * field when none is focused). The text is never submitted — send it with {@code press("enter")}.
   * Returns {@code false} when there is no text input to type into.
   */
  private final class TypeText extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String text = args.arg1().checkjstring();
      String target = args.arg(2).isnil() ? null : args.arg(2).optjstring(null);
      String kind = target == null ? "" : target.toLowerCase(java.util.Locale.ROOT);
      boolean chat = kind.equals("chat") || kind.equals("command");
      try {
        // Opening the chat is a client-thread job, so it is asynchronous: ask for it, wait until it
        // is actually up, then type into it (also on the client thread).
        if (chat && !client.isScreenOpen("chat")) {
          client.openChat(kind);
          if (!await(() -> client.isScreenOpen("chat"), CHAT_OPEN_TIMEOUT_MS)) {
            return LuaValue.FALSE;
          }
        }
        return LuaValue.valueOf(callOnClientThread(() -> client.typeText(text, target)));
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("typeText", e);
      }
    }
  }

  // ------------------------------------------------------------ screenshots (client)

  /** Capture names already used in this run: a second call with the same name is an error. */
  private final java.util.Set<String> capturedScreenshots = new java.util.HashSet<>();

  /**
   * minecheck.captureScreenshot(name[, options][, timeout]) -> table
   *
   * <p>Client: captures the frame the client is showing to a PNG under the run directory's {@code
   * screenshots/} folder and returns {@code { path, width, height, bytes, sha256 }} for the file
   * written.
   *
   * <p>{@code options} holds {@code region} today: {@code { x, y, w, h, unit? }} crops the capture.
   * {@code unit} is {@code "framebuffer"} (default) or {@code "gui"}; a region is clamped to the
   * frame and a region with no overlap raises. Unknown option keys raise, because a typo must not
   * silently capture the whole frame.
   *
   * <p>The capture happens on the client's next rendered frame — the action yields once per tick
   * while it waits, so the game keeps ticking — and the PNG is encoded and written off the render
   * thread. It does <b>not</b> wait for the scene to settle: a script that wants a stable picture
   * must already be there ({@code waitFor}, {@code worldGenSettled}, {@code isScreenOpen}, …).
   */
  private final class CaptureScreenshot extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String name = captureName(args.arg1());
      boolean hasRegion = false;
      int rx = 0;
      int ry = 0;
      int rw = 0;
      int rh = 0;
      String unit = net.minecheck.platform.api.ScreenshotRequest.UNIT_FRAMEBUFFER;

      LuaValue optionsArg = args.arg(2);
      if (!optionsArg.isnil()) {
        LuaTable options = optionsArg.checktable();
        for (LuaValue key : options.keys()) {
          String option = key.tojstring();
          if (!option.equals("region")) {
            throw new LuaError("captureScreenshot: unknown option \"" + option + "\"");
          }
        }
        LuaValue regionArg = options.get("region");
        if (!regionArg.isnil()) {
          LuaTable region = regionArg.checktable();
          for (LuaValue key : region.keys()) {
            String field = key.tojstring();
            if (!field.equals("x")
                && !field.equals("y")
                && !field.equals("w")
                && !field.equals("h")
                && !field.equals("unit")) {
              throw new LuaError("captureScreenshot: unknown region field \"" + field + "\"");
            }
          }
          rx = regionInt(region, "x");
          ry = regionInt(region, "y");
          rw = regionInt(region, "w");
          rh = regionInt(region, "h");
          if (rw <= 0 || rh <= 0) {
            throw new LuaError(
                "captureScreenshot: region needs a positive w and h, got " + rw + "x" + rh);
          }
          LuaValue unitArg = region.get("unit");
          if (!unitArg.isnil()) {
            unit = unitArg.optjstring("").trim().toLowerCase(java.util.Locale.ROOT);
            if (!unit.equals(net.minecheck.platform.api.ScreenshotRequest.UNIT_FRAMEBUFFER)
                && !unit.equals(net.minecheck.platform.api.ScreenshotRequest.UNIT_GUI)) {
              throw new LuaError(
                  "captureScreenshot: region unit must be \"framebuffer\" or \"gui\", got \""
                      + unit
                      + "\"");
            }
          }
          hasRegion = true;
        }
      }

      if (!capturedScreenshots.add(name)) {
        throw new LuaError("captureScreenshot: \"" + name + "\" was already captured in this run");
      }

      double seconds = args.arg(3).isnumber() ? args.arg(3).todouble() : config.defaultTimeout();
      long deadline = clock.getAsLong() + (long) (seconds * 1000);
      net.minecheck.platform.api.ScreenshotRequest request =
          new net.minecheck.platform.api.ScreenshotRequest(
              name,
              hasRegion,
              rx,
              ry,
              rw,
              rh,
              unit,
              config.screenshotScale(),
              config.screenshotChatVisible(),
              config.screenshotWidth(),
              config.screenshotHeight());
      try {
        while (true) {
          // Re-asked every tick while queued: a platform that has to resize the window first leaves
          // the request queued and captures on the next tick, when the new size has been applied.
          if (request.state() == net.minecheck.platform.api.ScreenshotRequest.State.QUEUED) {
            client.requestScreenshot(request);
          }
          if (request.state() == net.minecheck.platform.api.ScreenshotRequest.State.DONE) {
            LuaTable t = new LuaTable();
            t.set("path", LuaValue.valueOf(request.path().toString()));
            t.set("width", LuaValue.valueOf(request.outWidth()));
            t.set("height", LuaValue.valueOf(request.outHeight()));
            t.set("bytes", LuaValue.valueOf(request.bytes()));
            t.set("sha256", LuaValue.valueOf(request.sha256()));
            return t;
          }
          if (request.state() == net.minecheck.platform.api.ScreenshotRequest.State.FAILED) {
            throw new LuaError("captureScreenshot: " + request.failure());
          }
          if (clock.getAsLong() > deadline) {
            throw new LuaError(
                "captureScreenshot: timed out after "
                    + (seconds == Math.rint(seconds) ? Long.toString((long) seconds) : seconds)
                    + "s waiting for the frame");
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("captureScreenshot", e);
      }
    }

    /**
     * The capture name, with a trailing {@code .png} stripped: the name doubles as the file name,
     * so {@code captureScreenshot("title.png")} writes {@code title.png} rather than {@code
     * title.png.png}. Separators and {@code ..} are refused so a name can never escape the run's
     * screenshots directory, and a leading {@code _} is allowed (it marks an ad-hoc capture the
     * baseline comparison skips).
     */
    private String captureName(LuaValue arg) {
      if (arg.type() != LuaValue.TSTRING) {
        throw new LuaError(
            "captureScreenshot: the capture name must be a string, got " + arg.typename());
      }
      String name = arg.tojstring();
      if (name.endsWith(".png")) {
        name = name.substring(0, name.length() - 4);
      }
      if (name.isBlank()) {
        throw new LuaError("captureScreenshot: the capture name must not be empty");
      }
      if (name.contains("/") || name.contains("\\") || name.contains("..")) {
        throw new LuaError("captureScreenshot: invalid capture name \"" + name + "\"");
      }
      for (int i = 0; i < name.length(); i++) {
        if (Character.isISOControl(name.charAt(i))) {
          throw new LuaError("captureScreenshot: invalid capture name \"" + name + "\"");
        }
      }
      return name;
    }

    /** One whole-number region field, or a Lua error naming it. */
    private int regionInt(LuaTable region, String field) {
      LuaValue value = region.get(field);
      if (value.isnil()) {
        throw new LuaError(
            "captureScreenshot: region needs numeric x, y, w, h (missing \"" + field + "\")");
      }
      if (!value.isnumber()) {
        throw new LuaError(
            "captureScreenshot: region needs numeric x, y, w, h (\""
                + field
                + "\" is a "
                + value.typename()
                + ")");
      }
      double number = value.todouble();
      if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
        throw new LuaError(
            "captureScreenshot: region field \""
                + field
                + "\" must be a whole number, got "
                + value.tojstring());
      }
      return (int) number;
    }
  }

  // ------------------------------------------------------------ server actions

  /**
   * minecheck.runCommand(cmd) -> table
   *
   * <p>Executes {@code cmd} (no leading '/') and returns {@code { success = true, result = <result
   * count>, error = nil }}. On the dedicated server it runs as a console command; on the client it
   * is sent as the local player's command (cheats/op required for game-changing commands, and the
   * result count is unavailable there — verify effects instead).
   */
  private final class RunCommand extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String cmd = args.arg1().checkjstring();
      LuaTable t = new LuaTable();
      try {
        if (server != null) {
          t.set("result", LuaValue.valueOf(server.runCommand(cmd)));
        } else {
          onClientThread(() -> client.runCommand(cmd));
          t.set("result", LuaValue.valueOf(0));
        }
        t.set("success", LuaValue.TRUE);
        t.set("error", LuaValue.NIL);
        return t;
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("runCommand", e);
      }
    }
  }

  /**
   * minecheck.waitForPlayer(name, [timeout]) -> boolean
   *
   * <p>Server: pauses until a player with the given name is online. Returns true when they join,
   * false on timeout.
   */
  private final class WaitForPlayer extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireServer();
      String name = args.arg1().checkjstring();
      long deadline = deadline(args, 2);
      try {
        while (true) {
          if (server.isPlayerOnline(name)) {
            return LuaValue.TRUE;
          }
          if (clock.getAsLong() > deadline) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("waitForPlayer", e);
      }
    }
  }

  /**
   * minecheck.waitForPlayerLeave(name, [timeout]) -> boolean
   *
   * <p>Server: pauses until a player with the given name disconnects. Returns true immediately if
   * they are not online at call time ("already left"); otherwise waits and returns true on leave,
   * false on timeout.
   */
  private final class WaitForPlayerLeave extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireServer();
      String name = args.arg1().checkjstring();
      long deadline = deadline(args, 2);
      try {
        while (true) {
          if (!server.isPlayerOnline(name)) {
            return LuaValue.TRUE;
          }
          if (clock.getAsLong() > deadline) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("waitForPlayerLeave", e);
      }
    }
  }

  /**
   * minecheck.spawnEntity(id, pos, [nbt]) -> table
   *
   * <p>Server: spawns an entity by registry id (e.g. {@code minecraft:cow}) at {@code pos = { x = ,
   * y =, z = }} with optional NBT (Lua table). Returns {@code { success = true, id = <network id>
   * }} or {@code { success = false, error = <message> }}.
   */
  private final class SpawnEntity extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireServer();
      String id = args.arg1().checkjstring();
      double[] pos = parsePos(args.arg(2));
      String nbt = toSnbt(args.arg(3));
      try {
        int entityId = server.spawnEntity(id, pos[0], pos[1], pos[2], nbt);
        LuaTable t = new LuaTable();
        t.set("success", LuaValue.TRUE);
        t.set("id", LuaValue.valueOf(entityId));
        t.set("error", LuaValue.NIL);
        return t;
      } catch (RuntimeException e) {
        return spawnError("spawnEntity", e);
      }
    }
  }

  /**
   * minecheck.spawnBlock(id, pos, [nbt], [replace]) -> table
   *
   * <p>Server: sets a block by registry id (e.g. {@code minecraft:stone}) at {@code pos = { x =, y
   * =, z = }} with optional block-entity NBT. {@code replace} (default false) allows overwriting a
   * non-air block. Returns {@code { success = true }} or {@code { success = false, error =
   * <message> }}.
   */
  private final class SpawnBlock extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireServer();
      String id = args.arg1().checkjstring();
      double[] pos = parsePos(args.arg(2));
      String nbt = toSnbt(args.arg(3));
      boolean replace = args.arg(4).optboolean(false);
      try {
        boolean ok = server.spawnBlock(id, (int) pos[0], (int) pos[1], (int) pos[2], nbt, replace);
        LuaTable t = new LuaTable();
        t.set("success", LuaValue.valueOf(ok));
        t.set("error", ok ? LuaValue.NIL : LuaValue.valueOf("position not replaced (occupied)"));
        return t;
      } catch (RuntimeException e) {
        return spawnError("spawnBlock", e);
      }
    }
  }

  /**
   * minecheck.spawnItemDrop(id, pos, [count], [nbt]) -> table
   *
   * <p>Server: spawns an item entity (drop) by item registry id (e.g. {@code minecraft:diamond}) at
   * {@code pos = { x =, y =, z = }} with a count (default 1) and optional stack NBT. Returns {@code
   * { success = true, id = <network id> }} or {@code { success = false, error = <message> }}.
   */
  private final class SpawnItemDrop extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireServer();
      String id = args.arg1().checkjstring();
      double[] pos = parsePos(args.arg(2));
      int count = args.arg(3).optint(1);
      String nbt = args.arg(4).isnil() ? "" : toSnbt(args.arg(4));
      try {
        int entityId = server.spawnItemDrop(id, count, pos[0], pos[1], pos[2], nbt);
        LuaTable t = new LuaTable();
        t.set("success", LuaValue.TRUE);
        t.set("id", LuaValue.valueOf(entityId));
        t.set("error", LuaValue.NIL);
        return t;
      } catch (RuntimeException e) {
        return spawnError("spawnItemDrop", e);
      }
    }
  }

  /**
   * minecheck.spawnStructure(id, pos) -> table
   *
   * <p>Server: places a structure template by id (e.g. {@code minecraft:igloo/top}) at {@code pos =
   * { x =, y =, z = }}. Returns {@code { success = true }} or {@code { success = false, error =
   * <message> }}.
   */
  private final class SpawnStructure extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireServer();
      String id = args.arg1().checkjstring();
      double[] pos = parsePos(args.arg(2));
      try {
        boolean ok = server.spawnStructure(id, (int) pos[0], (int) pos[1], (int) pos[2]);
        LuaTable t = new LuaTable();
        t.set("success", LuaValue.valueOf(ok));
        t.set("error", LuaValue.NIL);
        return t;
      } catch (RuntimeException e) {
        return spawnError("spawnStructure", e);
      }
    }
  }

  /**
   * minecheck.findEntities([type][, tag]) -> table
   *
   * <p>Server: lists live entities in the overworld matching an optional entity type (registry id,
   * e.g. {@code "minecraft:cow"}) and an optional scoreboard tag. With no args it returns all
   * entities. Each entry: {@code { id = <network id>, type = <registry id>, pos = { x =, y =, z =
   * }, tags = { <tag>, ... } }}. Returns an empty table when nothing matches.
   */
  private final class FindEntities extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireServer();
      String type = args.arg1().isnil() ? null : args.arg1().checkjstring();
      String tag = args.arg(2).isnil() ? null : args.arg(2).checkjstring();
      java.util.List<net.minecheck.platform.api.EntityInfo> found;
      try {
        found = server.findEntities(type, tag);
      } catch (RuntimeException e) {
        throw wrap("findEntities", e);
      }
      LuaTable list = new LuaTable();
      int i = 1;
      for (net.minecheck.platform.api.EntityInfo info : found) {
        list.set(i++, entityTable(info));
      }
      return list;
    }
  }

  /**
   * minecheck.findNearbyEntities([type]) -> table
   *
   * <p>Client: the entities the client currently knows about (the server has synced them into our
   * loaded chunks) — i.e. what the local player can see and reach, which is the precondition for
   * aiming at and interacting with one. Pass a type to filter (e.g. {@code "minecraft:cow"}).
   *
   * <p>No tag filter: vanilla never syncs entity scoreboard tags to clients, so a client-side
   * entry's {@code tags} is always empty (use the server-side {@link FindEntities} when a tag is
   * the handle). Returns an empty table when the client is not in a world.
   */
  private final class FindNearbyEntities extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String type = args.arg1().isnil() ? null : args.arg1().checkjstring();
      java.util.List<net.minecheck.platform.api.EntityInfo> found;
      try {
        found = client.findNearbyEntities(type);
      } catch (RuntimeException e) {
        throw wrap("findNearbyEntities", e);
      }
      LuaTable list = new LuaTable();
      int i = 1;
      for (net.minecheck.platform.api.EntityInfo info : found) {
        list.set(i++, entityTable(info));
      }
      return list;
    }
  }

  /** Converts an {@link net.minecheck.platform.api.EntityInfo} to a Lua table. */
  private static LuaTable entityTable(net.minecheck.platform.api.EntityInfo info) {
    LuaTable pos = new LuaTable();
    pos.set("x", LuaValue.valueOf(info.x()));
    pos.set("y", LuaValue.valueOf(info.y()));
    pos.set("z", LuaValue.valueOf(info.z()));
    LuaTable tags = new LuaTable();
    int i = 1;
    for (String t : info.tags()) {
      tags.set(i++, LuaValue.valueOf(t));
    }
    LuaTable e = new LuaTable();
    e.set("id", LuaValue.valueOf(info.id()));
    e.set("type", LuaValue.valueOf(info.type()));
    e.set("pos", pos);
    e.set("tags", tags);
    return e;
  }

  /** Builds {@code { success = false, error = msg }} from a spawn failure. */
  private LuaTable spawnError(String action, RuntimeException e) {
    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    LuaTable t = new LuaTable();
    t.set("success", LuaValue.FALSE);
    t.set("id", LuaValue.NIL);
    t.set("error", LuaValue.valueOf(action + ": " + msg));
    return t;
  }

  // ------------------------------------------------------------ packets

  /**
   * minecheck.sendPacket(name, ...) -> boolean
   *
   * <p>Sends a packet on the Minecheck channel: client -> server, or server -> every connected
   * player. Arguments may be nil/boolean/number/string, or a string wrapped with {@code
   * minecheck.bytes}. Returns false when the channel is unavailable or the connection is not up.
   */
  private final class SendPacket extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String name = args.arg1().checkjstring();
      return LuaValue.valueOf(packets.send(name, packetArgs("sendPacket", args, 2)));
    }
  }

  /**
   * minecheck.sendPacketTo(playerName, name, ...) -> boolean
   *
   * <p>Server: sends a packet to one online player. Returns false when that player is not online
   * (or when called on a client, which has no player list to address).
   */
  private final class SendPacketTo extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String player = args.arg1().checkjstring();
      String name = args.arg(2).checkjstring();
      return LuaValue.valueOf(packets.sendTo(player, name, packetArgs("sendPacketTo", args, 3)));
    }
  }

  /**
   * minecheck.waitForPacket(name[, predicate][, timeout]) -> table | false
   *
   * <p>Waits until a packet with the given name has been received (checking packets that arrived
   * before the call too), optionally gated by a predicate called with the packet's arguments.
   * Returns the packet as {@code { name = <name>, args = { ... } }} (truthy), or false on timeout.
   */
  private final class WaitForPacket extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String name = args.arg1().checkjstring();
      LuaFunction predicate = args.arg(2).isfunction() ? args.arg(2).checkfunction() : null;
      long deadline = deadline(args, predicate != null ? 3 : 2);
      while (true) {
        for (PacketChannel.Packet packet : packets.received(name)) {
          if (predicate == null || matches(name, predicate, packet)) {
            return packet.toLua();
          }
        }
        // Checked after the history above, so a packet a plugin already delivered still counts.
        // A loader that registered no transport can never deliver one, so there is nothing to wait
        // for: answer false now instead of sleeping out a timeout that cannot end differently.
        // An *unavailable* channel is deliberately not short-circuited — it can come up later.
        if (!packets.hasTransport()) {
          return LuaValue.FALSE;
        }
        if (clock.getAsLong() > deadline) {
          return LuaValue.FALSE;
        }
        globals.yield(LuaValue.NIL);
      }
    }

    private boolean matches(String name, LuaFunction predicate, PacketChannel.Packet packet) {
      try {
        return predicate.invoke(packet.varargs()).arg1().toboolean();
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap("waitForPacket(" + name + ") predicate", e);
      }
    }
  }

  /**
   * minecheck.onPacket(name, handler) -> nil
   *
   * <p>Registers a handler invoked with the packet's arguments whenever a packet with that name is
   * received. Handlers run on the game thread between script steps and must not yield (no `sleep`
   * or `waitFor` inside a handler); an error in one fails the run.
   */
  private final class OnPacket extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String name = args.arg1().checkjstring();
      packets.onPacket(name, args.arg(2).checkfunction());
      return LuaValue.NIL;
    }
  }

  /**
   * minecheck.watchPacketChannel(channel) -> boolean
   *
   * <p>Opts in to observing another mod's channel: every payload received on it is handed to the
   * channel's decoder (see {@code registerPacketDecoder}) and then behaves like a packet named
   * after the channel. Returns false when this loader cannot observe that channel.
   */
  private final class WatchPacketChannel extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String channel = args.arg1().checkjstring();
      try {
        return LuaValue.valueOf(packets.observe(channel));
      } catch (RuntimeException e) {
        throw wrap("watchPacketChannel", e);
      }
    }
  }

  /**
   * minecheck.registerPacketDecoder(channel, decoder) -> nil
   *
   * <p>Gives the script the raw payload bytes of an observed channel (a Lua string) and takes back
   * whatever shape it wants: the decoder's return value becomes the packet's single argument.
   */
  private final class RegisterPacketDecoder extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String channel = args.arg1().checkjstring();
      packets.registerDecoder(channel, args.arg(2).checkfunction());
      return LuaValue.NIL;
    }
  }

  /**
   * minecheck.bytes(value) -> table
   *
   * <p>Marks a Lua string as raw bytes for {@code sendPacket} (strings are sent as text otherwise).
   * The marker is otherwise opaque: incoming bytes arrive as plain Lua strings.
   */
  private final class Bytes extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      return PacketValues.bytesMarker(args.arg1());
    }
  }

  /**
   * minecheck.int64(value) -> int64
   *
   * <p>An exact 64-bit integer, for values a Lua number cannot hold ({@code reader.readLong} hands
   * back the same type). A string is parsed exactly; a number is converted, which is exact up to
   * 2^53 — pass the string when all 64 bits matter.
   */
  private final class Int64 extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      LuaValue v = args.arg1();
      if (v instanceof LuaInt64 i) {
        return i;
      }
      if (v.isstring()) {
        return LuaInt64.parse(v.tojstring());
      }
      if (v.isnumber()) {
        double d = v.todouble();
        if (d != Math.rint(d) || Double.isInfinite(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
          throw new LuaError(
              "int64: not a 64-bit integer: " + d + " (pass a string for all 64 bits)");
        }
        return LuaInt64.of((long) d);
      }
      throw new LuaError("int64: expected a number or a decimal string, got " + v.typename());
    }
  }

  /**
   * minecheck.reader(bytes) -> table
   *
   * <p>A cursor over a byte string, for decoding another mod's payload: {@code readByte}, {@code
   * readVarInt} (MC VarInt), {@code readZigZag}, {@code readInt} / {@code readLong} (fixed
   * big-endian), {@code readFloat}, {@code readDouble}, {@code readBool}, {@code readString},
   * {@code readBytes(n)}, {@code readBytesVarInt}, {@code bytes()}, {@code remaining()} and {@code
   * offset()}.
   */
  private final class Reader extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      return readerTable(new WireReader(PacketValues.bytesOf("reader", args.arg1())));
    }
  }

  /** Marshals Lua arguments {@code fromIndex..narg} into wire values. */
  private static java.util.List<PacketValue> packetArgs(
      String action, Varargs args, int fromIndex) {
    java.util.List<PacketValue> values = new java.util.ArrayList<>();
    for (int i = fromIndex; i <= args.narg(); i++) {
      values.add(PacketValues.toWire(action, args.arg(i)));
    }
    return values;
  }

  /** One cursor method; {@code op} reads from the shared {@link WireReader}. */
  @FunctionalInterface
  private interface ReadOp {
    LuaValue read(WireReader reader, Varargs args);
  }

  private static final class ReaderMethod extends VarArgFunction {
    private final WireReader reader;
    private final String name;
    private final ReadOp op;

    ReaderMethod(WireReader reader, String name, ReadOp op) {
      this.reader = reader;
      this.name = name;
      this.op = op;
    }

    @Override
    public Varargs invoke(Varargs args) {
      try {
        return op.read(reader, args);
      } catch (WireFormatException e) {
        throw new LuaError("reader." + name + ": " + e.getMessage());
      }
    }
  }

  private static LuaTable readerTable(WireReader reader) {
    LuaTable t = new LuaTable();
    t.set(
        "readByte", new ReaderMethod(reader, "readByte", (r, a) -> LuaValue.valueOf(r.readByte())));
    t.set(
        "readVarInt",
        new ReaderMethod(reader, "readVarInt", (r, a) -> LuaValue.valueOf(r.readVarInt())));
    t.set(
        "readZigZag",
        new ReaderMethod(reader, "readZigZag", (r, a) -> LuaValue.valueOf(r.readZigZag())));
    t.set("readInt", new ReaderMethod(reader, "readInt", (r, a) -> LuaValue.valueOf(r.readInt())));
    // An i64 is exact in Java but not in a Lua number, so it arrives as an int64 value rather than
    // a double that has already lost the low bits; see LuaInt64.
    t.set("readLong", new ReaderMethod(reader, "readLong", (r, a) -> LuaInt64.of(r.readLong())));
    t.set(
        "readFloat",
        new ReaderMethod(reader, "readFloat", (r, a) -> LuaValue.valueOf(r.readFloat())));
    t.set(
        "readDouble",
        new ReaderMethod(reader, "readDouble", (r, a) -> LuaValue.valueOf(r.readDouble())));
    t.set(
        "readBool", new ReaderMethod(reader, "readBool", (r, a) -> LuaValue.valueOf(r.readBool())));
    t.set(
        "readString",
        new ReaderMethod(reader, "readString", (r, a) -> LuaValue.valueOf(r.readString())));
    t.set(
        "readBytes",
        new ReaderMethod(
            reader, "readBytes", (r, a) -> LuaString.valueOf(r.readBytes(a.arg1().checkint()))));
    t.set(
        "readBytesVarInt",
        new ReaderMethod(reader, "readBytesVarInt", (r, a) -> LuaString.valueOf(r.readBytes())));
    t.set(
        "bytes", new ReaderMethod(reader, "bytes", (r, a) -> LuaString.valueOf(r.readRemaining())));
    t.set(
        "remaining",
        new ReaderMethod(reader, "remaining", (r, a) -> LuaValue.valueOf(r.remaining())));
    t.set("offset", new ReaderMethod(reader, "offset", (r, a) -> LuaValue.valueOf(r.offset())));
    return t;
  }

  // ------------------------------------------------------------ utilities

  /** minecheck.log(msg, [level]) -> nil */
  private final class LogAction extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String msg = args.arg1().checkjstring();
      String level = args.arg(2).optjstring("info");
      switch (level.toLowerCase()) {
        case "warn":
          LOG.warning(String.format("[script] %s", msg));
          break;
        case "error":
          LOG.severe(String.format("[script] %s", msg));
          break;
        default:
          LOG.info(String.format("[script] %s", msg));
          break;
      }
      return LuaValue.NIL;
    }
  }

  /** minecheck.sleep(seconds) -> nil */
  private final class Sleep extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      double seconds = args.arg1().checkdouble();
      long deadline = clock.getAsLong() + (long) (seconds * 1000);
      while (clock.getAsLong() < deadline) {
        globals.yield(LuaValue.NIL);
      }
      return LuaValue.NIL;
    }
  }

  /**
   * minecheck.wait([seconds]) -> nil
   *
   * <p>Alias of {@code sleep} with an optional duration: an omitted (or non-positive) duration
   * waits one engine tick, which is enough to "let the world move" a frame without a fixed guess.
   */
  private final class Wait extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      double seconds = args.arg1().optdouble(0);
      if (seconds <= 0) {
        globals.yield(LuaValue.NIL); // one tick: "let the world move", with no fixed guess
        return LuaValue.NIL;
      }
      long deadline = clock.getAsLong() + (long) (seconds * 1000);
      while (clock.getAsLong() < deadline) {
        globals.yield(LuaValue.NIL);
      }
      return LuaValue.NIL;
    }
  }

  /**
   * minecheck.waitFor(condition, [timeout]) -> boolean
   *
   * <p>Also exposed as {@code sleepUntil} ("sleep until this is true"), same semantics.
   */
  private final class WaitFor extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      LuaFunction condition = args.arg1().checkfunction();
      long deadline = deadline(args, 2);
      while (true) {
        if (condition.call().toboolean()) {
          return LuaValue.TRUE;
        }
        if (clock.getAsLong() > deadline) {
          return LuaValue.FALSE;
        }
        globals.yield(LuaValue.NIL);
      }
    }
  }

  /** minecheck.side() -> "client" | "server" */
  private final class Side extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      return LuaValue.valueOf(services.dedicatedServer() ? "server" : "client");
    }
  }

  /**
   * minecheck.worldGenSettled() -> boolean
   *
   * <p>True once world chunk generation has settled (no pending chunk work). Designed to be driven
   * by {@code minecheck.waitFor}: pass it as the condition function and it is re-polled every tick.
   *
   * <p>On the client, in a local/integrated world this is true once the integrated server's
   * spawn-region generation is done; outside a world the platform returns true immediately (and
   * logs a console warning). On a dedicated server it reflects that server's own overworld
   * generation. Typically used to settle worldgen before {@code leaveWorld} so the save captures
   * fully-generated spawn chunks instead of re-generating them on rejoin.
   */
  private final class WorldGenSettled extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      // PlatformServices guarantees exactly one of client/server is set:
      // dedicated server -> server platform, client -> client platform.
      if (services.dedicatedServer()) {
        return LuaValue.valueOf(server.isWorldGenSettled());
      }
      return LuaValue.valueOf(client.isWorldGenSettled());
    }
  }

  /** minecheck.minecraftVersion() -> string */
  private final class MinecraftVersion extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      return LuaValue.valueOf(services.minecraftVersion());
    }
  }

  /** minecheck.config() -> table (read-only view) */
  private final class ConfigView extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      LuaTable t = new LuaTable();
      t.set("script", config.script());
      if (config.coordinator() != null) {
        t.set("coordinator", config.coordinator());
      }
      t.set("failMode", config.failMode());
      t.set("defaultTimeout", config.defaultTimeout());
      t.set("screenshotWidth", config.screenshotWidth());
      t.set("screenshotHeight", config.screenshotHeight());
      t.set("screenshotGuiScale", config.screenshotGuiScale());
      t.set("screenshotScale", config.screenshotScale());
      t.set("screenshotChatVisible", LuaValue.valueOf(config.screenshotChatVisible()));
      return t;
    }
  }

  /**
   * minecheck.<name>(...) for a plugin action: invoked on the game thread, and re-invoked once per
   * tick for as long as the plugin returns {@link PluginStep#waiting()}.
   */
  private final class PluginActionFn extends VarArgFunction {

    private final PluginRegistry.RegisteredAction registration;

    PluginActionFn(PluginRegistry.RegisteredAction registration) {
      this.registration = registration;
    }

    @Override
    public Varargs invoke(Varargs args) {
      PluginCall call = new ArgsView(args);
      try {
        while (true) {
          PluginStep step = registration.action().run(call);
          if (step == null) {
            throw new LuaError(
                registration.name() + ": plugin returned no step (return PluginStep.done(...))");
          }
          if (step.finished()) {
            return pluginValueToLua(step.result());
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap(registration.name(), e);
      }
    }
  }

  /**
   * minecheck.<name>(...) for a plugin wait condition: yields each tick until the condition holds,
   * then returns true — or false once {@code config.defaultTimeout} seconds elapse, the same budget
   * {@code waitFor} and {@code waitForPlayer} use.
   */
  private final class PluginWaitFn extends VarArgFunction {

    private final PluginRegistry.RegisteredWait registration;

    PluginWaitFn(PluginRegistry.RegisteredWait registration) {
      this.registration = registration;
    }

    @Override
    public Varargs invoke(Varargs args) {
      PluginCall call = new ArgsView(args);
      long deadline = clock.getAsLong() + config.defaultTimeout() * 1000L;
      try {
        while (true) {
          if (registration.condition().satisfied(call)) {
            return LuaValue.TRUE;
          }
          if (clock.getAsLong() > deadline) {
            return LuaValue.FALSE;
          }
          globals.yield(LuaValue.NIL);
        }
      } catch (LuaError e) {
        throw e;
      } catch (RuntimeException e) {
        throw wrap(registration.name(), e);
      }
    }
  }

  /**
   * {@link PluginCall} over a LuaJ argument list.
   *
   * <p>This class is the boundary: plugin code sees plain Java values only, so no LuaJ type ever
   * leaks into the extension API and a plugin never has to be recompiled against LuaJ.
   */
  private static final class ArgsView implements PluginCall {

    private final Varargs args;

    ArgsView(Varargs args) {
      this.args = args;
    }

    @Override
    public int argCount() {
      return args.narg();
    }

    @Override
    public boolean isNil(int index) {
      return args.arg(index).isnil();
    }

    @Override
    public String optString(int index, String fallback) {
      LuaValue v = args.arg(index);
      return isText(v) ? v.tojstring() : fallback;
    }

    @Override
    public String requireString(int index) {
      LuaValue v = args.arg(index);
      if (!isText(v)) {
        throw new LuaError("expected a string for argument " + index + ", got " + v.typename());
      }
      return v.tojstring();
    }

    @Override
    public double optNumber(int index, double fallback) {
      LuaValue n = args.arg(index).tonumber();
      return n.isnil() ? fallback : n.todouble();
    }

    @Override
    public double requireNumber(int index) {
      LuaValue n = args.arg(index).tonumber();
      if (n.isnil()) {
        throw new LuaError(
            "expected a number for argument " + index + ", got " + args.arg(index).typename());
      }
      return n.todouble();
    }

    @Override
    public boolean optBool(int index, boolean fallback) {
      LuaValue v = args.arg(index);
      return v.isboolean() ? v.toboolean() : fallback;
    }

    @Override
    public java.util.Map<String, Object> tableArg(int index) {
      LuaValue v = args.arg(index);
      return v.istable() ? tableToJava(v.checktable()) : java.util.Map.of();
    }

    @Override
    public Object arg(int index) {
      return argToJava(args.arg(index));
    }

    /**
     * Strings and numbers both read as text (Lua coerces numbers in concatenation); LuaJ's {@code
     * isstring()} is true for numbers too, so check both explicitly rather than rely on it.
     */
    private static boolean isText(LuaValue v) {
      return v.isstring() || v.isnumber();
    }

    private static java.util.Map<String, Object> tableToJava(LuaTable table) {
      java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
      for (LuaValue key : table.keys()) {
        out.put(key.tojstring(), argToJava(table.get(key)));
      }
      return out;
    }

    private static Object argToJava(LuaValue v) {
      if (v.isnil()) {
        return null;
      }
      if (v.isboolean()) {
        return v.toboolean();
      }
      if (v.isnumber()) {
        return v.todouble();
      }
      if (v.isstring()) {
        return v.tojstring();
      }
      if (v.istable()) {
        LuaTable table = v.checktable();
        return isArrayLike(table) ? tableToList(table) : tableToJava(table);
      }
      return null;
    }

    /** True for a table whose keys are exactly {@code 1..length} — a Lua array, not a record. */
    private static boolean isArrayLike(LuaTable table) {
      int length = table.length();
      if (length == 0) {
        return false;
      }
      for (LuaValue key : table.keys()) {
        if (!key.isint() || key.toint() < 1 || key.toint() > length) {
          return false;
        }
      }
      return true;
    }

    private static java.util.List<Object> tableToList(LuaTable table) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (int i = 1; i <= table.length(); i++) {
        out.add(argToJava(table.get(i)));
      }
      return out;
    }
  }

  // ------------------------------------------------------------ live session

  /**
   * minecheck.listen([port][, host]) -> listener
   *
   * <p>A TCP listener on the loopback interface that the script owns: it exists because the script
   * asked for it, it closes when the script ends, and nothing listens otherwise. {@code port} 0
   * (the default) asks the OS for an ephemeral one; the bound port is logged at INFO, which is how
   * a program on the same machine finds an ephemeral listener.
   */
  private final class Listen extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      int port = args.arg(1).optint(0);
      String host = args.arg(2).optjstring(null);
      try {
        LiveListener listener = LiveListener.open(host, port);
        engine.registerLive(listener);
        return listenerTable(listener);
      } catch (LiveException e) {
        throw wrap("listen", e);
      }
    }
  }

  /**
   * Method on a listener handle; {@code handle:accept()} passes the handle, so it is ignored.
   *
   * <p>{@code op} runs inside the poll loop for the ones that wait; the loop is the same
   * cooperative shape every other wait uses: check once, then yield to the next client tick.
   */
  @FunctionalInterface
  private interface LiveOp {
    LuaValue run(Varargs args);
  }

  private final class LiveMethod extends VarArgFunction {
    private final String name;
    private final LiveOp op;

    LiveMethod(String name, LiveOp op) {
      this.name = name;
      this.op = op;
    }

    @Override
    public Varargs invoke(Varargs args) {
      // `handle:method(...)` passes the handle as the first argument; the methods that take
      // arguments must not see it. (`handle.method(...)` does not pass it, so keep the args as they
      // are when the first one is not a table.)
      Varargs rest = args.arg(1).istable() ? args.subargs(2) : args;
      try {
        return op.run(rest);
      } catch (LuaError e) {
        throw e;
      } catch (LiveException e) {
        throw wrap(name, e);
      }
    }
  }

  private LuaTable listenerTable(LiveListener listener) {
    LuaTable t = new LuaTable();
    t.set("port", new LiveMethod("port", args -> LuaValue.valueOf(listener.port())));
    t.set("isOpen", new LiveMethod("isOpen", args -> LuaValue.valueOf(listener.isOpen())));
    t.set(
        "close",
        new LiveMethod(
            "close",
            args -> {
              listener.close();
              return LuaValue.NIL;
            }));
    t.set("accept", new LiveMethod("accept", args -> acceptLoop(listener, args)));
    return t;
  }

  private LuaValue acceptLoop(LiveListener listener, Varargs args) {
    long deadline = deadline(args, 1);
    while (true) {
      LiveConnection conn = listener.poll();
      if (conn != null) {
        engine.registerLive(conn);
        return connectionTable(conn);
      }
      if (clock.getAsLong() > deadline) {
        return LuaValue.NIL;
      }
      globals.yield(LuaValue.NIL);
    }
  }

  private LuaTable connectionTable(LiveConnection conn) {
    LuaTable t = new LuaTable();
    t.set("recv", new LiveMethod("recv", args -> recvLoop(conn, args)));
    t.set(
        "send", new LiveMethod("send", args -> LuaValue.valueOf(conn.send(args.checkjstring(1)))));
    t.set("peer", new LiveMethod("peer", args -> LuaValue.valueOf(conn.peer())));
    t.set("isOpen", new LiveMethod("isOpen", args -> LuaValue.valueOf(conn.isOpen())));
    t.set(
        "close",
        new LiveMethod(
            "close",
            args -> {
              conn.close();
              return LuaValue.NIL;
            }));
    return t;
  }

  private LuaValue recvLoop(LiveConnection conn, Varargs args) {
    long deadline = deadline(args, 1);
    while (true) {
      String line = conn.pollLine();
      if (line != null) {
        return LuaValue.valueOf(line);
      }
      if (!conn.isOpen() || clock.getAsLong() > deadline) {
        return LuaValue.NIL;
      }
      globals.yield(LuaValue.NIL);
    }
  }

  /**
   * minecheck.liveAttach([port][, timeout]) -> int | nil
   *
   * <p>The builtin served breakpoint: listen, report the port, accept one client and serve it until
   * it disconnects, then return the number of chunks served ({@code nil} when no client attached
   * before the timeout). Every line the client sends is a Lua chunk evaluated in the running
   * script's globals, so a served chunk can read script state and call any {@code minecheck}
   * action; the reply is one line, {@code ok <value>} or {@code err <message>}.
   *
   * <p>Because the script is parked inside this call, a chunk that blocks (a {@code sleep}, a
   * {@code waitFor}, a {@code joinServer}) simply suspends the script again and resumes when its
   * condition holds — the game keeps ticking, and the client waits for the chunk to finish before
   * its reply.
   */
  private final class LiveAttach extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      int port = args.arg(1).optint(0);
      double timeoutSeconds = args.arg(2).optdouble(config.defaultTimeout());

      LiveListener listener;
      try {
        listener = LiveListener.open(null, port);
      } catch (LiveException e) {
        throw wrap("liveAttach", e);
      }
      engine.registerLive(listener);
      try {
        long deadline = clock.getAsLong() + (long) (timeoutSeconds * 1000);
        LiveConnection conn = null;
        while (conn == null) {
          conn = listener.poll();
          if (conn != null) {
            break;
          }
          if (clock.getAsLong() > deadline) {
            return LuaValue.NIL;
          }
          globals.yield(LuaValue.NIL);
        }
        engine.registerLive(conn);
        LOG.info(String.format("Minecheck: live client attached (%s)", conn.peer()));

        int served = 0;
        while (true) {
          long lineDeadline = clock.getAsLong() + (long) (timeoutSeconds * 1000);
          String line;
          while (true) {
            try {
              line = conn.pollLine();
            } catch (LiveException e) {
              // A line over the cap is the client's error, not the run's: answer it and move on so
              // the session stays usable.
              conn.send("err " + oneLine(e.getMessage()));
              line = null;
              lineDeadline = clock.getAsLong();
              break;
            }
            if (line != null || !conn.isOpen() || clock.getAsLong() > lineDeadline) {
              break;
            }
            globals.yield(LuaValue.NIL);
          }
          if (line == null) {
            break;
          }
          served++;
          if (!conn.send(evaluate(line))) {
            break;
          }
        }
        LOG.info(String.format("Minecheck: live client detached after %d chunk(s)", served));
        conn.close();
        return LuaValue.valueOf(served);
      } catch (LiveException e) {
        throw wrap("liveAttach", e);
      } finally {
        listener.close();
      }
    }
  }

  /** Runs one served chunk against the script's globals and formats the one-line reply. */
  private String evaluate(String source) {
    try {
      LuaValue chunk = globals.load(source, "@live");
      Varargs out = chunk.invoke(LuaValue.NONE);
      if (out.narg() == 0 || out.arg(1).isnil()) {
        return "ok";
      }
      return "ok " + oneLine(out.arg(1).tojstring());
    } catch (LuaError e) {
      return "err " + oneLine(String.valueOf(e.getMessage()));
    } catch (RuntimeException e) {
      String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      return "err " + oneLine(msg);
    }
  }

  /** One reply is one line: a chunk's message must not smuggle a second frame in. */
  private static String oneLine(String text) {
    return text == null ? "" : text.replace("\r", " ").replace("\n", " ");
  }
}
