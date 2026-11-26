package net.minecheck.loader.neoforge;

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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;

@Mod("minecheck")
public class NeoForgeEntrypoint {

  private final boolean dedicated;
  private final NeoForgeClientPlatform client;
  private final NeoForgeServerPlatform serverPlatform;
  private final NeoForgeNetworkPlatform network;
  private volatile ScriptEngine engine;
  private final AtomicBoolean serverTickRunning = new AtomicBoolean();
  private final AtomicBoolean rulesApplied = new AtomicBoolean();

  public NeoForgeEntrypoint(IEventBus modBus) {
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
    client = dedicated ? null : new NeoForgeClientPlatform();
    serverPlatform = dedicated ? new NeoForgeServerPlatform() : null;
    network = new NeoForgeNetworkPlatform(dedicated);

    // Registration order is the priority order, and EMI wins where it is installed: it is the only
    // one of the three that both displays and transfers a recipe on this line. It ships a NeoForge
    // build for 1.20.4, so unlike Forge this loader prefers it over JEI.
    final List<RecipeViewer> viewers = new ArrayList<>();
    if (!dedicated && ModList.get().isLoaded("emi")) {
      viewers.add(new NeoForgeEmiRecipeViewer());
    }
    if (!dedicated && ModList.get().isLoaded("jei")) {
      viewers.add(new NeoForgeJeiRecipeViewer());
    }
    // REI's mod id is `roughlyenoughitems`; the provider reports the short id `rei`.
    if (!dedicated && ModList.get().isLoaded("roughlyenoughitems")) {
      viewers.add(new NeoForgeReiRecipeViewer());
    }

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
    TickClock.get().bind("neoforge");

    MinecheckBootstrap.init(services, engine -> this.engine = engine);
    // Registered whether or not a script is configured: the client tick hook is where per-frame
    // client work lives, and a client with no script used to have no hook at all. Every handler
    // below still gates its work on the engine, so a passive client behaves as it did; camera work
    // belongs at the top of the client tick, above that gate.
    NeoForge.EVENT_BUS.register(this);
    // NeoForge 20.2+ registers network payloads through the mod bus; the handler list is built by
    // the registrar, so this has to happen from the mod constructor.
    modBus.addListener(network::register);
    if (!dedicated) {
      // Key bindings reach the options list through this event instead of a registry call, so the
      // listener has to exist before the client builds its options.
      modBus.addListener(this::onRegisterKeyMappings);
    }
  }

  private void onRegisterKeyMappings(
      net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
    client.installCameraBindings(event);
  }

  /**
   * Per-frame hook for the screenshot suite: a capture must read a frame drawn after it cleared the
   * chat, and a render tick is the only thing that says "a frame was drawn" (a client tick is not —
   * the client can tick several times between two frames).
   */
  @SubscribeEvent
  public void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
    if (client != null && engine != null) {
      NeoForgeClientPlatform.notifyFrameDrawn();
    }
  }

  @SubscribeEvent
  public void onClientTickPre(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
    if (client != null) {
      // The camera runs before the player does: it holds the body's rotation at the value the
      // server last saw, and doing that after the player ticked would be after the packet that
      // carries the change.
      client.camera().tick();
    }
  }

  @SubscribeEvent
  public void onClientTickPost(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
    if (client == null) {
      return;
    }
    ScriptEngine e = engine;
    if (e == null || e.isFinished()) {
      return;
    }
    client.pinClientClock();
    client.pinRenderTickDelta();
    client.pumpViewWindow();
    if (client.isBootComplete()) {
      e.tick();
    }
  }

  /**
   * Installs the server-side channel tap on each player connection as it appears, so a script that
   * started watching a channel before the client joined still sees that client's payloads.
   */
  @SubscribeEvent
  public void onPlayerLogin(
      net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
    if (engine == null) {
      return;
    }
    if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
      NeoForgeServerChannelTap.installFor(player);
    }
  }

  @SubscribeEvent
  public void onServerTickPre(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event) {
    // Before this tick's world work: a held clock parks the server thread here, which is what keeps
    // the world from advancing between two captures.
    TickClock.get().awaitTick();
  }

  @SubscribeEvent
  public void onServerTickPost(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
    TickClock.get().countTick();
    if (serverPlatform == null) {
      return;
    }
    ScriptEngine e = engine;
    if (e == null || e.isFinished()) {
      return;
    }
    // 1.19.2's ServerTickEvent carries the server reference; 1.18.2's did not.
    MinecraftServer s = event.getServer();
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
