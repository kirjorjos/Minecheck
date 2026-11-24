package net.minecheck.loader.legacyfabric;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecheck.common.bootstrap.MinecheckBootstrap;
import net.minecheck.common.script.ScriptEngine;
import net.minecheck.common.tick.TickClock;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.PlatformServicesBase;
import net.minecheck.platform.api.RecipeViewer;
import net.minecheck.platform.api.RecipeViewers;
import net.minecheck.platform.api.ServerPlatform;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecraft.server.MinecraftServer;

/**
 * The Legacy Fabric entrypoint.
 *
 * <p>Legacy Fabric ships the loader and Mixin but no Fabric API, so this mod is an entrypoint plus
 * mixins and nothing else: the loader calls {@link #onInitialize()}, and the tick, frame, input,
 * payload and sound mixins call the static hooks below. There is no client-setup event, so the
 * client-side setup work the newer lines do at setup — camera install, pause-on-focus-loss — runs
 * on the first client tick instead.
 */
public final class LegacyFabricEntrypoint implements ModInitializer {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** The process's one entrypoint; the mixins reach it through this. */
  private static volatile LegacyFabricEntrypoint instance;

  private boolean dedicated;
  private LegacyFabricClientPlatform client;
  private LegacyFabricServerPlatform serverPlatform;
  private LegacyFabricNetworkPlatform network;
  private volatile ScriptEngine engine;

  private final AtomicBoolean serverTickRunning = new AtomicBoolean();
  private final AtomicBoolean clientTickRunning = new AtomicBoolean();
  private final AtomicBoolean rulesApplied = new AtomicBoolean();

  /** Set once the one-time client setup has run. */
  private boolean clientSetupDone;

  /** The running dedicated or integrated server, published for the packet transport. */
  private volatile MinecraftServer server;

  @Override
  public void onInitialize() {
    instance = this;

    FabricLoader loader = LegacyFabricLoaders.get();
    // Absolute, because this loader answers with a relative `config` on this line: a relative
    // config dir makes the run directory derived from it relative too, so the dedicated server's
    // staged save was copied beside the config instead of into the run directory and the server
    // generated a world of its own.
    Path configDir = loader.getConfigDir().toAbsolutePath().normalize();
    // Read from the loader rather than a constant: a hardcoded version silently reports the wrong
    // one the moment this build supports another Minecraft version. Walked rather than asked for by
    // id: `getModContainer` answers with an `Optional`, and this line's downgrade rewrites that
    // call to the stub `Optional` while the loader's own jar still returns `java.util.Optional` —
    // the runtime then dies with NoSuchMethodError on the erasure (the boot arm found exactly
    // that). `getAllMods` has no newer-than-Java-7 type in its signature.
    String found = "1.12.2";
    for (ModContainer container : loader.getAllMods()) {
      if ("minecraft".equals(container.getMetadata().getId())) {
        found = container.getMetadata().getVersion().getFriendlyString();
        break;
      }
    }
    final String mcVersion = found;

    dedicated = loader.getEnvironmentType() == EnvType.SERVER;
    // Both sides have a server platform: a dedicated server answers every server-side action with
    // it, and a client needs it to query its own integrated server.
    serverPlatform = new LegacyFabricServerPlatform();
    network = new LegacyFabricNetworkPlatform(dedicated);
    if (!dedicated) {
      client = new LegacyFabricClientPlatform(configDir);
    }

    // No recipe viewer is bridged on 1.12.2: EMI and REI do not exist for it, and JEI's 4.x line
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
          public ClientPlatform client() {
            return client;
          }

          @Override
          public ServerPlatform server() {
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
            return LegacyFabricLoaders.get().isModLoaded(modId);
          }
        };

    TickClock.get().bind("legacyfabric");

