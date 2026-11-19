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
import net.minecheck.common.bootstrap.MinecheckBootstrap;
import net.minecheck.common.script.ScriptEngine;
import net.minecheck.common.tick.TickClock;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.RecipeViewer;
import net.minecheck.platform.api.RecipeViewers;
import net.minecheck.platform.api.ServerPlatform;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecraft.server.MinecraftServer;

/**
 * The Legacy Fabric entrypoint.
 *
 * <p>Legacy Fabric ships the loader and Mixin but no Fabric API, so this mod is an entrypoint plus
 * mixins and nothing else: the loader calls {@link #onInitialize()}, the tick and payload mixins
 * call the static hooks below. There is no client-setup event, so the client-side work that the
 * newer lines do at client setup is done on the first client tick.
 *
 * <p>The server side is wired here in full. The client platform is the piece still to come on this
 * line, so {@link #onClientTickEnd()} currently only advances the shared tick clock; a client
 * boots, joins and renders, but a client-side script action has nowhere to go yet. That is a
 * deliberate placeholder rather than a silent failure: {@link PlatformServices#client()} answers
 * {@code null}, which the common bootstrap already treats as "no client actions on this side".
 */
public final class LegacyFabricEntrypoint implements ModInitializer {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** The process's one entrypoint; the mixins reach it through this. */
  private static volatile LegacyFabricEntrypoint instance;

  private boolean dedicated;
  private ClientPlatform client;
  private LegacyFabricServerPlatform serverPlatform;
  private LegacyFabricNetworkPlatform network;
  private volatile ScriptEngine engine;

  private final AtomicBoolean serverTickRunning = new AtomicBoolean();
  private final AtomicBoolean clientTickRunning = new AtomicBoolean();
  private final AtomicBoolean rulesApplied = new AtomicBoolean();

  /** The running dedicated or integrated server, published for the packet transport. */
  private volatile MinecraftServer server;

  @Override
  public void onInitialize() {
    instance = this;

    FabricLoader loader = FabricLoader.getInstance();
    Path configDir = loader.getConfigDir();
    // Read from the loader rather than a constant: a hardcoded version silently reports the wrong
    // one the moment this build supports another Minecraft version.
    String mcVersion =
        loader
            .getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("1.12.2");

    dedicated = loader.getEnvironmentType() == EnvType.SERVER;
    // Both sides have a server platform: a dedicated server answers every server-side action with
    // it, and a client needs it to query its own integrated server.
    serverPlatform = new LegacyFabricServerPlatform();
    network = new LegacyFabricNetworkPlatform(dedicated);

    // No recipe viewer is bridged on 1.12.2: EMI and REI do not exist for it, and JEI's 4.x line
    // predates the API these bridges were written against. The Lua API is unchanged.
    final List<RecipeViewer> viewers = new ArrayList<>();

    PlatformServices services =
        new PlatformServices() {
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
            return FabricLoader.getInstance().isModLoaded(modId);
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

  /** Client tick start (from {@code ClientTickMixin}); currently only what both sides share. */
  public static void onClientTickStart() {}

  /**
   * Client tick end: advances nothing but the shared clock.
   *
   * <p>The engine step belongs here once the client platform exists — a client script action needs
   * screens, input and a world to act on, which is that platform.
   */
  public static void onClientTickEnd() {
    LegacyFabricEntrypoint entrypoint = instance;
    if (entrypoint == null || entrypoint.dedicated) {
      return;
    }
    entrypoint.clientTickRunning.set(false);
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
