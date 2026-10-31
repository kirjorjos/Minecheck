package net.minecheck.loader.forge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecheck.common.bootstrap.MinecheckBootstrap;
import net.minecheck.common.script.ScriptEngine;
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

  private final boolean dedicated;
  private final ForgeClientPlatform client;
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
    serverPlatform = dedicated ? new ForgeServerPlatform() : null;
    network = new ForgeNetworkPlatform(dedicated);

    final List<RecipeViewer> viewers = new ArrayList<>();
    if (!dedicated && ModList.get().isLoaded("emi")) {
      viewers.add(new ForgeEmiRecipeViewer());
    }
    if (!dedicated && ModList.get().isLoaded("jei")) {
      viewers.add(new ForgeJeiRecipeViewer());
    }
    // REI's mod id is `roughlyenoughitems`; the provider reports the short id `rei`.
    if (!dedicated && ModList.get().isLoaded("roughlyenoughitems")) {
      viewers.add(new ForgeReiRecipeViewer());
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

    MinecheckBootstrap.init(
        services,
        engine -> {
          this.engine = engine;
          MinecraftForge.EVENT_BUS.register(this);
        });
  }

  private final AtomicBoolean builtinWarmed = new AtomicBoolean();

  /**
   * Materialises {@link net.minecraft.core.RegistryAccess#BUILTIN} before any world or server join.
   *
   * <p>That value is a lazily built, memoised copy of the builtin (datapack) registries. On 1.19.2
   * the Forge client first touches it from {@code ClientPacketListener}'s field initialiser, which
   * runs <em>after</em> the login registry snapshot has been applied: built from that post-sync
   * state, encoding fails to resolve even {@code minecraft:air} ("Unknown registry element in
   * minecraft:block: Block{minecraft:air}") and the join never completes — the server logs the
   * player in, then the client drops the connection about a second later, with no world and no
   * client-side error. Building it at boot, while the registries are still pristine, is the state
   * the value is meant to hold, so the login path finds it already there.
   */
  private void warmBuiltinRegistryAccess() {
    long start = System.nanoTime();
    try {
      net.minecraft.core.RegistryAccess.BUILTIN.get();
      java.util.logging.Logger.getLogger("Minecheck")
          .info(
              String.format(
                  "built the builtin registry copy in %d ms, before any world join",
                  (System.nanoTime() - start) / 1_000_000));
    } catch (RuntimeException e) {
      java.util.logging.Logger.getLogger("Minecheck")
          .warning(String.format("could not build the builtin registry copy: %s", e));
    }
  }

  /**
   * Per-frame hook for the screenshot suite: a capture must read a frame drawn after it cleared the
   * chat, and a render tick is the only thing that says "a frame was drawn" (a client tick is not —
   * the client can tick several times between two frames).
   */
  @SubscribeEvent
  public void onRenderTick(TickEvent.RenderTickEvent event) {
    if (event.phase == TickEvent.Phase.END && client != null) {
      ForgeClientPlatform.notifyFrameDrawn();
    }
  }

  @SubscribeEvent
  public void onClientTick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.END || client == null) {
      return;
    }
    if (client.isBootComplete() && builtinWarmed.compareAndSet(false, true)) {
      warmBuiltinRegistryAccess();
    }
    client.pinClientClock();
    client.pinRenderTickDelta();
    ScriptEngine e = engine;
    if (e != null && !e.isFinished() && client.isBootComplete()) {
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
    if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
      ForgeServerChannelTap.installFor(player);
    }
  }

  @SubscribeEvent
  public void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase != TickEvent.Phase.END || serverPlatform == null) {
      return;
    }
    MinecraftServer s = event.getServer();
    serverPlatform.setServer(s);
    if (rulesApplied.compareAndSet(false, true)) {
      serverPlatform.applyDeterministicRules();
    }
    ScriptEngine e = engine;
    if (e != null && !e.isFinished() && serverTickRunning.compareAndSet(false, true)) {
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