    MinecheckBootstrap.init(services, engine -> this.engine = engine);
    LOG.info(
        String.format(
            "Minecheck: legacyfabric entrypoint ready for %s (%s)",
            mcVersion, dedicated ? "dedicated server" : "client"));
  }

  /** The running server, or {@code null} before one exists. Used by the packet transport. */
  public static MinecraftServer server() {
    LegacyFabricEntrypoint entrypoint = instance;
    return entrypoint == null ? null : entrypoint.server;
  }

  /** The script engine, or {@code null} before one is configured. */
  static ScriptEngine engine() {
    LegacyFabricEntrypoint entrypoint = instance;
    return entrypoint == null ? null : entrypoint.engine;
  }

  static boolean dedicated() {
    LegacyFabricEntrypoint entrypoint = instance;
    return entrypoint != null && entrypoint.dedicated;
  }

  // ------------------------------------------------------------------ tick hooks

  /**
   * Client tick start, from {@code ClientTickMixin}: the work that must precede the player tick.
   */
  public static void onClientTickStart() {
    LegacyFabricEntrypoint entrypoint = instance;
    if (entrypoint == null || entrypoint.dedicated || entrypoint.client == null) {
      return;
    }
    // A leave a script asked for runs here, not on the scheduled-task queue: the world teardown
    // queues a client task and waits for it, which cannot happen while that queue is draining.
    LegacyFabricClientPlatform.drainClientTasks();
    entrypoint.client.applyPendingLeave();
    // Same reason, and the same place: a quit has to drop the world from the tick so the integrated
    // server writes its save before the client stops.
    entrypoint.client.applyPendingQuit();
    // The camera runs before the player does: it holds the body's rotation at the value the server
    // last saw, and doing that after the player ticked would be after the packet that carries the
    // change.
    // This is the client's own thread: the platform has no `isOnThread` to ask, so the tick is
    // where it learns the thread the leave and quit checks compare against.
    LegacyFabricClientPlatform.markClientThread();
    LegacySilentSound.shutDownDevice();
    entrypoint.client.camera().tick();
  }

  /**
   * Client tick end, from {@code ClientTickMixin}: the engine step and the per-tick platform work.
   */
  public static void onClientTickEnd() {
    LegacyFabricEntrypoint entrypoint = instance;
    if (entrypoint == null || entrypoint.dedicated || entrypoint.client == null) {
      return;
    }
    if (!entrypoint.clientSetupDone) {
      entrypoint.clientSetupDone = true;
      entrypoint.client.installCameraBindings();
      entrypoint.client.allowUnfocusedPlay();
    }
    entrypoint.client.hideWindowIfConfigured();
    ScriptEngine e = entrypoint.engine;
    if (e == null || e.isFinished()) {
      return;
    }
    entrypoint.client.pinClientClock();
    entrypoint.client.pumpViewWindow();
    if (entrypoint.client.isBootComplete()) {
      entrypoint.client.allowUnfocusedPlay();
      // Not inline: this line's client tick runs on the JVM's main thread, and LuaJ refuses to
      // yield a coroutine resumed there ("cannot yield main thread") — which every waitFor does.
      // Hand the tick to a worker, one at a time so a slow script step cannot overlap itself.
      if (entrypoint.clientTickRunning.compareAndSet(false, true)) {
        CompletableFuture.runAsync(
            () -> {
              try {
                e.tick();
              } finally {
                entrypoint.clientTickRunning.set(false);
              }
            });
      }
    }
  }

  /** Server tick start: a held clock parks the server thread here. */
  public static void onServerTickStart() {
    TickClock.get().awaitTick();
  }

  /**
   * Server tick end, from {@code ServerTickMixin}, on both a dedicated and an integrated server.
   *
   * @param ticking the server whose tick just ended
   */
  public static void onServerTickEnd(MinecraftServer ticking) {
    TickClock.get().countTick();
    LegacyFabricEntrypoint entrypoint = instance;
    if (entrypoint == null || ticking == null) {
      return;
    }
    entrypoint.server = ticking;
    LegacyFabricServerPlatform platform = entrypoint.serverPlatform;
    if (platform == null) {
      return;
    }
    platform.setServer(ticking);
    // Payloads arrive on a netty thread; this is the tick boundary they are handed over at.
    LegacyFabricNetworkPlatform.drainServerTasks();
    if (entrypoint.rulesApplied.compareAndSet(false, true)) {
      platform.applyDeterministicRules();
    }
    // A client runs an integrated server too, and its tick fires as well; ticking the engine from
    // both would resume one coroutine from two threads at once. The client tick is the single
    // driver there, as on the other lines.
    if (!entrypoint.dedicated) {
      return;
    }
    ScriptEngine e = entrypoint.engine;
    if (e == null || e.isFinished()) {
      return;
    }
    if (entrypoint.serverTickRunning.compareAndSet(false, true)) {
      CompletableFuture.runAsync(
          () -> {
            try {
              e.tick();
            } finally {
              entrypoint.serverTickRunning.set(false);
            }
          });
    }
  }
}
