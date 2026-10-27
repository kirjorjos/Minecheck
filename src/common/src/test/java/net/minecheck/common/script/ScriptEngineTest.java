package net.minecheck.common.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.result.ExpectRecord;
import net.minecheck.common.result.RunResult;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecheck.platform.api.GhostSlot;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.RecipeViewer;
import net.minecheck.platform.api.RecipeViewers;
import net.minecheck.platform.api.ServerPlatform;
import net.minecheck.platform.api.plugin.MinecheckPlugin;
import net.minecheck.platform.api.plugin.MinecheckRegistry;
import net.minecheck.platform.api.plugin.PluginStep;
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
    public boolean canReachServer(String address) {
      return true; // the fake "server" is this client's own join simulation
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

    boolean loadingScreen;

    @Override
    public boolean isWorldReady() {
      return isInWorld() && !loadingScreen;
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

    // Input recording
    final java.util.List<String> keyDowns = new java.util.ArrayList<>();
    final java.util.List<String> keyUps = new java.util.ArrayList<>();
    final java.util.List<Integer> mouseDowns = new java.util.ArrayList<>();
    final java.util.List<Integer> mouseUps = new java.util.ArrayList<>();
    final java.util.Set<String> unresolvedKeys = new java.util.HashSet<>();

    @Override
    public boolean keyDown(String key) {
      if (unresolvedKeys.contains(key)) {
        return false;
      }
      keyDowns.add(key);
      if (key.equals("key.inventory")) {
        openRequested = true;
      }
      return true;
    }

    @Override
    public void keyUp(String key) {
      keyUps.add(key);
    }

    @Override
    public boolean mouseDown(int button) {
      mouseDowns.add(button);
      if (button == 1) {
        openRequested = true;
      }
      return true;
    }

    @Override
    public void mouseUp(int button) {
      mouseUps.add(button);
    }

    final java.util.List<String> clientCommands = new java.util.ArrayList<>();
    int useItemOnTargetCalls = 0;
    final java.util.List<double[]> guiClicks = new java.util.ArrayList<>();

    @Override
    public void runCommand(String command) {
      clientCommands.add(command);
    }

    @Override
    public void useItemOnTarget() {
      useItemOnTargetCalls++;
      openRequested = true;
    }

    @Override
    public boolean clickGui(int button, double x, double y) {
      guiClicks.add(new double[] {x, y, button});
      return openScreenId != null;
    }

    final java.util.List<String> guiElementClicks = new java.util.ArrayList<>();

    /** Labels/ids the fake screen has, in element order (a label may appear several times). */
    final java.util.List<String> guiElements = new java.util.ArrayList<>();

    /** Models the real pick: match by id, then choose the 1-based {@code index} among matches. */
    @Override
    public boolean clickGuiElement(String elementId, int button, int index) {
      guiElementClicks.add(elementId + ":" + button + ":" + index);
      if (openScreenId == null) {
        return false;
      }
      int matches = 0;
      for (String id : guiElements) {
        if (id.equalsIgnoreCase(elementId)) {
          matches++;
        }
      }
      return (index <= 1 ? 0 : index - 1) < matches;
    }

    final java.util.List<String> slotClicks = new java.util.ArrayList<>();
    net.minecheck.platform.api.SlotInfo heldStack;

    static final net.minecheck.platform.api.SlotInfo EMPTY_SLOT =
        new net.minecheck.platform.api.SlotInfo(true, null, 0, null);
    static final int MAX_STACK = 64;

    /**
     * Models the vanilla PICKUP click on an empty-cursor-aware container: a left-click with an
     * empty cursor takes the whole stack, a right-click takes half; with a held stack a left-click
     * places as much as fits and a right-click places exactly one.
     */
    @Override
    public boolean clickSlot(int slot, int button, String actionType) {
      if (openScreenId == null) {
        return false;
      }
      slotClicks.add(slot + ":" + button + ":" + actionType);
      if (!actionType.equals("pickup")) {
        return true;
      }
      net.minecheck.platform.api.SlotInfo current = slots.getOrDefault(slot, EMPTY_SLOT);
      if (heldStack == null) {
        if (current.empty()) {
          return true;
        }
        int take = button == 0 ? current.count() : (current.count() + 1) / 2;
        heldStack =
            new net.minecheck.platform.api.SlotInfo(false, current.id(), take, current.nbt());
        int left = current.count() - take;
        slots.put(
            slot,
            left == 0
                ? EMPTY_SLOT
                : new net.minecheck.platform.api.SlotInfo(
                    false, current.id(), left, current.nbt()));
        return true;
      }
      boolean compatible = current.empty() || current.id().equals(heldStack.id());
      int room = compatible ? MAX_STACK - (current.empty() ? 0 : current.count()) : 0;
      int place = button == 0 ? Math.min(heldStack.count(), room) : Math.min(1, room);
      if (place > 0) {
        int existing = current.empty() ? 0 : current.count();
        slots.put(
            slot,
            new net.minecheck.platform.api.SlotInfo(
                false, heldStack.id(), existing + place, heldStack.nbt()));
        int left = heldStack.count() - place;
        heldStack =
            left == 0
                ? null
                : new net.minecheck.platform.api.SlotInfo(
                    false, heldStack.id(), left, heldStack.nbt());
      }
      return true;
    }

    final java.util.List<String> typed = new java.util.ArrayList<>();
    boolean typeTextResult = true;

    @Override
    public boolean typeText(String text, String target) {
      typed.add(text + "@" + (target == null ? "" : target));
      return typeTextResult;
    }

    /**
     * Client-thread hops. Real loaders run these inline when already on the client thread and queue
     * them for a later tick otherwise; set {@link #deferClientThread} to model the queued case and
     * drive {@link #runClientTasks()} from the test like a client tick would.
     */
    boolean deferClientThread;

    int runOnClientThreadCalls;
    final java.util.ArrayDeque<Runnable> clientTasks = new java.util.ArrayDeque<>();

    @Override
    public void runOnClientThread(Runnable action) {
      runOnClientThreadCalls++;
      if (deferClientThread) {
        clientTasks.add(action);
      } else {
        action.run();
      }
    }

    void runClientTasks() {
      while (!clientTasks.isEmpty()) {
        clientTasks.poll().run();
      }
    }

    int resourceReloads;
    int reloadPendingTicks;

    @Override
    public void reloadResources() {
      resourceReloads++;
      reloadPendingTicks = 2;
    }

    @Override
    public boolean isResourceReloadSettled() {
      if (reloadPendingTicks > 0) {
        reloadPendingTicks--;
        return false;
      }
      return true;
    }

    final java.util.List<String> openedChats = new java.util.ArrayList<>();

    /** The chat screen was asked for: like a real client it appears a few ticks later. */
    @Override
    public void openChat(String mode) {
      openedChats.add(mode);
      openScreenId = "chat";
      openRequested = true;
      openScreenTicks = 0;
    }

    String openScreenId; // currently open screen id (null = none)
    boolean openRequested;
    int openScreenDelay = 2;
    int openScreenTicks = 0;
    java.util.Map<Integer, net.minecheck.platform.api.SlotInfo> slots = new java.util.HashMap<>();

    void openNow(String id) {
      openScreenId = id;
      openRequested = true;
      openScreenTicks = openScreenDelay + 1;
    }

    private boolean screenOpen() {
      if (openScreenId == null || !openRequested) {
        return false;
      }
      return ++openScreenTicks > openScreenDelay;
    }

    @Override
    public boolean isScreenOpen(String screenId) {
      if (!screenOpen()) {
        return false;
      }
      return screenId == null || screenId.isEmpty() || screenId.equals(openScreenId);
    }

    @Override
    public int slotCount(String screenId) {
      if (!isScreenOpen(screenId)) {
        return 0;
      }
      // Model a real container: its size covers the highest recorded slot, and every index in
      // between reads as an empty slot (getSlot returns EMPTY for absent keys). A scan over
      // 0..slotCount-1 therefore sees the recorded slots, like it does on a real client.
      int highest = -1;
      for (int index : slots.keySet()) {
        highest = Math.max(highest, index);
      }
      return highest + 1;
    }

    @Override
    public net.minecheck.platform.api.SlotInfo getSlot(int index, String screenId) {
      if (!isScreenOpen(screenId)) {
        throw new IllegalStateException("no open screen matching '" + screenId + "'");
      }
      net.minecheck.platform.api.SlotInfo s = slots.get(index);
      return s == null ? new net.minecheck.platform.api.SlotInfo(true, null, 0, null) : s;
    }

    // Player / look recording
    final java.util.List<float[]> looks = new java.util.ArrayList<>();
    final java.util.List<double[]> lookAts = new java.util.ArrayList<>();
    net.minecheck.platform.api.PlayerInfo playerInfo =
        new net.minecheck.platform.api.PlayerInfo("Dev", 8.5, 65.0, 8.5, "minecraft:overworld");
    net.minecheck.platform.api.BlockPosition targetBlock =
        new net.minecheck.platform.api.BlockPosition(8, 64, 8);
    boolean inWorld = true;

    @Override
    public net.minecheck.platform.api.PlayerInfo getPlayer() {
      return inWorld ? playerInfo : null;
    }

    @Override
    public void setLook(float pitch, float yaw) {
      looks.add(new float[] {pitch, yaw});
    }

    @Override
    public void lookAt(double x, double y, double z) {
      lookAts.add(new double[] {x, y, z});
    }

    @Override
    public net.minecheck.platform.api.BlockPosition getTargetBlock(String entityName) {
      return entityName.equals(playerInfo.name()) ? targetBlock : null;
    }

    /** Entities the client currently knows about (what the server has synced to it). */
    java.util.List<net.minecheck.platform.api.EntityInfo> clientEntities =
        new java.util.ArrayList<>();

    @Override
    public java.util.List<net.minecheck.platform.api.EntityInfo> findNearbyEntities(String type) {
      java.util.List<net.minecheck.platform.api.EntityInfo> out = new java.util.ArrayList<>();
      for (net.minecheck.platform.api.EntityInfo e : clientEntities) {
        if (type != null && !e.type().equals(type)) {
          continue;
        }
        out.add(e);
      }
      return out;
    }

    final java.util.List<String> blockReads = new java.util.ArrayList<>();
    net.minecheck.platform.api.BlockInfo blockInfo;

    @Override
    public net.minecheck.platform.api.BlockInfo getBlock(
        net.minecheck.platform.api.BlockPosition pos) {
      blockReads.add(pos.x() + "," + pos.y() + "," + pos.z());
      return blockInfo;
    }
  }

  /** Fake recipe viewer: returns canned slots and records fill requests. */
  static final class FakeRecipeViewer implements RecipeViewer {
    String id = "fake";
    boolean available = true;
    final java.util.Map<String, java.util.List<GhostSlot>> slots = new java.util.HashMap<>();
    final java.util.List<String> fills = new java.util.ArrayList<>();
    boolean fillResult = true;

    @Override
    public String id() {
      return id;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public java.util.List<GhostSlot> recipeSlots(String recipeId) {
      return slots.getOrDefault(recipeId, java.util.List.of());
    }

    @Override
    public boolean fillFromRecipe(String recipeId, int maxItems) {
      fills.add(recipeId + ":" + maxItems);
      return fillResult;
    }
  }

  /** Fake server: an online player can be added/removed by the test. */
  static final class FakeServer implements ServerPlatform {
    java.util.Set<String> online = new java.util.HashSet<>();
    java.util.List<String> commands = new java.util.ArrayList<>();

    // Spawn recording: raw args as passed by the Lua layer.
    java.util.List<String> spawnedEntities = new java.util.ArrayList<>();
    java.util.List<String> spawnedBlocks = new java.util.ArrayList<>();
    java.util.List<String> spawnedItemDrops = new java.util.ArrayList<>();
    java.util.List<String> spawnedStructures = new java.util.ArrayList<>();
    boolean refuseReplace;

    java.util.List<net.minecheck.platform.api.EntityInfo> entities = new java.util.ArrayList<>();

    boolean rulesApplied;

    @Override
    public void applyDeterministicRules() {
      rulesApplied = true;
    }

    @Override
    public java.util.List<net.minecheck.platform.api.EntityInfo> findEntities(
        String type, String tag) {
      java.util.List<net.minecheck.platform.api.EntityInfo> out = new java.util.ArrayList<>();
      for (net.minecheck.platform.api.EntityInfo e : entities) {
        if (type != null && !e.type().equals(type)) {
          continue;
        }
        if (tag != null && !e.tags().contains(tag)) {
          continue;
        }
        out.add(e);
      }
      return out;
    }

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

    @Override
    public int spawnEntity(String id, double x, double y, double z, String nbt) {
      spawnedEntities.add(id + "|" + x + "|" + y + "|" + z + "|" + nbt);
      if (id.contains("not_a_mob")) {
        throw new IllegalArgumentException("unknown entity type: " + id);
      }
      return 42;
    }

    @Override
    public boolean spawnBlock(String id, int x, int y, int z, String nbt, boolean replace) {
      spawnedBlocks.add(id + "|" + x + "|" + y + "|" + z + "|" + nbt + "|" + replace);
      return !refuseReplace;
    }

    @Override
    public int spawnItemDrop(String id, int count, double x, double y, double z, String nbt) {
      spawnedItemDrops.add(id + "|" + count + "|" + x + "|" + y + "|" + z + "|" + nbt);
      return 7;
    }

    @Override
    public boolean spawnStructure(String id, int x, int y, int z) {
      spawnedStructures.add(id + "|" + x + "|" + y + "|" + z);
      return true;
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

  /** Like {@link #serverEngine(FakeServer, ConfigData, String, AtomicLong)} but with plugins. */
  private ScriptEngine serverEngine(
      FakeServer server, ConfigData config, String script, PluginRegistry plugins)
      throws Exception {
    Path scriptFile = tempDir.resolve("server-script.lua");
    Files.writeString(scriptFile, script);
    return new ScriptEngine(
        serverServices(server), config, scriptFile, null, System::currentTimeMillis, plugins);
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

  /** Like {@link #engine(FakeClient, ConfigData, String)} but with a recipe viewer installed. */
  private ScriptEngine engineWithViewer(FakeClient client, RecipeViewer viewer, String script)
      throws Exception {
    return engineWithViewers(client, java.util.List.of(viewer), script);
  }

  /** Like {@link #engineWithViewer} but with every listed viewer installed. */
  private ScriptEngine engineWithViewers(
      FakeClient client, java.util.List<RecipeViewer> viewers, String script) throws Exception {
    Path scriptFile = tempDir.resolve("script.lua");
    Files.writeString(scriptFile, script);
    PlatformServices services =
        new PlatformServices() {
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

          @Override
          public RecipeViewer recipeViewer() {
            return RecipeViewers.active(viewers);
          }

          @Override
          public RecipeViewer recipeViewer(String id) {
            return RecipeViewers.byId(viewers, id);
          }

          @Override
          public boolean isModLoaded(String modId) {
            return viewers.stream().anyMatch(v -> v.id().equals(modId));
          }
        };
    return new ScriptEngine(services, ConfigData.defaults(), scriptFile);
  }

  /**
   * Like {@link #engine(FakeClient, ConfigData, String, AtomicLong)} but with plugins registered.
   */
  private ScriptEngine engine(
      FakeClient client, ConfigData config, String script, PluginRegistry plugins, AtomicLong clock)
      throws Exception {
    Path scriptFile = tempDir.resolve("script.lua");
    Files.writeString(scriptFile, script);
    return new ScriptEngine(clientServices(client), config, scriptFile, null, clock::get, plugins);
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
  void createWorldWaitsForLoadingScreenToClear() throws Exception {
    FakeClient client = new FakeClient();
    client.loadingScreen = true; // in world, but the "downloading terrain" screen is still up
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            new ConfigData("", null, "exit", 30),
            "local ok = minecheck.createWorld({ name = 'test' }, 2)\n"
                + "assert(ok, 'create should not return until ready')\n",
            clock);

    engine.tick(); // script starts, createWorld initiated, yields
    assertFalse(engine.isFinished());

    // Let the join simulation complete: the player exists, but behind the loading screen.
    for (int i = 0; i < 5; i++) {
      engine.tick();
    }
    assertTrue(client.isInWorld(), "player exists behind the loading screen");
    assertFalse(client.isWorldReady(), "but not ready to interact");

    clock.addAndGet(5000); // way past the 2s timeout
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertTrue(engine.isFailed(), "assert(ok) should fail: the world never became ready");
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
  void waitWithNoDurationYieldsExactlyOneTick() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "minecheck.wait()\n" + "assert(minecheck.side() == 'client', 'script resumed')\n");

    engine.tick(); // starts the script, which waits
    assertFalse(engine.isFinished(), "one tick waits, it does not resume inline");
    engine.tick();
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void waitWithDurationSleepsUntilItElapses() throws Exception {
    FakeClient client = new FakeClient();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(client, ConfigData.defaults(), "minecheck.wait(5)\n" + "assert(true)\n", clock);

    engine.tick();
    clock.addAndGet(4000); // still inside the 5s window
    engine.tick();
    assertFalse(engine.isFinished());
    clock.addAndGet(2000);
    engine.tick();
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void sleepUntilAliasesWaitFor() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.sleepUntil(function() return minecheck.side() == 'client' end, 5)\n"
                + "assert(ok, 'sleepUntil failed')\n");
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
  void spawnEntityReturnsNetworkIdAndPassesSnbt() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local r = minecheck.spawnEntity('minecraft:cow', { x = 1.5, y = 64, z = -2 }, { NoAI = true, CustomName = '\"Boss\"' })\n"
                + "assert(r.success, 'spawnEntity failed: ' .. tostring(r.error))\n"
                + "assert(r.id == 42, 'expected fake entity id')\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, server.spawnedEntities.size());
    String rec = server.spawnedEntities.get(0);
    assertTrue(rec.startsWith("minecraft:cow|1.5|64.0|-2.0|"), rec);
    assertTrue(rec.contains("\"NoAI\":true"), rec);
    assertTrue(rec.contains("\"CustomName\":\"\\\"Boss\\\"\""), rec);
  }

  @Test
  void spawnEntitySerializesAnInt64AsAnExactLongTag() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            // A plain literal here would have rounded before reaching the SNBT writer, which is why
            // the value has to be built with minecheck.int64.
            "local r = minecheck.spawnEntity('minecraft:cow', { x = 0, y = 64, z = 0 }, { Seed = minecheck.int64('72623859790382856'), NoAI = true })\n"
                + "assert(r.success, 'spawnEntity failed: ' .. tostring(r.error))\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    String rec = server.spawnedEntities.get(0);
    assertTrue(rec.contains("\"Seed\":72623859790382856L"), rec);
    assertTrue(rec.contains("\"NoAI\":true"), rec);
  }

  @Test
  void spawnEntityFailureReturnsErrorTable() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local r = minecheck.spawnEntity('minecraft:not_a_mob', { x = 0, y = 64, z = 0 })\n"
                + "assert(not r.success, 'should fail')\n"
                + "assert(r.error ~= nil, 'error should be set')\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, server.spawnedEntities.size());
  }

  @Test
  void spawnBlockSetsBlockWithReplaceFlag() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local r = minecheck.spawnBlock('minecraft:stone', { x = 3, y = 64, z = 4 }, { }, true)\n"
                + "assert(r.success, 'spawnBlock failed: ' .. tostring(r.error))\n"
                + "assert(r.error == nil)\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, server.spawnedBlocks.size());
    assertEquals("minecraft:stone|3|64|4|{}|true", server.spawnedBlocks.get(0));
  }

  @Test
  void spawnBlockWithoutReplaceReportsOccupied() throws Exception {
    FakeServer server = new FakeServer();
    server.refuseReplace = true;
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local r = minecheck.spawnBlock('minecraft:stone', { x = 3, y = 64, z = 4 })\n"
                + "assert(not r.success, 'occupied position should not be replaced')\n"
                + "assert(r.error ~= nil)\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals("minecraft:stone|3|64|4||false", server.spawnedBlocks.get(0));
  }

  @Test
  void spawnItemDropPassesCountAndNbt() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local r = minecheck.spawnItemDrop('minecraft:diamond', { x = 0, y = 65, z = 0 }, 3, { Count = 3 })\n"
                + "assert(r.success, 'spawnItemDrop failed: ' .. tostring(r.error))\n"
                + "assert(r.id == 7)\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, server.spawnedItemDrops.size());
    String rec = server.spawnedItemDrops.get(0);
    assertTrue(rec.startsWith("minecraft:diamond|3|0.0|65.0|0.0|"), rec);
    assertTrue(rec.contains("\"Count\":3"), rec);
  }

  @Test
  void spawnStructurePlacesTemplate() throws Exception {
    FakeServer server = new FakeServer();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local r = minecheck.spawnStructure('minecraft:igloo/top', { x = 10, y = 70, z = 10 })\n"
                + "assert(r.success, 'spawnStructure failed: ' .. tostring(r.error))\n"
                + "assert(r.error == nil)\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("minecraft:igloo/top|10|70|10"), server.spawnedStructures);
  }

  @Test
  void findEntitiesListsMatchingEntities() throws Exception {
    FakeServer server = new FakeServer();
    server.entities =
        java.util.List.of(
            new net.minecheck.platform.api.EntityInfo(
                42, "minecraft:cow", 1.5, 64.0, -2.0, java.util.Set.of("herd")),
            new net.minecheck.platform.api.EntityInfo(
                7, "minecraft:zombie", 3.0, 64.0, 4.0, java.util.Set.of()),
            new net.minecheck.platform.api.EntityInfo(
                9, "minecraft:cow", 5.0, 64.0, 6.0, java.util.Set.of("herd", "target")));
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local function hasTag(e, tag)\n"
                + "  for _, t in pairs(e.tags) do\n"
                + "    if t == tag then return true end\n"
                + "  end\n"
                + "  return false\n"
                + "end\n"
                + "local all = minecheck.findEntities()\n"
                + "assert(#all == 3, 'expected 3 entities, got ' .. #all)\n"
                + "local cows = minecheck.findEntities('minecraft:cow')\n"
                + "assert(#cows == 2, 'expected 2 cows, got ' .. #cows)\n"
                + "assert(cows[1].id == 42 and cows[1].type == 'minecraft:cow')\n"
                + "assert(cows[1].pos.x == 1.5 and cows[1].pos.y == 64 and cows[1].pos.z == -2)\n"
                + "local herd = minecheck.findEntities('minecraft:cow', 'herd')\n"
                + "assert(#herd == 2, 'expected 2 tagged cows, got ' .. #herd)\n"
                + "local tagged = minecheck.findEntities(nil, 'target')\n"
                + "assert(#tagged == 1 and tagged[1].id == 9, 'expected the tagged cow')\n"
                + "assert(hasTag(tagged[1], 'herd') and hasTag(tagged[1], 'target'), 'tagged entry reports both tags')\n"
                + "local none = minecheck.findEntities('minecraft:ender_dragon')\n"
                + "assert(#none == 0, 'no dragons should match')\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void findNearbyEntitiesOnAClientReadsTheSyncedEntities() throws Exception {
    FakeClient client = new FakeClient();
    client.clientEntities =
        java.util.List.of(
            new net.minecheck.platform.api.EntityInfo(
                11, "minecraft:vex", 9.5, 65.0, 8.5, java.util.Set.of()),
            new net.minecheck.platform.api.EntityInfo(
                12, "minecraft:pig", 4.0, 65.0, 8.5, java.util.Set.of()));
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local vexes = minecheck.findNearbyEntities('minecraft:vex')\n"
                + "assert(#vexes == 1 and vexes[1].id == 11, 'the client sees the synced vex')\n"
                + "assert(vexes[1].pos.x == 9.5, 'entry reports the position the client knows')\n"
                + "assert(#minecheck.findNearbyEntities() == 2, 'no filter returns everything it knows')\n"
                + "-- the point of the client-side query: find it, then aim at it\n"
                + "minecheck.lookAt(vexes[1].pos.x, vexes[1].pos.y + 0.7, vexes[1].pos.z)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, client.lookAts.size(), "the found entity was aimed at");
    assertEquals(9.5, client.lookAts.get(0)[0]);
    assertEquals(65.7, client.lookAts.get(0)[1]);
    assertEquals(8.5, client.lookAts.get(0)[2]);
  }

  @Test
  void findEntitiesRequiresServer() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok, err = pcall(function() minecheck.findEntities() end)\n"
                + "assert(not ok, 'findEntities on a client should fail')\n"
                + "assert(err:find('server%-only') ~= nil, 'error should mention server-only: ' .. tostring(err))\n"
                + "-- the client-side counterpart is the one that works here\n"
                + "assert(#minecheck.findNearbyEntities() == 0, 'findNearbyEntities works on a client')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void findNearbyEntitiesWithNothingSyncedIsEmpty() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(#minecheck.findNearbyEntities() == 0, 'nothing known: empty, not an error')\n"
                + "assert(#minecheck.findNearbyEntities('minecraft:vex') == 0, 'type filter too')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void findNearbyEntitiesRequiresAClient() throws Exception {
    FakeServer server = new FakeServer();
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local ok, err = pcall(function() minecheck.findNearbyEntities() end)\n"
                + "assert(not ok, 'findNearbyEntities on a server should fail')\n"
                + "assert(err:find('client%-only') ~= nil, 'error should mention client-only: ' .. tostring(err))\n",
            new AtomicLong(0));
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void openScreenOpensBeaconAndReadsSlots() throws Exception {
    FakeClient client = new FakeClient();
    client.openScreenId = "beacon";
    client.slots.put(
        0,
        new net.minecheck.platform.api.SlotInfo(
            false,
            "minecraft:emerald",
            64,
            java.util.Map.of("display", java.util.Map.of("Name", "Test Gem"))));
    client.slots.put(1, new net.minecheck.platform.api.SlotInfo(true, null, 0, null));
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.openScreen('beacon', { x = 10, y = 65, z = 8 }, 10)\n"
                + "assert(ok, 'beacon screen should open')\n"
                + "assert(minecheck.isScreenOpen('beacon') == true)\n"
                + "assert(minecheck.isScreenOpen() == true, 'any-screen check should match')\n"
                + "assert(minecheck.slotCount('beacon') == 2, 'slot count should be 2')\n"
                + "local s = minecheck.getSlot(0, 'beacon')\n"
                + "assert(not s.empty and s.id == 'minecraft:emerald' and s.count == 64)\n"
                + "assert(s.nbt.display.Name == 'Test Gem', 'nested nbt read: ' .. tostring(s.nbt))\n"
                + "local e = minecheck.getSlot(1, 'beacon')\n"
                + "assert(e.empty == true, 'second slot should be empty')\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, client.lookAts.size());
    assertEquals(10.0, client.lookAts.get(0)[0], 0.001);
    assertEquals(65.0, client.lookAts.get(0)[1], 0.001);
    assertEquals(8.0, client.lookAts.get(0)[2], 0.001);
    assertEquals(1, client.useItemOnTargetCalls, "block right-click should be dispatched");
  }

  @Test
  void openScreenInventoryPressesInventoryKey() throws Exception {
    FakeClient client = new FakeClient();
    client.openScreenId = "inventory";
    client.slots.put(
        36, new net.minecheck.platform.api.SlotInfo(false, "minecraft:emerald", 64, null));
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.openScreen('inventory', nil, 10)\n"
                + "assert(ok, 'inventory should open')\n"
                + "assert(minecheck.isScreenOpen('inventory') == true)\n"
                + "local s = minecheck.getSlot(36, 'inventory')\n"
                + "assert(not s.empty and s.id == 'minecraft:emerald' and s.count == 64)\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertTrue(client.keyDowns.contains("key.inventory"), "inventory key should be pressed");
    assertTrue(client.keyUps.contains("key.inventory"));
  }

  @Test
  void slotReadsRequireMatchingOpenScreen() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "-- no screen open: slotCount is 0 and getSlot raises\n"
                + "assert(minecheck.slotCount('beacon') == 0, 'no screen -> 0 slots')\n"
                + "local ok, err = pcall(function() minecheck.getSlot(0, 'beacon') end)\n"
                + "assert(not ok, 'getSlot without a screen should fail')\n"
                + "assert(err:find('no open screen') ~= nil, 'unexpected error: ' .. tostring(err))\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void clickGuiDispatchesToOpenScreen() throws Exception {
    FakeClient client = new FakeClient();
    client.openNow("beacon");
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.clickGui(120, 80) == true, 'left click on open screen')\n"
                + "assert(minecheck.clickGui(120, 80, 1) == true, 'right click on open screen')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(2, client.guiClicks.size());
    assertEquals(120.0, client.guiClicks.get(0)[0], 0.001);
    assertEquals(80.0, client.guiClicks.get(0)[1], 0.001);
    assertEquals(0.0, client.guiClicks.get(0)[2], 0.001);
    assertEquals(1.0, client.guiClicks.get(1)[2], 0.001);
  }

  @Test
  void clickGuiByElementIdClicksThatElementNotAPoint() throws Exception {
    FakeClient client = new FakeClient();
    client.openNow("beacon");
    client.guiElements.addAll(java.util.List.of("Cancel", "Done", "Done"));
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.clickGui('Cancel') == true, 'element by label')\n"
                + "assert(minecheck.clickGui('done', 1) == true, 'label is case-insensitive, button passed through')\n"
                + "assert(minecheck.clickGui('Done', 0, 2) == true, 'index picks the second match')\n"
                + "assert(minecheck.clickGui('Done', 0, 3) == false, 'no third match')\n"
                + "assert(minecheck.clickGui('NoSuchButton') == false, 'unknown id -> false')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertTrue(client.guiClicks.isEmpty(), "the element form must not send screen coordinates");
    assertEquals(
        java.util.List.of("Cancel:0:1", "done:1:1", "Done:0:2", "Done:0:3", "NoSuchButton:0:1"),
        client.guiElementClicks);
  }

  @Test
  void clickGuiByElementIdWithoutScreenReturnsFalse() throws Exception {
    FakeClient client = new FakeClient();
    client.guiElements.add("Done");
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.clickGui('Done') == false, 'no screen -> false')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void moveItemMovesStackBetweenSlots() throws Exception {
    FakeClient client = new FakeClient();
    client.openNow("inventory");
    client.slots.put(
        36, new net.minecheck.platform.api.SlotInfo(false, "minecraft:emerald", 64, null));
    client.slots.put(0, new net.minecheck.platform.api.SlotInfo(true, null, 0, null));
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.moveItem(36, 0) == true, 'moveItem dispatched')\n"
                + "local s = minecheck.getSlot(0, 'inventory')\n"
                + "assert(not s.empty and s.id == 'minecraft:emerald' and s.count == 64, 'item landed in destination')\n"
                + "assert(minecheck.getSlot(36, 'inventory').empty, 'source slot emptied')\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("36:0:pickup", "0:0:pickup"), client.slotClicks);
  }

  @Test
  void moveItemMovesExactlyCountItems() throws Exception {
    FakeClient client = new FakeClient();
    client.openNow("beacon");
    client.slots.put(
        28, new net.minecheck.platform.api.SlotInfo(false, "minecraft:emerald", 64, null));
    client.slots.put(1, FakeClient.EMPTY_SLOT);
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.moveItem(28, 1, 10, 'beacon') == true, 'exact count move')\n"
                + "local d = minecheck.getSlot(1, 'beacon')\n"
                + "assert(not d.empty and d.id == 'minecraft:emerald' and d.count == 10, 'destination holds 10: ' .. tostring(d.count))\n"
                + "local s = minecheck.getSlot(28, 'beacon')\n"
                + "assert(not s.empty and s.count == 54, 'source keeps the rest: ' .. tostring(s.count))\n");
    runUntilFinished(engine, 40);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(12, client.slotClicks.size());
    assertEquals("28:0:pickup", client.slotClicks.get(0));
    assertEquals("1:1:pickup", client.slotClicks.get(1));
    assertEquals("28:0:pickup", client.slotClicks.get(client.slotClicks.size() - 1));
    assertTrue(client.heldStack == null, "the cursor is left empty");
  }

  @Test
  void moveItemCountAboveStackSizeMovesWholeStack() throws Exception {
    FakeClient client = new FakeClient();
    client.openNow("beacon");
    client.slots.put(
        28, new net.minecheck.platform.api.SlotInfo(false, "minecraft:emerald", 8, null));
    client.slots.put(1, FakeClient.EMPTY_SLOT);
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.moveItem(28, 1, 999, 'beacon') == true, 'count above the stack size')\n"
                + "assert(minecheck.getSlot(1, 'beacon').count == 8, 'whole stack moved')\n"
                + "assert(minecheck.getSlot(28, 'beacon').empty, 'source emptied')\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("28:0:pickup", "1:0:pickup"), client.slotClicks);
  }

  @Test
  void moveItemZeroCountIsANoOp() throws Exception {
    FakeClient client = new FakeClient();
    client.openNow("beacon");
    client.slots.put(
        28, new net.minecheck.platform.api.SlotInfo(false, "minecraft:emerald", 8, null));
    client.slots.put(1, FakeClient.EMPTY_SLOT);
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.moveItem(28, 1, 0, 'beacon') == false, 'count 0 moves nothing')\n"
                + "assert(minecheck.getSlot(28, 'beacon').count == 8, 'source untouched')\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertTrue(client.slotClicks.isEmpty(), "no clicks for a zero-count move");
  }

  @Test
  void moveItemRestoresTheSourceWhenTheDestinationRefusesTheItems() throws Exception {
    FakeClient client = new FakeClient();
    client.openNow("beacon");
    client.slots.put(
        28, new net.minecheck.platform.api.SlotInfo(false, "minecraft:redstone", 8, null));
    client.slots.put(1, new net.minecheck.platform.api.SlotInfo(false, "minecraft:stone", 8, null));
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.moveItem(28, 1, 3, 'beacon') == false, 'incompatible destination -> false')\n"
                + "assert(minecheck.getSlot(28, 'beacon').count == 8, 'source restored')\n"
                + "assert(minecheck.getSlot(1, 'beacon').count == 8, 'destination untouched')\n",
            clock);
    for (int i = 0; i < 300 && !engine.isFinished(); i++) {
      engine.tick();
      clock.addAndGet(50); // model ~50ms per tick so click timeouts elapse
    }
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("28:0:pickup", "1:1:pickup", "28:0:pickup"), client.slotClicks);
    assertTrue(client.heldStack == null, "the cursor is left empty");
  }

  @Test
  void moveItemRequiresMatchingOpenScreen() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok, err = pcall(function() minecheck.moveItem(36, 0) end)\n"
                + "assert(not ok, 'moveItem without a screen should fail')\n"
                + "assert(err:find('no matching open screen') ~= nil, 'unexpected error: ' .. tostring(err))\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void getBlockReadsIdAndBlockEntityNbt() throws Exception {
    FakeClient client = new FakeClient();
    // Energy is a long tag: 72623859790382856 is not representable in a Lua number, so it must
    // arrive as an int64 rather than a rounded double.
    client.blockInfo =
        new net.minecheck.platform.api.BlockInfo(
            "minecraft:beacon",
            java.util.Map.of("Levels", 4.0, "Primary", 1.0, "Energy", 72623859790382856L));
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local b = minecheck.getBlock({ x = 8.5, y = 64, z = -4 })\n"
                + "assert(b ~= nil, 'block read')\n"
                + "assert(b.id == 'minecraft:beacon', 'block id: ' .. tostring(b.id))\n"
                + "assert(b.nbt ~= nil and b.nbt.Levels == 4, 'block entity nbt: ' .. tostring(b.nbt))\n"
                + "local energy = b.nbt.Energy\n"
                + "assert(tostring(energy) == '72623859790382856', 'long tag exact: ' .. tostring(energy))\n"
                + "assert(energy > 72623859790382848, 'and compared exactly, not as a rounded double')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("8,64,-4"), client.blockReads);
  }

  @Test
  void getBlockReturnsNilWhenThePositionIsNotLoaded() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.getBlock({ x = 0, y = 0, z = 0 }) == nil, 'unloaded -> nil')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void moveItemTakesItsSourceFromAGhostSlotIngredient() throws Exception {
    FakeClient client = new FakeClient();
    client.openNow("inventory");
    // Planes in the hotbar, an empty destination, and one decoy stack that must not be picked.
    client.slots.put(
        36, new net.minecheck.platform.api.SlotInfo(false, "minecraft:oak_planks", 4, null));
    client.slots.put(
        37, new net.minecheck.platform.api.SlotInfo(false, "minecraft:birch_planks", 2, null));
    FakeRecipeViewer viewer = new FakeRecipeViewer();
    viewer.id = "emi";
    viewer.slots.put(
        "minecraft:stick",
        java.util.List.of(new GhostSlot(0, java.util.List.of("minecraft:oak_planks"))));
    ScriptEngine engine =
        engineWithViewer(
            client,
            viewer,
            "assert(minecheck.moveItem({ recipe = 'minecraft:stick', slot = 0 }, 10, 1, 'inventory') == true, 'ghost source resolved')\n"
                + "local s = minecheck.getSlot(10, 'inventory')\n"
                + "assert(not s.empty and s.id == 'minecraft:oak_planks' and s.count == 1, 'one oak plank moved')\n"
                + "assert(minecheck.getSlot(36, 'inventory').count == 3, 'the rest stayed in the source')\n"
                + "assert(minecheck.getSlot(37, 'inventory').count == 2, 'the decoy stack was untouched')\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("36:0:pickup", "10:1:pickup", "36:0:pickup"), client.slotClicks);
  }

  @Test
  void moveItemWithAGhostSlotReferenceReportsUnresolvableCases() throws Exception {
    FakeClient client = new FakeClient();
    client.openNow("inventory");
    FakeRecipeViewer viewer = new FakeRecipeViewer();
    viewer.slots.put(
        "minecraft:stick",
        java.util.List.of(new GhostSlot(0, java.util.List.of("minecraft:oak_planks"))));
    ScriptEngine engine =
        engineWithViewer(
            client,
            viewer,
            // Unknown recipe, unknown slot, and an ingredient the container does not hold: all
            // false, no move.
            "assert(minecheck.moveItem({ recipe = 'minecheck:nope', slot = 0 }, 10, 1, 'inventory') == false, 'unknown recipe')\n"
                + "assert(minecheck.moveItem({ recipe = 'minecraft:stick', slot = 7 }, 10, 1, 'inventory') == false, 'unknown slot')\n"
                + "assert(minecheck.moveItem({ recipe = 'minecraft:stick', slot = 0 }, 10, 1, 'inventory') == false, 'ingredient not in the container')\n"
                // A malformed reference is a programming error, not a silent false.
                + "local ok = pcall(function() minecheck.moveItem({ slot = 0 }, 10, 1, 'inventory') end)\n"
                + "assert(ok == false, 'a reference without recipe raises')\n"
                // Viewer slots are display-only: naming one as the destination raises too.
                + "local ok2, err = pcall(function() minecheck.moveItem(10, { recipe = 'minecraft:stick', slot = 0 }) end)\n"
                + "assert(ok2 == false, 'a ghost destination raises')\n"
                + "assert(string.find(err, 'display%-only') ~= nil, 'the error explains why: ' .. tostring(err))\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertTrue(
        client.slotClicks.isEmpty(), "nothing is clicked when the reference does not resolve");
  }

  @Test
  void recipeViewerReportsIdAndGhostSlots() throws Exception {
    FakeClient client = new FakeClient();
    FakeRecipeViewer viewer = new FakeRecipeViewer();
    viewer.id = "emi";
    viewer.slots.put(
        "minecraft:stick",
        java.util.List.of(
            new GhostSlot(0, java.util.List.of("minecraft:oak_planks")),
            new GhostSlot(
                1, java.util.List.of("minecraft:birch_planks", "minecraft:spruce_planks"))));
    ScriptEngine engine =
        engineWithViewer(
            client,
            viewer,
            "local v = minecheck.recipeViewer()\n"
                + "assert(v ~= nil, 'viewer available')\n"
                + "assert(v.id == 'emi', 'viewer id: ' .. tostring(v.id))\n"
                + "local slots = minecheck.getRecipeSlots('minecraft:stick')\n"
                + "assert(#slots == 2, 'two ghost slots: ' .. #slots)\n"
                + "assert(slots[1].index == 0 and slots[1].items[1] == 'minecraft:oak_planks')\n"
                + "assert(#slots[2].items == 2, 'tag-backed slot lists its members')\n"
                + "assert(#minecheck.getRecipeSlots('minecraft:unknown') == 0, 'unknown recipe -> empty')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void recipeViewerActionsAreInertWithoutAViewer() throws Exception {
    FakeClient client = new FakeClient();
    FakeRecipeViewer viewer = new FakeRecipeViewer();
    viewer.available = false; // installed but not ready -> treated as absent
    ScriptEngine engine =
        engineWithViewer(
            client,
            viewer,
            "assert(minecheck.recipeViewer() == nil, 'no viewer -> nil')\n"
                + "assert(#minecheck.getRecipeSlots('minecraft:stick') == 0, 'no viewer -> no slots')\n"
                + "assert(minecheck.fillFromRecipe('minecraft:stick') == false, 'no viewer -> false')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertTrue(viewer.fills.isEmpty(), "an unavailable viewer is never asked to fill");
  }

  @Test
  void fillFromRecipeForwardsTheMaxItemsCap() throws Exception {
    FakeClient client = new FakeClient();
    FakeRecipeViewer viewer = new FakeRecipeViewer();
    ScriptEngine engine =
        engineWithViewer(
            client,
            viewer,
            "assert(minecheck.fillFromRecipe('minecraft:stick') == true, 'default cap fills')\n"
                + "assert(minecheck.fillFromRecipe('minecraft:stick', 8) == true, 'capped fill')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("minecraft:stick:0", "minecraft:stick:8"), viewer.fills);
  }

  @Test
  void recipeViewersActiveSkipsUnavailableProvidersInOrder() {
    FakeRecipeViewer first = new FakeRecipeViewer();
    first.id = "jei";
    first.available = false;
    FakeRecipeViewer second = new FakeRecipeViewer();
    second.id = "emi";

    assertEquals("emi", RecipeViewers.active(java.util.List.of(first, second)).id());
    assertEquals(null, RecipeViewers.active(java.util.List.of()));
    assertEquals(null, RecipeViewers.active(null));
  }

  @Test
  void recipeViewersByIdPicksTheNamedAvailableProvider() {
    FakeRecipeViewer emi = new FakeRecipeViewer();
    emi.id = "emi";
    FakeRecipeViewer jei = new FakeRecipeViewer();
    jei.id = "jei";
    jei.available = false;

    java.util.List<RecipeViewer> viewers = java.util.List.of(emi, jei);
    assertEquals("emi", RecipeViewers.byId(viewers, "emi").id());
    assertEquals(null, RecipeViewers.byId(viewers, "jei"), "installed but not ready -> nil");
    assertEquals(null, RecipeViewers.byId(viewers, "rei"), "not installed -> nil");
    assertEquals(null, RecipeViewers.byId(viewers, null));
    assertEquals(null, RecipeViewers.byId(null, "emi"));
  }

  @Test
  void recipeViewerActionsCanTargetANamedViewer() throws Exception {
    FakeClient client = new FakeClient();
    FakeRecipeViewer emi = new FakeRecipeViewer();
    emi.id = "emi";
    emi.slots.put("minecraft:stick", java.util.List.of(new GhostSlot(0, java.util.List.of("oak"))));
    FakeRecipeViewer jei = new FakeRecipeViewer();
    jei.id = "jei";
    jei.slots.put(
        "minecraft:stick", java.util.List.of(new GhostSlot(0, java.util.List.of("birch"))));
    jei.fillResult = false;

    ScriptEngine engine =
        engineWithViewers(
            client,
            java.util.List.of(emi, jei),
            "assert(minecheck.recipeViewer().id == 'emi', 'priority viewer is emi')\n"
                + "assert(minecheck.recipeViewer('jei').id == 'jei', 'named viewer is jei')\n"
                + "assert(minecheck.recipeViewer('rei') == nil, 'unknown viewer -> nil')\n"
                + "assert(minecheck.isModLoaded('jei') == true, 'jei installed')\n"
                + "assert(minecheck.isModLoaded('rei') == false, 'rei not installed')\n"
                + "local slots = minecheck.getRecipeSlots('minecraft:stick', 'jei')\n"
                + "assert(#slots == 1 and slots[1].items[1] == 'birch', 'named viewer slots')\n"
                + "assert(minecheck.getRecipeSlots('minecraft:stick')[1].items[1] == 'oak')\n"
                + "assert(minecheck.fillFromRecipe('minecraft:stick', nil, 'emi') == true)\n"
                + "assert(minecheck.fillFromRecipe('minecraft:stick', 8, 'jei') == false)\n"
                + "assert(minecheck.fillFromRecipe('minecraft:stick', 8, 'rei') == false)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("minecraft:stick:0"), emi.fills);
    assertEquals(
        java.util.List.of("minecraft:stick:8"), jei.fills, "unknown viewer is never asked");
  }

  @Test
  void worldStatePredicatesReflectTheClient() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.isOnTitleScreen() == true, 'starts at the title screen')\n"
                + "assert(minecheck.isInWorld() == false, 'not in a world yet')\n"
                + "assert(minecheck.joinLocalWorld('world') == true, 'join the world')\n"
                + "assert(minecheck.isInWorld() == true, 'in the world after joining')\n"
                + "assert(minecheck.isOnTitleScreen() == false, 'no longer at the title screen')\n");
    runUntilFinished(engine, 20);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void typeTextDispatchesTextForEachTarget() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.typeText('hello world') == true, 'no target types into the open field')\n"
                + "assert(minecheck.typeText('hi', 'chat') == true, 'chat target types')\n"
                + "assert(minecheck.typeText('give @s minecraft:stone 1', 'command') == true, 'command target types')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(
        java.util.List.of("hello world@", "hi@chat", "give @s minecraft:stone 1@command"),
        client.typed);
    // The chat screen is opened once — for the chat target — and the already-open screen is reused
    // for the command target (which is where the leading '/' comes from).
    assertEquals(java.util.List.of("chat"), client.openedChats);
  }

  @Test
  void typeTextOpensTheChatOnTheClientThreadThenTypes() throws Exception {
    FakeClient client = new FakeClient();
    client.deferClientThread = true;
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.typeText('hello', 'chat') == true, 'typed once the chat is up')\n");

    // The script asks for the chat first; the typing itself is queued on the client thread.
    engine.tick();
    assertFalse(engine.isFinished());
    assertEquals(java.util.List.of("chat"), client.openedChats);
    assertTrue(client.typed.isEmpty(), "nothing is typed before the chat screen is up");

    // Drive engine ticks and client-thread drains like the loader's tick loop does.
    int guard = 0;
    while (!engine.isFinished() && guard++ < 20) {
      engine.tick();
      client.runClientTasks();
    }
    assertTrue(engine.isFinished(), "script finished");
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("hello@chat"), client.typed);
    assertEquals(1, client.runOnClientThreadCalls, "only the typing was dispatched to the client");
    assertTrue(client.clientTasks.isEmpty(), "the client ran everything it was handed");
  }

  @Test
  void typeTextReturnsFalseWithoutTextInput() throws Exception {
    FakeClient client = new FakeClient();
    client.typeTextResult = false;
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.typeText('nowhere to go') == false, 'no text input -> false')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("nowhere to go@"), client.typed);
  }

  @Test
  void reloadResourcesWaitsForTheReloadToSettle() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.reloadResources() == true, 'reload finished')\n");
    engine.tick(); // request issued, first unsettled poll yields
    assertFalse(engine.isFinished());
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, client.resourceReloads, "the reload was requested once");
  }

  @Test
  void reloadResourcesTimesOutWhenTheReloadNeverSettles() throws Exception {
    FakeClient client = new FakeClient();
    client.reloadPendingTicks = Integer.MAX_VALUE;
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            new ConfigData("", null, "exit", 2),
            "assert(minecheck.reloadResources() == false, 'timeout -> false')\n",
            clock);
    // The fake reports unsettled until its counter runs out; without a settle the script must time
    // out at the 2s default rather than hang.
    engine.tick();
    clock.addAndGet(5000);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, client.resourceReloads);
  }

  @Test
  void clickGuiWithoutScreenReturnsFalse() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.clickGui(4, 4) == false, 'no screen -> false')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void runCommandOnClientSendsPlayerCommand() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local r = minecheck.runCommand('fill ~ ~ ~1 ~8 ~ ~8 minecraft:iron_block')\n"
                + "assert(r.success, 'client runCommand should succeed')\n"
                + "assert(r.result == 0, 'client result count is 0: ' .. tostring(r.result))\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(
        java.util.List.of("fill ~ ~ ~1 ~8 ~ ~8 minecraft:iron_block"), client.clientCommands);
  }

  @Test
  void guiActionsRequireClient() throws Exception {
    FakeServer server = new FakeServer();
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local ok, err = pcall(function() minecheck.openScreen('beacon', { x = 0, y = 1, z = 0 }) end)\n"
                + "assert(not ok, 'openScreen on server should fail')\n"
                + "assert(err:find('client%-only') ~= nil, 'error should mention client-only: ' .. tostring(err))\n"
                + "local ok2, err2 = pcall(function() minecheck.getSlot(0) end)\n"
                + "assert(not ok2 and err2:find('client%-only') ~= nil)\n"
                + "local ok3, err3 = pcall(function() minecheck.isScreenOpen() end)\n"
                + "assert(not ok3 and err3:find('client%-only') ~= nil)\n"
                + "local ok4, err4 = pcall(function() minecheck.clickGui(1, 1) end)\n"
                + "assert(not ok4 and err4:find('client%-only') ~= nil)\n"
                + "local ok5, err5 = pcall(function() minecheck.moveItem(36, 0) end)\n"
                + "assert(not ok5 and err5:find('client%-only') ~= nil)\n"
                + "local ok6, err6 = pcall(function() minecheck.typeText('hi', 'chat') end)\n"
                + "assert(not ok6 and err6:find('client%-only') ~= nil)\n"
                + "local ok7, err7 = pcall(function() minecheck.getBlock({ x = 0, y = 64, z = 0 }) end)\n"
                + "assert(not ok7 and err7:find('client%-only') ~= nil)\n"
                + "local ok8, err8 = pcall(function() minecheck.recipeViewer() end)\n"
                + "assert(not ok8 and err8:find('client%-only') ~= nil)\n"
                + "local ok9, err9 = pcall(function() minecheck.getRecipeSlots('minecraft:stick') end)\n"
                + "assert(not ok9 and err9:find('client%-only') ~= nil)\n"
                + "local ok10, err10 = pcall(function() minecheck.fillFromRecipe('minecraft:stick') end)\n"
                + "assert(not ok10 and err10:find('client%-only') ~= nil)\n"
                + "local ok11, err11 = pcall(function() minecheck.reloadResources() end)\n"
                + "assert(not ok11 and err11:find('client%-only') ~= nil)\n"
                + "local ok12, err12 = pcall(function() minecheck.isInWorld() end)\n"
                + "assert(not ok12 and err12:find('client%-only') ~= nil)\n"
                + "local ok13, err13 = pcall(function() minecheck.isOnTitleScreen() end)\n"
                + "assert(not ok13 and err13:find('client%-only') ~= nil)\n",
            new AtomicLong(0));
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void spawnActionsRequireServer() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok, err = pcall(function() minecheck.spawnEntity('minecraft:cow', { x = 0, y = 64, z = 0 }) end)\n"
                + "assert(not ok, 'spawnEntity on client should fail')\n"
                + "assert(err:find('server%-only') ~= nil, 'error should mention server-only: ' .. tostring(err))\n"
                + "local ok2, err2 = pcall(function() minecheck.spawnBlock('minecraft:stone', { x = 0, y = 64, z = 0 }) end)\n"
                + "assert(not ok2, 'spawnBlock on client should fail')\n"
                + "assert(err2:find('server%-only') ~= nil)\n");
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
                + "assert(minecheck.isOnTitleScreen(), 'disconnect returns to the title screen')\n"
                + "assert(not minecheck.isInWorld(), 'disconnect leaves the world')\n"
                + "assert(minecheck.config() ~= nil)\n");
    runUntilFinished(engine, 50);
    assertTrue(engine.isFinished(), "coordinator disconnect should finish");
    assertFalse(engine.isFailed());
    assertTrue(client.left, "disconnect should leave the current world");
    assertTrue(client.isOnTitleScreen(), "disconnect should end at the title screen");
  }

  @Test
  void configExposesEveryFieldToTheScript() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            new ConfigData("phase.lua", "coordinator.lua", "continue", 90),
            "local c = minecheck.config()\n"
                + "assert(c.script == 'phase.lua', 'script: ' .. tostring(c.script))\n"
                + "assert(c.coordinator == 'coordinator.lua', 'coordinator: ' .. tostring(c.coordinator))\n"
                + "assert(c.failMode == 'continue', 'failMode: ' .. tostring(c.failMode))\n"
                + "assert(c.defaultTimeout == 90, 'timeout: ' .. tostring(c.defaultTimeout))\n"
                + "assert(type(c.defaultTimeout) == 'number', 'the timeout is a number, not a string')\n",
            new AtomicLong());
    runUntilFinished(engine, 10);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  /**
   * The shape of an unset config, which the docs used to describe wrongly: {@code script} is an
   * <b>empty string</b>, never {@code nil} — even when a coordinator is the entry — so a script
   * must test {@code == ""}, and only {@code coordinator} reads as nil.
   */
  @Test
  void configReportsAnAbsentCoordinatorAsNilAndAnUnsetScriptAsAnEmptyString() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local c = minecheck.config()\n"
                + "assert(c.coordinator == nil, 'an absent coordinator reads as nil')\n"
                + "assert(c.script ~= nil, 'script is never nil')\n"
                + "assert(type(c.script) == 'string', 'script is a string')\n"
                + "assert(c.script == '', 'an unset script is empty, got: ' .. tostring(c.script))\n"
                + "assert(c.failMode == 'exit')\n"
                + "assert(c.defaultTimeout == 30)\n",
            new AtomicLong());
    runUntilFinished(engine, 10);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void theConfigViewIsAReadOnlySnapshot() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            new ConfigData("phase.lua", null, "exit", 90),
            "local first = minecheck.config()\n"
                + "first.script = 'hacked.lua'\n"
                + "first.defaultTimeout = 9999\n"
                + "local second = minecheck.config()\n"
                + "assert(second.script == 'phase.lua', 'the config is unchanged: ' .. tostring(second.script))\n"
                + "assert(second.defaultTimeout == 90, 'and so is the timeout: ' .. tostring(second.defaultTimeout))\n"
                + "assert(first ~= second, 'each call returns its own table')\n",
            new AtomicLong());
    runUntilFinished(engine, 10);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  /** No side guard: a dedicated server script reads the config too. */
  @Test
  void configIsReadableOnADedicatedServer() throws Exception {
    FakeServer server = new FakeServer();
    ScriptEngine engine =
        serverEngine(
            server,
            new ConfigData("", null, "exit", 45),
            "local c = minecheck.config()\n"
                + "assert(c.defaultTimeout == 45, 'timeout: ' .. tostring(c.defaultTimeout))\n"
                + "assert(c.coordinator == nil)\n",
            new AtomicLong());
    runUntilFinished(engine, 10);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
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

  /**
   * The answer to "what does an expect record look like when the value is an int64 or a byte
   * string?": the condition's truthiness becomes {@code ok} and the value itself is never in the
   * record. Both are always truthy — an int64 is a table, and a bytes payload is a Lua string,
   * which only {@code nil}/{@code false} are not — exactly the documented {"nil/false = fail,
   * anything else = pass"}. So such a check records a pass, and the report reveals nothing about
   * the value: a script that wants the value in the report must put it in the label.
   */
  @Test
  void expectTurnsAnInt64OrAByteStringConditionIntoOk() throws Exception {
    FakeClient client = new FakeClient();
    Path resultFile = tempDir.resolve("expect-condition-result.json");
    Path scriptFile = tempDir.resolve("expect-condition-script.lua");
    Files.writeString(
        scriptFile,
        "minecheck.expect(minecheck.int64('0'), 'int64 zero is truthy')\n"
            + "minecheck.expect(minecheck.int64('72623859790382856'), 'int64 big is truthy')\n"
            + "minecheck.expect('', 'the empty string is truthy')\n"
            + "minecheck.expect(minecheck.bytes('\\255\\128'), 'high bytes are truthy')\n"
            + "minecheck.expect(nil, 'only nil fails')\n"
            + "minecheck.expect(false, 'only false fails')\n");
    ScriptEngine engine =
        new ScriptEngine(
            clientServices(client),
            ConfigData.defaults(),
            scriptFile,
            resultFile,
            System::currentTimeMillis);
    runUntilFinished(engine, 30);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));

    RunResult result = new Gson().fromJson(Files.readString(resultFile), RunResult.class);
    assertEquals(
        List.of(
            new ExpectRecord("int64 zero is truthy", true),
            new ExpectRecord("int64 big is truthy", true),
            new ExpectRecord("the empty string is truthy", true),
            new ExpectRecord("high bytes are truthy", true),
            new ExpectRecord("only nil fails", false),
            new ExpectRecord("only false fails", false)),
        result.expects());
  }

  /**
   * An int64 (or any table) as the <em>name</em> used to abort the whole script: LuaJ's {@code
   * optjstring} raises for a table, and the docs promise "never aborts the script". It now falls
   * back to the numbered default with a warning, so the run keeps every recorded outcome.
   */
  @Test
  void expectWithAnInt64OrBytesAsTheNameNumbersTheCheckInsteadOfAborting() throws Exception {
    FakeClient client = new FakeClient();
    Path resultFile = tempDir.resolve("expect-name-result.json");
    Path scriptFile = tempDir.resolve("expect-name-script.lua");
    Files.writeString(
        scriptFile,
        "minecheck.expect(true, 'first')\n"
            + "minecheck.expect(true, minecheck.int64('5'))\n"
            + "minecheck.expect(true, minecheck.bytes('\\1\\2'))\n"
            + "minecheck.expect(true, {})\n"
            + "minecheck.expect(false, 'still running after all of those')\n");
    ScriptEngine engine =
        new ScriptEngine(
            clientServices(client),
            ConfigData.defaults(),
            scriptFile,
            resultFile,
            System::currentTimeMillis);
    runUntilFinished(engine, 30);
    assertTrue(engine.isFinished(), "the script must not abort on a non-string name");
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));

    RunResult result = new Gson().fromJson(Files.readString(resultFile), RunResult.class);
    assertEquals(
        List.of(
            new ExpectRecord("first", true),
            new ExpectRecord("expect #2", true),
            new ExpectRecord("expect #3", true),
            new ExpectRecord("expect #4", true),
            new ExpectRecord("still running after all of those", false)),
        result.expects(),
        "a non-text name is numbered, and the run keeps recording after it");
  }

  /** The documented default for an omitted name, which used to be the constant "expect". */
  @Test
  void unnamedExpectsAreNumberedSequentiallyInTheRunResult() throws Exception {
    FakeClient client = new FakeClient();
    Path resultFile = tempDir.resolve("expect-numbered-result.json");
    Path scriptFile = tempDir.resolve("expect-numbered-script.lua");
    Files.writeString(
        scriptFile,
        "minecheck.expect(true)\n"
            + "minecheck.expect(false)\n"
            + "minecheck.expect(true, 'named')\n"
            + "minecheck.expect(true)\n");
    ScriptEngine engine =
        new ScriptEngine(
            clientServices(client),
            ConfigData.defaults(),
            scriptFile,
            resultFile,
            System::currentTimeMillis);
    runUntilFinished(engine, 30);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());

    RunResult result = new Gson().fromJson(Files.readString(resultFile), RunResult.class);
    assertEquals(
        List.of(
            new ExpectRecord("expect #1", true),
            new ExpectRecord("expect #2", false),
            new ExpectRecord("named", true),
            new ExpectRecord("expect #4", true)),
        result.expects());
  }

  /** A name is free text: a byte string, quotes, a backslash, a newline — the file stays valid. */
  @Test
  void aNameThatIsOpaqueTextStillProducesValidJson() throws Exception {
    FakeClient client = new FakeClient();
    Path resultFile = tempDir.resolve("expect-gnarly-name-result.json");
    Path scriptFile = tempDir.resolve("expect-gnarly-name-script.lua");
    Files.writeString(
        scriptFile,
        "minecheck.expect(true, 'a \\255\\128 a \\\"q\\\" \\\\ and \\n newline and é')\n");
    ScriptEngine engine =
        new ScriptEngine(
            clientServices(client),
            ConfigData.defaults(),
            scriptFile,
            resultFile,
            System::currentTimeMillis);
    runUntilFinished(engine, 30);
    assertTrue(engine.isFinished(), "a gnarly name must not break the run");
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));

    // The plugin parses this exact file with its own Gson; invalid JSON would fail here.
    JsonObject json = JsonParser.parseString(Files.readString(resultFile)).getAsJsonObject();
    assertFalse(json.getAsJsonArray("expects").isEmpty());
  }

  // ------------------------------------------------------------ input actions

  @Test
  void pressTapsKeyDownAndUp() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local r = minecheck.press('key.jump')\n"
                + "assert(r.pressed, 'press should succeed')\n"
                + "assert(r.held == nil, 'tap returns no held key')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("key.jump"), client.keyDowns);
    assertEquals(java.util.List.of("key.jump"), client.keyUps, "tap should release after pressing");
  }

  @Test
  void pressWithHoldReleasesAfterTimeout() throws Exception {
    FakeClient client = new FakeClient();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local r = minecheck.press('key.jump', 2)\n"
                + "assert(r.pressed)\n"
                + "assert(not r.held, 'hold timeout should report held=false')\n"
                + "assert(r.released == 'timeout', 'hold should release on timeout')\n",
            clock);
    engine.tick(); // script starts, press pressed down, begins holding
    assertFalse(engine.isFinished());
    assertEquals(java.util.List.of("key.jump"), client.keyDowns);
    assertTrue(client.keyUps.isEmpty(), "key should still be held");
    clock.addAndGet(5000); // way past the 2s hold
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("key.jump"), client.keyUps, "key should be released after hold");
  }

  @Test
  void aHoldNeverReleasesInTheSameClientTickItWasPressedIn() throws Exception {
    FakeClient client = new FakeClient();
    AtomicLong clock = new AtomicLong(0);
    // The predicate is already true, so without the guaranteed tick the release would be dispatched
    // in the same client tick as the press — and the game would never sample the key down.
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local r = minecheck.press('key.jump', function() return true end)\n"
                + "assert(r.released == 'cond', 'released on cond')\n",
            clock);

    engine.tick();
    assertEquals(java.util.List.of("key.jump"), client.keyDowns);
    assertTrue(
        client.keyUps.isEmpty(),
        "a hold must not release in the same client tick it was pressed in");
    runUntilFinished(engine, 5);
    assertEquals(java.util.List.of("key.jump"), client.keyUps);
    assertFalse(engine.isFailed());
  }

  @Test
  void pressWithUntilReleasesWhenConditionTrue() throws Exception {
    FakeClient client = new FakeClient();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local n = 0\n"
                + "local r = minecheck.press('key.jump', nil, function() n = n + 1; return n >= 3 end)\n"
                + "assert(r.pressed)\n"
                + "assert(r.held, 'condition should report held=true')\n"
                + "assert(r.released == 'cond', 'condition release should report cond')\n",
            clock);
    engine.tick(); // script starts, presses, polls condition (false) and yields
    assertFalse(engine.isFinished());
    assertEquals(java.util.List.of("key.jump"), client.keyDowns);
    assertTrue(client.keyUps.isEmpty(), "key held while condition is false");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("key.jump"), client.keyUps, "key released when condition fired");
  }

  @Test
  void pressWithUntilTimesOutWithDefaultTimeout() throws Exception {
    FakeClient client = new FakeClient();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            new ConfigData("", null, "exit", 3),
            "local r = minecheck.press('key.jump', nil, function() return false end)\n"
                + "assert(r.pressed)\n"
                + "assert(not r.held, 'timeout should report held=false')\n"
                + "assert(r.released == 'timeout')\n",
            clock);
    engine.tick();
    assertFalse(engine.isFinished());
    clock.addAndGet(10000); // past the 3s default timeout
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("key.jump"), client.keyUps, "key released on timeout");
  }

  @Test
  void pressUnresolvedKeyReturnsPressedFalse() throws Exception {
    FakeClient client = new FakeClient();
    client.unresolvedKeys.add("key.nonexistent");
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local r = minecheck.press('key.nonexistent')\n"
                + "assert(not r.pressed, 'unresolved key should report pressed=false')\n"
                + "assert(not r.held)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertTrue(client.keyDowns.isEmpty(), "unresolved key should not press anything");
    assertTrue(client.keyUps.isEmpty(), "unresolved key should not release anything");
  }

  @Test
  void pressCombinationPressesAllKeysTogether() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local r = minecheck.pressCombination({ 'key.sneak', 'key.jump' })\n"
                + "assert(r.pressed)\n"
                + "assert(r.held == nil)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("key.sneak", "key.jump"), client.keyDowns);
    assertEquals(java.util.List.of("key.sneak", "key.jump"), client.keyUps);
  }

  @Test
  void pressCombinationFailsAtomicallyOnUnresolvedKey() throws Exception {
    FakeClient client = new FakeClient();
    // The unresolved key comes SECOND on purpose: with it first, nothing is pressed and the test
    // passes without ever exercising the rollback (which is exactly how the leak hid before).
    client.unresolvedKeys.add("key.jump");
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local r = minecheck.pressCombination({ 'key.sneak', 'key.jump' })\n"
                + "assert(not r.pressed, 'combination with unresolved key should fail')\n"
                + "assert(r.held == false, 'a failed combination holds nothing')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(
        java.util.List.of("key.sneak"), client.keyDowns, "the key before the failure is pressed");
    assertEquals(
        java.util.List.of("key.sneak"), client.keyUps, "and released again - nothing stays held");
  }

  @Test
  void mousePressesAndReleasesButton() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local r = minecheck.mouse(0)\n" + "assert(r.pressed)\n" + "assert(r.held == nil)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of(0), client.mouseDowns);
    assertEquals(java.util.List.of(0), client.mouseUps);
  }

  @Test
  void mouseHoldUntilReleasesOnCondition() throws Exception {
    FakeClient client = new FakeClient();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local n = 0\n"
                + "local r = minecheck.mouse(1, nil, function() n = n + 1; return n >= 3 end)\n"
                + "assert(r.pressed)\n"
                + "assert(r.held)\n"
                + "assert(r.released == 'cond')\n",
            clock);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of(1), client.mouseDowns);
    assertEquals(java.util.List.of(1), client.mouseUps);
  }

  // ------------------------------------------------------------ player / look / target

  @Test
  void getPlayerReturnsNamePosAndDimension() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local p = minecheck.getPlayer()\n"
                + "assert(p ~= nil, 'player should exist')\n"
                + "assert(p.name == 'Dev')\n"
                + "assert(p.pos.x == 8.5 and p.pos.y == 65.0 and p.pos.z == 8.5)\n"
                + "assert(p.dimension == 'minecraft:overworld')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void getPlayerReturnsNilWhenNotInWorld() throws Exception {
    FakeClient client = new FakeClient();
    client.inWorld = false;
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local p = minecheck.getPlayer()\n"
                + "assert(p == nil, 'no player outside a world')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void lookSetsPitchAndYaw() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client, ConfigData.defaults(), "local ok = minecheck.look(90, 0)\n" + "assert(ok)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, client.looks.size());
    assertEquals(90.0f, client.looks.get(0)[0]);
    assertEquals(0.0f, client.looks.get(0)[1]);
  }

  @Test
  void lookAtAimsAtPosition() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.lookAt(8, 64, 8)\n" + "assert(ok)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(1, client.lookAts.size());
    assertEquals(8.0, client.lookAts.get(0)[0]);
    assertEquals(64.0, client.lookAts.get(0)[1]);
    assertEquals(8.0, client.lookAts.get(0)[2]);
  }

  @Test
  void getTargetBlockRaycastsFromPlayer() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local p = minecheck.getPlayer()\n"
                + "local t = minecheck.getTargetBlock(p)\n"
                + "assert(t ~= nil, 'should hit the block below')\n"
                + "assert(t.x == 8 and t.y == 64 and t.z == 8)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void getTargetBlockReturnsNilForUnknownEntity() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local t = minecheck.getTargetBlock({ name = 'Stranger' })\n"
                + "assert(t == nil, 'unknown entity should miss')\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void keyPressTapsKey() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local ok = minecheck.keyPress('key.jump')\n" + "assert(ok)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of("key.jump"), client.keyDowns);
    assertEquals(java.util.List.of("key.jump"), client.keyUps);
  }

  @Test
  void mousePressTapsButton() throws Exception {
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client, ConfigData.defaults(), "local ok = minecheck.mousePress(2)\n" + "assert(ok)\n");
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(java.util.List.of(2), client.mouseDowns);
    assertEquals(java.util.List.of(2), client.mouseUps);
  }

  @Test
  void inputActionsRequireClient() throws Exception {
    FakeServer server = new FakeServer();
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "local ok, err = pcall(function() minecheck.press('key.jump') end)\n"
                + "assert(not ok, 'press on server should fail')\n"
                + "assert(err:find('client%-only') ~= nil, 'error should mention client-only: ' .. tostring(err))\n",
            new AtomicLong(0));
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  // ------------------------------------------------------------ plugin extension points

  /** A plugin whose {@code register} body is supplied inline. */
  private record InlinePlugin(String id, java.util.function.Consumer<MinecheckRegistry> body)
      implements MinecheckPlugin {

    @Override
    public void register(MinecheckRegistry registry) {
      body.accept(registry);
    }
  }

  @Test
  void pluginActionReceivesArgumentsAndReturnsValues() throws Exception {
    PluginRegistry plugins = new PluginRegistry(false);
    assertTrue(
        plugins.register(
            new InlinePlugin(
                "mymod",
                r -> {
                  r.action(
                      "echo",
                      call ->
                          PluginStep.done(
                              call.requireString(1) + ":" + (int) call.requireNumber(2)));
                  r.action(
                      "point",
                      call ->
                          PluginStep.done(
                              new double[] {call.optNumber(1, 0), call.optNumber(2, 0)}));
                  r.action("nothing", call -> PluginStep.done());
                  r.action(
                      "record",
                      call ->
                          PluginStep.done(
                              java.util.Map.of(
                                  "name", call.optString(1, "?"),
                                  "flag", call.optBool(2, false))));
                })));
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.echo('hi', 7) == 'hi:7', 'string and number arguments')\n"
                + "local p = minecheck.point(3, -4)\n"
                + "assert(p[1] == 3 and p[2] == -4, 'an array result becomes a Lua array')\n"
                + "assert(minecheck.nothing() == nil, 'no result is nil')\n"
                + "local rec = minecheck.record('ana', true)\n"
                + "assert(rec.name == 'ana' and rec.flag == true, 'a map result becomes a Lua table')\n"
                + "assert(minecheck.ext.mymod.echo('x', 1) == 'x:1', 'the namespaced index works')\n",
            plugins,
            new AtomicLong(0));
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void pluginActionIsReinvokedEachTickUntilItFinishes() throws Exception {
    PluginRegistry plugins = new PluginRegistry(false);
    java.util.concurrent.atomic.AtomicInteger steps =
        new java.util.concurrent.atomic.AtomicInteger();
    assertTrue(
        plugins.register(
            new InlinePlugin(
                "ticker",
                r ->
                    r.action(
                        "slow",
                        call ->
                            steps.incrementAndGet() < 3
                                ? PluginStep.waiting()
                                : PluginStep.done("after " + steps.get() + " ticks")))));
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "local v = minecheck.slow()\n"
                + "assert(v == 'after 3 ticks', 'got ' .. tostring(v))\n",
            plugins,
            new AtomicLong(0));
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(3, steps.get(), "one step per tick");
  }

  @Test
  void pluginWaitConditionYieldsUntilThePluginReportsReady() throws Exception {
    PluginRegistry plugins = new PluginRegistry(false);
    java.util.concurrent.atomic.AtomicInteger polls =
        new java.util.concurrent.atomic.AtomicInteger();
    assertTrue(
        plugins.register(
            new InlinePlugin(
                "waiter", r -> r.waitCondition("ready", call -> polls.incrementAndGet() >= 3))));
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.ready(), 'the condition eventually holds')\n",
            plugins,
            new AtomicLong(0));
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(3, polls.get(), "polled once per tick until satisfied");
  }

  @Test
  void pluginWaitConditionReturnsFalseAfterTheDefaultTimeout() throws Exception {
    PluginRegistry plugins = new PluginRegistry(false);
    assertTrue(
        plugins.register(new InlinePlugin("waiter", r -> r.waitCondition("never", call -> false))));
    FakeClient client = new FakeClient();
    AtomicLong clock = new AtomicLong(0);
    ScriptEngine engine =
        engine(
            client,
            new ConfigData("", null, "exit", 1),
            "assert(minecheck.never() == false, 'a wait that never holds times out')\n",
            plugins,
            clock);
    for (int i = 0; i < 5 && !engine.isFinished(); i++) {
      clock.addAndGet(600); // two 600 ms ticks exhaust the 1 s budget
      engine.tick();
    }
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }

  @Test
  void pluginCallConvertsTablesAndRejectsWrongArgumentTypes() throws Exception {
    PluginRegistry plugins = new PluginRegistry(false);
    assertTrue(
        plugins.register(
            new InlinePlugin(
                "args",
                r -> {
                  r.action("firstName", call -> PluginStep.done(call.tableArg(1).get("name")));
                  r.action("firstItem", call -> PluginStep.done(call.arg(1)));
                  r.action("needsNumber", call -> PluginStep.done(call.requireNumber(1)));
                })));
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.firstName({ name = 'ana', extra = 1 }) == 'ana', 'a named table becomes a map')\n"
                + "local it = minecheck.firstItem({ 10, 20 })\n"
                + "assert(it[1] == 10 and it[2] == 20, 'an array table arrives as a list')\n"
                + "local ok, err = pcall(function() minecheck.needsNumber({}) end)\n"
                + "assert(not ok, 'a table where a number belongs must raise')\n"
                + "assert(tostring(err):find('number') ~= nil, 'the error names the expected type: ' .. tostring(err))\n",
            plugins,
            new AtomicLong(0));
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed(), engine.failureMessage());
  }

  @Test
  void pluginNameCollidingWithABuiltinIsSkippedRatherThanShadowingIt() throws Exception {
    PluginRegistry plugins = new PluginRegistry(false);
    java.util.concurrent.atomic.AtomicInteger hijackCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    assertTrue(
        plugins.register(
            new InlinePlugin(
                "shadow",
                r -> {
                  r.action(
                      "log",
                      call -> {
                        hijackCalls.incrementAndGet();
                        return PluginStep.done("hijacked");
                      });
                  r.action("own", call -> PluginStep.done("ok"));
                })));
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.log('still the built-in') == nil, 'the built-in was not shadowed')\n"
                + "assert(minecheck.own() == 'ok', 'the non-colliding action still registers')\n",
            plugins,
            new AtomicLong(0));
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
    assertEquals(0, hijackCalls.get(), "the colliding action must never run");
  }

  @Test
  void aSideAwarePluginRegistersOnlyItsServerBranchOnADedicatedServer() throws Exception {
    PluginRegistry plugins = new PluginRegistry(true);
    assertTrue(
        plugins.register(
            new InlinePlugin(
                "sided",
                r -> {
                  if (r.dedicatedServer()) {
                    r.action("sided_probe", call -> PluginStep.done("server"));
                  } else {
                    r.action("sided_probe", call -> PluginStep.done("client"));
                  }
                })));
    FakeServer server = new FakeServer();
    ScriptEngine engine =
        serverEngine(
            server,
            ConfigData.defaults(),
            "assert(minecheck.sided_probe() == 'server', 'the server branch registered')\n",
            plugins);
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed(), engine.failureMessage());
  }

  @Test
  void pluginsDiscoveredThroughServiceLoaderAreCallableFromLua() throws Exception {
    PluginRegistry plugins = new PluginRegistry(false);
    for (MinecheckPlugin plugin : PluginRegistry.discover()) {
      plugins.register(plugin);
    }
    FakeClient client = new FakeClient();
    ScriptEngine engine =
        engine(
            client,
            ConfigData.defaults(),
            "assert(minecheck.discovered_add(2, 3) == 5, 'a discovered plugin action runs')\n",
            plugins,
            new AtomicLong(0));
    runUntilFinished(engine, 10);
    assertTrue(engine.isFinished());
    assertFalse(engine.isFailed());
  }
}
