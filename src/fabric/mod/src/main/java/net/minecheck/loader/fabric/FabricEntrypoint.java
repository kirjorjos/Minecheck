package net.minecheck.loader.fabric;

import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecheck.common.bootstrap.MinecheckBootstrap;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.yarn.YarnClientPlatform;
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
          public net.minecheck.platform.api.ServerPlatform server() {
            return serverPlatform;
          }

          @Override
          public boolean dedicatedServer() {
            return server;
          }
        };

    MinecheckBootstrap.init(
        services,
        engine -> {
          if (client != null) {
            ClientTickEvents.END_CLIENT_TICK.register(
                c -> {
                  if (!engine.isFinished() && client.isBootComplete()) {
                    engine.tick();
                  }
                });
          } else {
            ServerTickEvents.END_SERVER_TICK.register(
                s -> {
                  serverPlatform.setServer(s);
                  if (!engine.isFinished()) {
                    engine.tick();
                  }
                });
          }
        });
  }
}
