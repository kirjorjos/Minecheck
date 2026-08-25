package net.minecheck.common.script;

import java.util.function.LongSupplier;
import java.util.logging.Logger;
import net.minecheck.common.config.ConfigData;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.ServerPlatform;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
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

  private final Globals globals;
  private final PlatformServices services;
  private final ClientPlatform client;
  private final ServerPlatform server;
  private final ConfigData config;
  private final LongSupplier clock;
  private final ScriptEngine engine;

  public LuaMinecheck(
      Globals globals,
      PlatformServices services,
      ConfigData config,
      LongSupplier clock,
      ScriptEngine engine) {
    this.globals = globals;
    this.services = services;
    this.client = services.client();
    this.server = services.server();
    this.config = config;
    this.clock = clock;
    this.engine = engine;
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
    minecheck.set("waitFor", new WaitFor());
    minecheck.set("worldGenSettled", new WorldGenSettled());
    minecheck.set("side", new Side());
    minecheck.set("minecraftVersion", new MinecraftVersion());
    minecheck.set("config", new ConfigView());

    // Dedicated-server actions
    minecheck.set("runCommand", new RunCommand());
    minecheck.set("waitForPlayer", new WaitForPlayer());
    minecheck.set("waitForPlayerLeave", new WaitForPlayerLeave());
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

  private static LuaError wrap(String action, RuntimeException e) {
    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    return new LuaError(action + ": " + msg);
  }

  // ------------------------------------------------------------ world entry / exit

  /** minecheck.joinLocalWorld(name, [timeout]) -> boolean */
  private final class JoinLocalWorld extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String name = args.arg1().checkjstring();
      long deadline = deadline(args, 2);
      boolean initiated = false;
      try {
        while (true) {
          if (!initiated) {
            client.joinLocalWorld(name);
            initiated = true;
          }
          if (client.isInWorld()) {
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
        throw wrap("joinLocalWorld", e);
      }
    }
  }

  /**
   * minecheck.createWorld(opts, [timeout]) -> boolean
   *
   * <p>opts: name (default "minecheck_world"), seed (number|string, default 0), gamemode (default
   * "creative"), difficulty (default "normal"), structures (default false), mobSpawning (default
   * false), allowCheats (default true).
   *
   * <p>Deterministic defaults: superflat, no structures, no mob spawning — so nothing random can
   * happen (spec §9.3). The client auto-joins once the world is generated; returns true once
   * in-world.
   */
  private final class CreateWorld extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      LuaTable opts = args.arg1().checktable();
      long deadline = deadline(args, 2);
      CreateWorldOptions options = parseOptions(opts);
      boolean initiated = false;
      try {
        while (true) {
          if (!initiated) {
            client.createWorld(options);
            initiated = true;
          }
          if (client.isInWorld()) {
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
      return new CreateWorldOptions(
          name, seed, gamemode, difficulty, structures, mobSpawning, allowCheats);
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
    @Override
    public Varargs invoke(Varargs args) {
      requireClient();
      String address = args.arg1().checkjstring();
      long deadline = deadline(args, 2);
      boolean initiated = false;
      long lastAttempt = 0L;
      try {
        while (true) {
          long now = clock.getAsLong();
          if (!client.isInWorld()
              && (!initiated || (!client.isConnecting() && now - lastAttempt >= 2000))) {
            client.joinServer(address);
            initiated = true;
            lastAttempt = now;
          }
          if (client.isInWorld()) {
            return LuaValue.TRUE;
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
        engine.recordExpect(args.arg(2).optjstring("expect"), ok);
      }
      return LuaValue.valueOf(ok);
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

  // ------------------------------------------------------------ server actions

  /**
   * minecheck.runCommand(cmd) -> table
   *
   * <p>Server: executes {@code cmd} (no leading '/') as a console command and returns {@code {
   * success = true, result = <result count> }}.
   */
  private final class RunCommand extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      requireServer();
      String cmd = args.arg1().checkjstring();
      int result;
      try {
        result = server.runCommand(cmd);
      } catch (RuntimeException e) {
        throw wrap("runCommand", e);
      }
      LuaTable t = new LuaTable();
      t.set("success", LuaValue.TRUE);
      t.set("result", LuaValue.valueOf(result));
      t.set("error", LuaValue.NIL);
      return t;
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

  // ------------------------------------------------------------ utilities

  /** minecheck.log(msg, [level]) -> nil */
  private final class LogAction extends VarArgFunction {
    @Override
    public Varargs invoke(Varargs args) {
      String msg = args.arg1().checkjstring();
      String level = args.arg(2).optjstring("info");
      switch (level.toLowerCase()) {
        case "warn":
          LOG.warning("[script] " + msg);
          break;
        case "error":
          LOG.severe("[script] " + msg);
          break;
        default:
          LOG.info("[script] " + msg);
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

  /** minecheck.waitFor(condition, [timeout]) -> boolean */
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
      return t;
    }
  }
}
