package net.minecheck.common.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.result.RunResult;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.ServerPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptEngineTest {

  @TempDir Path tempDir;

  /** Fake client: a join completes after {@code joinTicks} isInWorld() calls. */
  static final class FakeClient implements ClientPlatform {
    int joinTicks = 3;
    int ticks = 0;
    boolean joining;
    String joinedWorld;
    String joinedServer;
    boolean left;
    boolean quit;

    CreateWorldOptions createdWorld;

    @Override
    public void joinLocalWorld(String worldName) {
      joinedWorld = worldName;
      joining = true;
    }

    @Override
    public void createWorld(CreateWorldOptions options) {
      createdWorld = options;
      joining = true;
    }

    @Override
    public void joinServer(String address) {
      joinedServer = address;
      joining = true;
    }

    @Override
    public void leaveWorld() {
      left = true;
      joining = false;
    }

    @Override
    public void quit() {
      quit = true;
    }

    @Override
    public boolean isInWorld() {
      return joining && ++ticks > joinTicks;
    }

    @Override
    public boolean isOnTitleScreen() {
      return !joining;
    }

    boolean worldGenSettled;

    @Override
    public boolean isWorldGenSettled() {
      return worldGenSettled;
    }
  }

  /** Fake server: an online player can be added/removed by the test. */
  static final class FakeServer implements ServerPlatform {
    java.util.Set<String> online = new java.util.HashSet<>();
    java.util.List<String> commands = new java.util.ArrayList<>();

    @Override
    public int runCommand(String command) {
      commands.add(command);
      return 1;
    }

    @Override
    public boolean isPlayerOnline(String name) {
      return online.contains(name);
    }

    boolean worldGenSettled;

    @Override
    public boolean isWorldGenSettled() {
      return worldGenSettled;
    }
  }

  private ScriptEngine serverEngine(
      FakeServer server, ConfigData config, String script, AtomicLong clock) throws Exception {
    Path scriptFile = tempDir.resolve("server-script.lua");
    Files.writeString(scriptFile, script);
    PlatformServices services = serverServices(server);
    return new ScriptEngine(services, config, scriptFile, clock::get);
  }

  /** Services with a real (dedicated) server and no client. */
  private PlatformServices serverServices(FakeServer server) {
    return new PlatformServices() {
      @Override
      public String minecraftVersion() {
        return "1.19.2";
      }

      @Override
      public ConfigDirProvider configDir() {
        return () -> tempDir;
      }

      @Override
      public ClientPlatform client() {
        return null;
      }

      @Override
      public net.minecheck.platform.api.ServerPlatform server() {
        return server;
      }

      @Override
      public boolean dedicatedServer() {
        return true;
      }
    };
  }

  /** Services with a mock client and no server. */
  private PlatformServices clientServices(FakeClient client) {
    return new PlatformServices() {
      @Override
      public String minecraftVersion() {
        return "1.19.2";
      }

      @Override
      public ConfigDirProvider configDir() {
        return () -> tempDir;
      }

      @Override
      public ClientPlatform client() {
        return client;
      }

      @Override
      public net.minecheck.platform.api.ServerPlatform server() {
        return null;
      }

      @Override
      public boolean dedicatedServer() {
        return false;
      }
    };
  }

  private ScriptEngine engine(FakeClient client, ConfigData config, String script)
      throws Exception {
    Path scriptFile = tempDir.resolve("script.lua");
    Files.writeString(scriptFile, script);
    PlatformServices services =
        new PlatformServices() {
          @Override
          public String minecraftVersion() {
            return "1.21.1";
          }

          @Override
          public ConfigDirProvider configDir() {
            return () -> tempDir;
          }

          @Override
          public ClientPlatform client() {
            return client;
          }

          @Override
          public net.minecheck.platform.api.ServerPlatform server() {
            return null;
          }

          @Override
          public boolean dedicatedServer() {
            return false;
          }
        };
    return new ScriptEngine(services, config, scriptFile);
  }

  private ScriptEngine engine(FakeClient client, ConfigData config, String script, AtomicLong clock)
      throws Exception {
    Path scriptFile = tempDir.resolve("script.lua");
    Files.writeString(scriptFile, script);
    PlatformServices services =
        new PlatformServices() {
          @Override
          public String minecraftVersion() {
            return "1.21.1";
          }

          @Override
          public ConfigDirProvider configDir() {
            return () -> tempDir;
          }

          @Override
          public ClientPlatform client() {
            return client;
          }

          @Override
          public net.minecheck.platform.api.ServerPlatform server() {
            return null;
          }

          @Override
          public boolean dedicatedServer() {
            return false;
          }
        };
    return new ScriptEngine(services, config, scriptFile, clock::get);
  }

  private void runUntilFinished(ScriptEngine engine, int maxTicks) {
    for (int i = 0; i < maxTicks && !engine.isFinished(); i++) {
      engine.tick();
    }
  }

  @Test
  void joinLocalWorldBlocksUntilInWorld() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.joinLocalWorld('test')\n"
                + "assert(ok, 'join failed')\n"
                + "minecheck.log('joined')\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished(), "script should finish");
    assertFalse(engine.isFailed());
    assertEquals("test", client.joinedWorld);
  }

  @Test
  void createWorldBlocksUntilInWorld() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.createWorld({ name = 'test', seed = 42, structures = false })\n"
                + "assert(ok, 'create failed')\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished(), "script should finish");
    assertFalse(engine.isFailed());
    assertEquals("test", client.createdWorld.name());
    assertEquals(42L, client.createdWorld.seed());
    assertFalse(client.createdWorld.mobSpawning(), "deterministic default: no mob spawning");
    assertFalse(client.createdWorld.structures(), "deterministic default: no structures");
  }

  @Test
  void createWorldAppliesDefaults() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.createWorld({})\n" + "assert(ok)\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals("minecheck_world", client.createdWorld.name());
    assertEquals("creative", client.createdWorld.gamemode());
    assertTrue(client.createdWorld.allowCheats(), "dev helper default: cheats on");
  }

  @Test
  void joinServerBlocksUntilConnected() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.joinServer('localhost:25565')\n" + "assert(ok, 'join failed')\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals("localhost:25565", client.joinedServer);
  }

  @Test
  void joinTimesOutWhenWorldNeverLoads() throws Exception {
    FakeClient client = new FakeClient();
    client.joinTicks = Integer.MAX_VALUE; // never joins
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            new ConfigData("", null, "exit", 30),
            "local ok = minecheck.joinLocalWorld('test', 1)\n"
                + "assert(ok, 'expected join failure')\n",
            clock);

    engine.tick(); // first tick: script starts, join initiated, yields
    assertFalse(engine.isFinished());
    assertFalse(client.isInWorld());
    clock.addAndGet(5000); // way past the 1s timeout
    runUntilFinished(engine, 10);

    assertTrue(engine.isFinished());
    assertTrue(engine.isFailed(), "assert(ok) should fail the run after timeout");
  }

  @Test
  void leaveWorldWaitsUntilLeft() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.joinLocalWorld('test')\n"
                + "assert(ok)\n"
                + "local left = minecheck.leaveWorld()\n"
                + "assert(left, 'leave failed')\n");
    runUntilFinished(engine, 50);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertTrue(client.left);
  }

  @Test
  void quitActionQuitsClient() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine = engine(client, ConfigData.defaults(), "minecheck.quit()\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertTrue(client.quit);
  }

  @Test
  void scriptErrorWithContinueModeDoesNotQuit() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(client, new ConfigData("", null, "continue", 30), "error('boom')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertTrue(engine.isFailed());
    assertFalse(client.quit, "failMode=continue should not quit the client");
  }

  @Test
  void scriptErrorWithExitModeQuitsClient() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(), // failMode=exit
            "error('boom')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertTrue(engine.isFailed());
    assertTrue(client.quit, "failMode=exit should quit the client");
  }

  @Test
  void waitForYieldsUntilCondition() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.waitFor(function() return minecheck.side() == 'client' end, 5)\n"
                + "assert(ok, 'waitFor failed')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void waitForWorldGenSettledYieldsUntilClientSettled() throws Exception {
    FakeClient client = new FakeClient();
    client.worldGenSettled = false;
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.waitFor(minecheck.worldGenSettled, 30)\n"
                + "assert(ok, 'worldgen never settled')\n",
            clock);
    engine.tick(); // script starts, waitFor polls worldGenSettled (false) and yields
    assertFalse(engine.isFinished());
    client.worldGenSettled = true;
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void waitForWorldGenSettledTimesOutOnClient() throws Exception {
    FakeClient client = new FakeClient();
    client.worldGenSettled = false;
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            new ConfigData("", null, "exit", 30),
            "local ok = minecheck.waitFor(minecheck.worldGenSettled, 1)\n"
                + "assert(ok, 'should have timed out')\n",
            clock);
    engine.tick();
    assertFalse(engine.isFinished());
    clock.addAndGet(5000); // past the 1s timeout
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertTrue(engine.isFailed());
  }

  @Test
  void waitForWorldGenSettledReflectsServerGeneration() throws Exception {
    FakeServer server = new FakeServer();
    server.worldGenSettled = false;
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local ok = minecheck.waitFor(minecheck.worldGenSettled, 30)\n"
                + "assert(ok, 'server worldgen never settled')\n",
            clock);
    engine.tick(); // server not settled yet -> yields
    assertFalse(engine.isFinished());
    server.worldGenSettled = true;
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void runCommandExecutesConsoleCommand() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local r = minecheck.runCommand('stop')\n"
                + "assert(r.success, 'runCommand failed')\n"
                + "assert(r.result == 1)\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("stop"), server.commands);
  }

  @Test
  void waitForPlayerBlocksUntilJoin() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local ok = minecheck.waitForPlayer('Dev', 10)\n"
                + "assert(ok, 'waitForPlayer failed')\n",
            clock);
    engine.tick(); // script starts, waitForPlayer yields (Dev offline)
    assertFalse(engine.isFinished());
    server.online.add("Dev");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void waitForPlayerLeaveReturnsTrueImmediatelyWhenNotOnline() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local ok = minecheck.waitForPlayerLeave('Dev', 10)\n"
                + "assert(ok, 'should return true immediately when not online')\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void waitForPlayerLeaveBlocksUntilLeave() throws Exception {
    FakeServer server = new FakeServer();
    server.online.add("Dev");
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local ok = minecheck.waitForPlayerLeave('Dev', 10)\n"
                + "assert(ok, 'waitForPlayerLeave failed')\n",
            clock);
    engine.tick(); // script starts, waitForPlayerLeave yields (Dev still online)
    assertFalse(engine.isFinished());
    server.online.remove("Dev");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void serverScriptFailureWithExitModeStopsServer() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(), // failMode=exit
            "error('boom')\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertTrue(engine.isFailed());
    assertEquals(
        java.util.List.of("stop"),
        server.commands,
        "failMode=exit on the server should stop the server via console command");
  }

  @Test
  void expectRecordsResultAndReturnsBoolean() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local a = minecheck.expect(true, 'first')\n"
                + "local b = minecheck.expect(false, 'second')\n"
                + "assert(a and not b, 'expect should return the bool')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertFalse(client.quit);
  }

  @Test
  void failActionStopsScriptWithMessage() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.expect(false, 'never happened')\n"
                + "if not ok then minecheck.fail('the world did not load') end\n"
                + "minecheck.log('should not reach here')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertTrue(engine.isFailed());
    assertEquals("the world did not load", engine.failureMessage());
  }

  @Test
  void disconnectReturnsToTitleScreen() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.joinLocalWorld('test')\n"
                + "assert(ok)\n"
                + "local d = minecheck.disconnect()\n"
                + "assert(d, 'disconnect failed')\n"
                + "assert(minecheck.config() ~= nil)\n");
    runUntilFinished(engine, 50);
    assertTrue(engine.isFinished(), "coordinator disconnect should finish");
    assertFalse(engine.isFailed());
    assertTrue(client.left, "disconnect should leave the current world");
  }

  @Test
  void coordinatorRunsMultiplePhasesInOneSession() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local phase = 0\n"
                + "local p1 = minecheck.createWorld({ name = 'w1' })\n"
                + "assert(p1)\n"
                + "local d1 = minecheck.disconnect()\n"
                + "assert(d1)\n"
                + "local p2 = minecheck.joinLocalWorld('w1', 30)\n"
                + "assert(p2)\n"
                + "local d2 = minecheck.disconnect()\n"
                + "assert(d2)\n"
                + "local p3 = minecheck.joinServer('localhost:25565', 30)\n"
                + "assert(p3)\n"
                + "minecheck.quit()\n");
    runUntilFinished(engine, 120);
    assertTrue(engine.isFinished(), "coordinator should finish");
    assertFalse(engine.isFailed());
    assertEquals("w1", client.joinedWorld);
    assertEquals("localhost:25565", client.joinedServer);
    assertTrue(client.quit, "coordinator should quit only at the very end");
  }

  @Test
  void runResultJsonIsWrittenWithExpects() throws Exception {
    FakeClient client = new FakeClient();
    Path resultFile = tempDir.resolve("run-result.json");
    Path scriptFile = tempDir.resolve("result-script.lua");
    Files.writeString(
        scriptFile,
        "minecheck.expect(true, 'one')\n"
            + "minecheck.expect(false, 'two')\n"
            + "local ok = minecheck.expect(true, 'three')\n"
            + "if not ok then minecheck.fail('never') end\n");
    ScriptEngine engine =
        new ScriptEngine(
            clientServices(client),
            ConfigData.defaults(),
            scriptFile,
            resultFile,
            System::currentTimeMillis);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());

    RunResult result = new Gson().fromJson(Files.readString(resultFile), RunResult.class);
    assertEquals("result-script.lua", result.script());
    assertTrue(result.finished());
    assertFalse(result.failed());
    assertEquals(3, result.total());
    assertEquals(2, result.passed());
  }
}
