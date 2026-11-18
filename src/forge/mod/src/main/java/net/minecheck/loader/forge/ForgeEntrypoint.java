package net.minecheck.loader.forge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecheck.common.bootstrap.MinecheckBootstrap;
import net.minecheck.common.script.ScriptEngine;
import net.minecheck.common.tick.TickClock;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.RecipeViewer;
import net.minecheck.platform.api.RecipeViewers;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod("minecheck")
public class ForgeEntrypoint {

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger("Minecheck");

  private final boolean dedicated;
  private final ForgeClientPlatform client;

  /**
   * The client-only event hooks, held for the run's lifetime. Forge's event bus keeps no strong
   * reference of its own to a registered listener object, so an inline {@code new
   * ForgeClientHooks()} could be collected and would then silently stop receiving events.
   */
  private final ForgeClientHooks clientHooks;

  private final ForgeServerPlatform serverPlatform;
  private final ForgeNetworkPlatform network;
  private volatile ScriptEngine engine;
  private final AtomicBoolean serverTickRunning = new AtomicBoolean();
  private final AtomicBoolean rulesApplied = new AtomicBoolean();

  public ForgeEntrypoint() {
    Path configDir = FMLPaths.CONFIGDIR.get();
    // Read from the running game rather than a constant: a hardcoded version silently reports the
    // wrong one the moment this build supports another Minecraft version. Mirrors the Fabric/Quilt
    // entrypoints, which read the same value from their loader's mod metadata.
    String mcVersion =
        ModList.get()
            .getModContainerById("minecraft")
            .map(container -> container.getModInfo().getVersion().toString())
            .orElseThrow(() -> new RuntimeException("Failed to get MC version"));

    dedicated = FMLEnvironment.dist.isDedicatedServer();
    client = dedicated ? null : new ForgeClientPlatform();
    clientHooks = dedicated ? null : new ForgeClientHooks();
    serverPlatform = dedicated ? new ForgeServerPlatform() : null;
    network = new ForgeNetworkPlatform(dedicated);

    // No recipe viewer is bridged on 1.16.5: EMI and REI publish nothing for it, and JEI's 7.x
    // line predates the API these bridges were written against. The Lua API is unchanged.
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
            return ModList.get().isLoaded(modId);
          }
        };

    // The tick gate: the server's loop is ours to hold (on a client, the integrated one), so a
    // script
    // can stop the world between frames and advance it by a tick count rather than by however many
    // ticks a runner managed to render. Bound before any script exists — the clock belongs to the
    // server, not to a run — and in both environments. The actions stay behind `ownTickClock`.
    TickClock.get().bind("forge");

    MinecheckBootstrap.init(services, engine -> this.engine = engine);
    // Registered whether or not a script is configured: the client tick hook is where per-frame
    // client work lives, and a client with no script used to have no hook at all. Every handler
    // below still gates its work on the engine, so a passive client behaves as it did; camera work
    // belongs at the top of the client tick, above that gate.
    MinecraftForge.EVENT_BUS.register(this);
    if (!dedicated) {
      net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get()
          .getModEventBus()
          .addListener(this::onClientSetup);
      // Client-only, and registered from here so the dedicated server never loads the class: the
      // input event's own type names a client-only class, and Forge's dist cleaner rejects that
      // class being loaded on a server at all.
      MinecraftForge.EVENT_BUS.register(clientHooks);
    }
  }

  /** Key bindings belong to client setup: the options list they register into exists by then. */
  private void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
    // 31.2's FMLClientSetupEvent has no enqueueWork: low-code mods run the work inline.
    client.installCameraBindings();
  }

  /**
   * Per-frame hook for the screenshot suite: a capture must read a frame drawn after it cleared the
   * chat, and a render tick is the only thing that says "a frame was drawn" (a client tick is not —
   * the client can tick several times between two frames).
   */
  @SubscribeEvent
  public void onRenderTick(TickEvent.RenderTickEvent event) {
    if (event.phase == TickEvent.Phase.END && client != null && engine != null) {
      ForgeClientPlatform.notifyFrameDrawn();
    }
  }

  @SubscribeEvent
  public void onClientTick(TickEvent.ClientTickEvent event) {
    if (event.phase == TickEvent.Phase.START) {
      if (client != null) {
        // The camera runs before the player does: it holds the body's rotation at the value the
        // server last saw, and doing that after the player ticked would be after the packet that
        // carries the change.
        ForgeSilentSound.shutDownDevice();
        client.camera().tick();
      }
      return;
    }
    if (event.phase != TickEvent.Phase.END || client == null) {
      return;
    }
    client.hideWindowIfConfigured();
    ScriptEngine e = engine;
    if (e == null || e.isFinished()) {
      return;
    }
    client.pinClientClock();
    client.pinRenderTickDelta();
    client.pumpViewWindow();
    if (client.isBootComplete()) {
      client.allowUnfocusedPlay();
      e.tick();
    }
  }

  /**
   * Installs the server-side channel tap on each player connection as it appears, so a script that
   * started watching a channel before the client joined still sees that client's payloads.
   */
  @SubscribeEvent
  public void onPlayerLogin(
      net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
    if (engine == null) {
      return;
    }
    if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
      ForgeServerChannelTap.installFor(player);
    }
  }

  @SubscribeEvent
  public void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase == TickEvent.Phase.START) {
      // Before this tick's world work: a held clock parks the server thread here, which is what
      // keeps the world from advancing between two captures.
      TickClock.get().awaitTick();
      return;
    }
    if (event.phase != TickEvent.Phase.END) {
      return;
    }
    TickClock.get().countTick();
    if (serverPlatform == null) {
      return;
    }
    ScriptEngine e = engine;
    if (e == null || e.isFinished()) {
      return;
    }
    // 1.18.2's ServerTickEvent carries no server reference.
    MinecraftServer s = net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer();
    if (s == null) {
      return;
    }
    serverPlatform.setServer(s);
    if (rulesApplied.compareAndSet(false, true)) {
      serverPlatform.applyDeterministicRules();
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
}
