package net.minecheck.loader.fabric;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import net.minecheck.platform.yarn.EmiRecipeViewer;
import net.minecheck.platform.yarn.JeiRecipeViewer;
import net.minecheck.platform.yarn.ReiRecipeViewer;
import net.minecheck.platform.yarn.YarnClientNetworkPlatform;
import net.minecheck.platform.yarn.YarnClientPlatform;
import net.minecheck.platform.yarn.YarnServerNetworkPlatform;
import net.minecheck.platform.yarn.YarnServerPlatform;

public class FabricEntrypoint implements ModInitializer {

  @Override
  public void onInitialize() {
    FabricLoader loader = FabricLoader.getInstance();
    Path configDir = loader.getConfigDir();
    String mcVersion =
        loader
            .getModContainer("minecraft")
            .orElseThrow(() -> new RuntimeException("Failed to get MC version"))
            .getMetadata()
            .getVersion()
            .getFriendlyString();

    boolean server = loader.getEnvironmentType() == EnvType.SERVER;
    ClientPlatform client = server ? null : new YarnClientPlatform();
    YarnServerPlatform serverPlatform = server ? new YarnServerPlatform() : null;
    final YarnServerNetworkPlatform serverNetwork = server ? new YarnServerNetworkPlatform() : null;
    final NetworkPlatform network = server ? serverNetwork : new YarnClientNetworkPlatform();

    final List<RecipeViewer> viewers = new ArrayList<>();
    if (!server && loader.isModLoaded("emi")) {
      viewers.add(new EmiRecipeViewer());
    }
    if (!server && loader.isModLoaded("jei")) {
      viewers.add(new JeiRecipeViewer());
    }
    // REI's mod id is `roughlyenoughitems`; the provider reports the short id `rei`.
    if (!server && loader.isModLoaded("roughlyenoughitems")) {
      viewers.add(new ReiRecipeViewer());
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
          public ClientPlatform client() {
            return client;
          }

          @Override
          public ServerPlatform server() {
            return serverPlatform;
          }

          @Override
          public boolean dedicatedServer() {
            return server;
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
            return loader.isModLoaded(modId);
          }
        };

    // Client hooks are registered before the engine exists, so they do not depend on a script being
    // configured: a client with none used to have no tick hook at all, which is exactly the state a
    // camera without a script has to work in. The work below stays gated on the engine, so a
    // passive
    // client behaves as it did; camera work belongs at the top of the hook, above the gate.
    final AtomicReference<ScriptEngine> engineRef = new AtomicReference<>();
    if (client != null) {
      // Per-frame hook for the screenshot suite: captures need to know that a frame was drawn
      // (see YarnClientPlatform#captureScreenshot).
      net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
          (matrices, tickDelta) -> YarnClientPlatform.notifyFrameDrawn());
      client.camera().registerBindings();
      // The camera ticks at the *start* of the tick, before the player does: it holds the body's
      // rotation at the value the server last saw, and doing that after the player ticked would be
      // after the packet that carries the change.
      ClientTickEvents.START_CLIENT_TICK.register(c -> client.camera().tick());
      ClientTickEvents.END_CLIENT_TICK.register(
          c -> {
            ScriptEngine e = engineRef.get();
            if (e == null || e.isFinished()) {
              return;
            }
            client.pinClientClock();
            client.pinRenderTickDelta();
            client.pumpViewWindow();
            if (client.isBootComplete()) {
              e.tick();
            }
          });
    }

    // The tick gate: the server's loop is ours to hold (on a client, the integrated one), so a
    // script
    // can stop the world between frames and advance it by a tick count rather than by however many
    // ticks a runner managed to render. Registered before any script exists — the clock belongs to
    // the server, not to a run — and in both environments, so a dedicated server's own scripts can
    // drive it too. The actions stay behind `ownTickClock` in the config.
    TickClock.get().bind("fabric");
    ServerTickEvents.START_SERVER_TICK.register(ticking -> TickClock.get().awaitTick());
    ServerTickEvents.END_SERVER_TICK.register(ticking -> TickClock.get().countTick());

    MinecheckBootstrap.init(
        services,
        engine -> {
          engineRef.set(engine);
          if (client == null) {
            AtomicBoolean serverTickRunning = new AtomicBoolean();
            AtomicBoolean rulesApplied = new AtomicBoolean();
            ServerTickEvents.END_SERVER_TICK.register(
                s -> {
                  serverPlatform.setServer(s);
                  serverNetwork.setServer(s);
                  if (rulesApplied.compareAndSet(false, true)) {
                    serverPlatform.applyDeterministicRules();
                  }
                  if (!engine.isFinished() && serverTickRunning.compareAndSet(false, true)) {
                    CompletableFuture.runAsync(
                        () -> {
                          try {
                            engine.tick();
                          } finally {
                            serverTickRunning.set(false);
                          }
                        });
                  }
                });
          }
        });
  }
}
