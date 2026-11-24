package net.minecheck.loader.forge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecheck.common.bootstrap.MinecheckBootstrap;
import net.minecheck.common.script.ScriptEngine;
import net.minecheck.common.tick.TickClock;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.PlatformServicesBase;
import net.minecheck.platform.api.RecipeViewer;
import net.minecheck.platform.api.RecipeViewers;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;

/**
 * The Forge 1.4.7 entrypoint.
 *
 * <p>This line predates FML's event bus: the mod class is constructed by Forge, its lifecycle
 * arrives as {@code @Mod.EventHandler}-style annotation methods, and ticks are an {@link
 * ITickHandler} registered with {@link TickRegistry}. The only event bus 1.4.7 has is Forge's
 * gameplay bus, which is where the client's sound refusal is registered.
 *
 * <p>Client-only types live in {@link ForgeClientHooks} for the same reason as on the newer lines:
 * loading that class on a dedicated server would name client classes.
 */
@Mod(modid = "minecheck", name = "Minecheck", acceptedMinecraftVersions = "*")
public class ForgeEntrypoint {

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger("Minecheck");

  /**
   * Makes this process's Minecheck log lines reach the console.
   *
   * <p>1.4.7's FML installs handlers for the loggers it knows by name and leaves the root logger
   * without one, so a {@code java.util.logging} logger of the mod's own wrote nowhere: the run
   * looked silent even while it was failing, which is exactly when the lines matter. Installed only
   * when nothing above this logger would print, so a launcher that already routes it keeps its own
   * formatting.
   */
  private static void ensureLogVisible() {
    for (java.util.logging.Logger logger = LOG; logger != null; logger = logger.getParent()) {
      if (logger.getHandlers().length > 0) {
        return;
      }
    }
    java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler();
    handler.setFormatter(
        new java.util.logging.Formatter() {
          @Override
          public String format(java.util.logging.LogRecord record) {
            return String.format(
                "[%1$tT] [Minecheck/%2$s] %3$s%n",
                new java.util.Date(record.getMillis()), record.getLevel(), formatMessage(record));
          }
        });
    LOG.addHandler(handler);
    LOG.setUseParentHandlers(false);
  }

  /** The process's one entrypoint; the platform reaches it through this. */
  private static volatile ForgeEntrypoint instance;

  private boolean dedicated;
  private ForgeClientPlatform client;

  /**
   * The client-only hooks, held for the run's lifetime: the 1.4.7 event bus keeps no strong
   * reference of its own to a registered listener, so an inline instance could be collected and
   * then silently stop receiving events.
   */
  private ForgeClientHooks clientHooks;

  private ForgeServerPlatform serverPlatform;
  private ForgeNetworkPlatform network;
  private volatile ScriptEngine engine;

  /** The running dedicated or integrated server, published for the client platform's queries. */
  private volatile MinecraftServer server;

  private final AtomicBoolean serverTickRunning = new AtomicBoolean();
  private final AtomicBoolean clientTickRunning = new AtomicBoolean();
  private final AtomicBoolean rulesApplied = new AtomicBoolean();

  private final ITickHandler ticker =
      new ITickHandler() {
        @Override
        public void tickStart(EnumSet<TickType> types, Object... data) {
          if (types.contains(TickType.CLIENT)) {
            tickClientStart();
          }
          if (types.contains(TickType.SERVER)) {
            TickClock.get().awaitTick();
          }
          if (types.contains(TickType.RENDER) && client != null) {
            client.pinRenderTickDelta();
          }
        }

        @Override
        public void tickEnd(EnumSet<TickType> types, Object... data) {
          if (types.contains(TickType.SERVER)) {
            tickServerEnd();
          }
          if (types.contains(TickType.CLIENT)) {
            tickClientEnd();
          }
          if (types.contains(TickType.RENDER) && client != null && engine != null) {
            ForgeClientPlatform.notifyFrameDrawn();
          }
        }

        @Override
        public EnumSet<TickType> ticks() {
          return EnumSet.of(TickType.CLIENT, TickType.SERVER, TickType.RENDER);
        }

        @Override
        public String getLabel() {
          return "Minecheck";
        }
      };

