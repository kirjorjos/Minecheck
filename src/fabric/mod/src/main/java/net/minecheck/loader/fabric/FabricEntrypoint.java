package net.minecheck.loader.fabric;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecheck.common.bootstrap.MinecheckBootstrap;
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

    MinecheckBootstrap.init(
        services,
        engine -> {
          if (client != null) {
            ClientTickEvents.END_CLIENT_TICK.register(
                c -> {
                  client.pinClientClock();
                  if (!engine.isFinished() && client.isBootComplete()) {
                    engine.tick();
                  }
                });
          } else {
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
