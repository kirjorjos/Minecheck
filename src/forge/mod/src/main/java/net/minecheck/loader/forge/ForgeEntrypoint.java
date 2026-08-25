package net.minecheck.loader.forge;

import java.nio.file.Path;
import net.minecheck.common.bootstrap.MinecheckBootstrap;
import net.minecheck.common.script.ScriptEngine;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.PlatformServices;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod("minecheck")
public class ForgeEntrypoint {

  private final boolean dedicated;
  private final ForgeClientPlatform client;
  private final ForgeServerPlatform serverPlatform;
  private volatile ScriptEngine engine;

  public ForgeEntrypoint() {
    Path configDir = FMLPaths.CONFIGDIR.get();

    dedicated = FMLEnvironment.dist.isDedicatedServer();
    client = dedicated ? null : new ForgeClientPlatform();
    serverPlatform = dedicated ? new ForgeServerPlatform() : null;

    PlatformServices services =
        new PlatformServices() {
          @Override
          public String minecraftVersion() {
            return "1.19.2";
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
        };

    MinecheckBootstrap.init(
        services,
        engine -> {
          this.engine = engine;
          MinecraftForge.EVENT_BUS.register(this);
        });
  }

  @SubscribeEvent
  public void onClientTick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.END || client == null) {
      return;
    }
    ScriptEngine e = engine;
    if (e != null && !e.isFinished() && client.isBootComplete()) {
      e.tick();
    }
  }

  @SubscribeEvent
  public void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase != TickEvent.Phase.END || serverPlatform == null) {
      return;
    }
    MinecraftServer s = event.getServer();
    serverPlatform.setServer(s);
    ScriptEngine e = engine;
    if (e != null && !e.isFinished()) {
      e.tick();
    }
  }
}
