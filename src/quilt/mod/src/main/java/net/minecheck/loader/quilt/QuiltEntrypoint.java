package net.minecheck.loader.quilt;

import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecheck.common.bootstrap.MinecheckBootstrap;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.yarn.YarnClientPlatform;
import net.minecheck.platform.yarn.YarnServerPlatform;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;

public class QuiltEntrypoint implements ModInitializer {

  @Override
  public void onInitialize(ModContainer mod) {
    Path configDir = QuiltLoader.getConfigDir();
    String mcVersion =
        QuiltLoader.getModContainer("minecraft")
            .orElseThrow(() -> new RuntimeException("Failed to get MC version"))
            .metadata()
            .version()
            .raw();

    boolean server = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
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
