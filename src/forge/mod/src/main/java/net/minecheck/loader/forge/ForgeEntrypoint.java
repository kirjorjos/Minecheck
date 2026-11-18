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
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The 1.12.2 entrypoint.
 *
 * <p>This line is loaded by FML, not ModLauncher: the mod class is constructed by Forge and its
 * lifecycle arrives as {@code @Mod.EventHandler} methods. There is no mod event bus, so every hook
 * is a {@link MinecraftForge#EVENT_BUS} listener and the client ones live in {@link
 * ForgeClientHooks} for the same reason as on the newer lines: loading that class on a dedicated
 * server would name client-only types.
 */
@Mod(modid = "minecheck", name = "Minecheck", acceptableRemoteVersions = "*")
public class ForgeEntrypoint {

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger("Minecheck");

  private boolean dedicated;
  private ForgeClientPlatform client;

  /**
   * The client-only event hooks, held for the run's lifetime. Forge's event bus keeps no strong
   * reference of its own to a registered listener object, so an inline {@code new
   * ForgeClientHooks()} could be collected and would then silently stop receiving events.
   */
  private ForgeClientHooks clientHooks;

  private ForgeServerPlatform serverPlatform;
  private ForgeNetworkPlatform network;
  private volatile ScriptEngine engine;
  private final AtomicBoolean serverTickRunning = new AtomicBoolean();
  private final AtomicBoolean clientTickRunning = new AtomicBoolean();
  private final AtomicBoolean rulesApplied = new AtomicBoolean();

  @Mod.EventHandler
  public void preInit(FMLPreInitializationEvent event) {
    Path configDir = Loader.instance().getConfigDir().toPath();
    // Read from the loader rather than a constant: a hardcoded version silently reports the wrong
    // one the moment this build supports another Minecraft version.
    String mcVersion = Loader.instance().getMCVersionString().replace("Minecraft ", "").trim();

    dedicated = FMLCommonHandler.instance().getSide().isServer();
    client = dedicated ? null : new ForgeClientPlatform();
    clientHooks = dedicated ? null : new ForgeClientHooks();
    // Both sides have one. A dedicated server needs it to answer every server-side script action,
    // and a client needs it to query its integrated server; leaving it null when dedicated made the
    // server tick return early, so a server script never started and the process hung until killed.
    serverPlatform = new ForgeServerPlatform();
    network = new ForgeNetworkPlatform(dedicated);

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
            return Loader.isModLoaded(modId);
          }
        };

    TickClock.get().bind("forge");

    MinecheckBootstrap.init(services, engine -> this.engine = engine);
    // Registered whether or not a script is configured: the client tick hook is where per-frame
    // client work lives, and every handler below still gates its work on the engine.
    MinecraftForge.EVENT_BUS.register(this);
    if (!dedicated) {
      MinecraftForge.EVENT_BUS.register(clientHooks);
    }
  }

  /**
   * Client setup: the options list key bindings register into exists by init, and on 1.12.2 there
   * is no separate client-setup event from the mod's own point of view.
   */
  @Mod.EventHandler
  public void init(FMLInitializationEvent event) {
    if (!dedicated && client != null) {
      client.installCameraBindings();
      client.allowUnfocusedPlay();
    }
  }

  /**
   * Per-frame hook for the screenshot suite: a capture must read a frame drawn after it cleared the
   * chat, and a render tick is the only thing that says "a frame was drawn" (a client tick is not —
   * the client can tick several times between two frames).
   */
  @SubscribeEvent
  public void onRenderTick(TickEvent.RenderTickEvent event) {
    if (event.phase == TickEvent.Phase.START && client != null) {
      // In 1.12.2 the render delta is recomputed at the top of every frame, so pinning it here is
      // what makes a capture a function of the script alone.
      client.pinRenderTickDelta();
      return;
    }
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
    client.pumpViewWindow();
    if (client.isBootComplete()) {
      client.allowUnfocusedPlay();
      // Not inline: on 1.12.2 the client tick runs on the JVM's main thread, and LuaJ refuses to
      // yield a coroutine resumed there ("cannot yield main thread") — which every waitFor does.
      // Hand the tick to a worker like the server path does, one at a time so a slow script step
      // cannot overlap itself.
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

  /**
   * Installs the server-side channel tap on each player connection as it appears, so a script that
   * started watching a channel before the client joined still sees that client's payloads.
   */
  @SubscribeEvent
  public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
    if (engine == null || dedicated && serverPlatform == null) {
      return;
    }
    if (event.player instanceof EntityPlayerMP) {
      ForgeServerChannelTap.installFor((EntityPlayerMP) event.player);
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
    // 1.12.2's ServerTickEvent carries no server reference: ask the loader for the running one.
    MinecraftServer s = FMLCommonHandler.instance().getMinecraftServerInstance();
    if (s == null) {
      return;
    }
    serverPlatform.setServer(s);
    if (rulesApplied.compareAndSet(false, true)) {
      serverPlatform.applyDeterministicRules();
    }
    // A client runs an integrated server too, and its ServerTickEvent fires as well; ticking the
    // engine from both would resume one coroutine from two threads at once ("cannot resume
    // non-suspended coroutine"). The client tick is the single driver there, as on the other lines.
    if (!dedicated) {
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
}