  @Mod.PreInit
  public void preInit(FMLPreInitializationEvent event) {
    instance = this;
    ensureLogVisible();
    Path configDir = Loader.instance().getConfigDir().toPath();
    // Read from the loader rather than a constant: a hardcoded version silently reports the wrong
    // one the moment this build supports another Minecraft version.
    String mcVersion = Loader.instance().getMCVersionString().replace("Minecraft ", "").trim();

    dedicated = FMLCommonHandler.instance().getSide().isServer();
    client = dedicated ? null : new ForgeClientPlatform(configDir);
    clientHooks = dedicated ? null : new ForgeClientHooks();
    // Both sides have one. A dedicated server needs it to answer every server-side script action,
    // and a client needs it to query its integrated server.
    serverPlatform = new ForgeServerPlatform();
    network = new ForgeNetworkPlatform(dedicated);

    // No recipe viewer is bridged on 1.4.7: EMI and REI do not exist for it, and JEI's 1.x line
    // predates the API these bridges were written against. The Lua API is unchanged.
    final List<RecipeViewer> viewers = new ArrayList<>();

    PlatformServices services =
        new PlatformServicesBase() {
          @Override
          public String minecraftVersion() {
            return mcVersion;
          }

          @Override
          public ConfigDirProvider configDir() {
            return () -> configDir;
          }

          @Override
          public net.minecheck.platform.api.ClientPlatform client() {
            return client;
          }

          @Override
          public net.minecheck.platform.api.ServerPlatform server() {
            return serverPlatform;
          }

          @Override
          public boolean dedicatedServer() {
            return dedicated;
          }

          @Override
          public NetworkPlatform network() {
            return network;
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
            // Case-insensitively, because this line's own ids are not the lowercase ones the Lua
            // API documents: Forge registers itself as "Forge".
            if (Loader.isModLoaded(modId)) {
              return true;
            }
            for (ModContainer mod : Loader.instance().getModList()) {
              if (mod.getModId().equalsIgnoreCase(modId)) {
                return true;
              }
            }
            return false;
          }
        };

    TickClock.get().bind("forge");

    MinecheckBootstrap.init(services, engine -> this.engine = engine);
    // Ticks arrive through FML's tick registry on 1.4.7: one handler instance on each side, so the
    // shared fields are updated from whichever side is ticking.
    TickRegistry.registerTickHandler(ticker, Side.SERVER);
    if (!dedicated) {
      TickRegistry.registerTickHandler(ticker, Side.CLIENT);
      MinecraftForge.EVENT_BUS.register(clientHooks);
    }
  }

  /** Client setup: this line has no key-binding registry, so the camera toggle is polled. */
  @Mod.Init
  public void init(FMLInitializationEvent event) {
    if (!dedicated && client != null) {
      client.installCameraBindings();
      client.allowUnfocusedPlay();
    }
  }

  private void tickClientStart() {
    if (client == null) {
      return;
    }
    ForgeClientPlatform.markClientThread();
    // Script calls arrive on the engine's worker thread; the work that touches the game runs here.
    ForgeClientPlatform.drainClientTasks();
    // A leave a script asked for runs here, not on a scheduled-task queue: the world teardown
    // queues a client task and waits for it, which cannot happen while that queue is draining.
    ForgeClientPlatform.applyPendingInput();
    client.applyPendingLeave();
    client.applyPendingQuit();
    ForgeSilentSound.shutDownDevice();
    client.camera().tick();
    ForgeClientHooks.tickStart();
  }

  private void tickClientEnd() {
    if (client == null) {
      return;
    }
    client.hideWindowIfConfigured();
    ScriptEngine e = engine;
    if (e == null || e.isFinished()) {
      return;
    }
    client.pinClientClock();
    client.pumpViewWindow();
    if (client.isBootComplete()) {
      client.allowUnfocusedPlay();
      // Not inline: the client tick runs on the JVM's main thread, and LuaJ refuses to yield a
      // coroutine resumed there ("cannot yield main thread") — which every waitFor does. Hand the
      // tick to a worker like the server path does, one at a time.
      if (clientTickRunning.compareAndSet(false, true)) {
        CompletableFuture.runAsync(
            () -> {
              try {
                e.tick();
              } finally {
                clientTickRunning.set(false);
              }
            });
      }
    }
  }

  private void tickServerEnd() {
    TickClock.get().countTick();
    if (serverPlatform == null) {
      return;
    }
    MinecraftServer s = MinecraftServer.getServer();
    if (s == null) {
      return;
    }
    this.server = s;
    serverPlatform.setServer(s);
    // `/setblock` and `/fill` are not commands this line has; they are registered once the server
    // exists so a client's chat commands reach them. Idempotent, so ticking is harmless.
    LegacyBlockCommands.register(s);
    // Payloads can arrive on a network thread during login; this is the tick boundary they are
    // handed over at.
    ForgeNetworkPlatform.drainServerTasks();
    if (rulesApplied.compareAndSet(false, true)) {
      serverPlatform.applyDeterministicRules();
    }
    // A client runs an integrated server too; ticking the engine from both would resume one
    // coroutine from two threads at once. The client tick is the single driver there.
    if (!dedicated) {
      return;
    }
    ScriptEngine e = engine;
    if (e == null || e.isFinished()) {
      return;
    }
    if (serverTickRunning.compareAndSet(false, true)) {
      CompletableFuture.runAsync(
          () -> {
            try {
              e.tick();
            } finally {
              serverTickRunning.set(false);
            }
          });
    }
  }

  /** The running server, or {@code null} before one exists. */
  public static MinecraftServer server() {
    ForgeEntrypoint entrypoint = instance;
    return entrypoint == null ? null : entrypoint.server;
  }
}
