package net.minecheck.loader.forge;

import net.minecheck.platform.api.ServerPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public class ForgeServerPlatform implements ServerPlatform {

  private volatile MinecraftServer server;

  public void setServer(MinecraftServer server) {
    this.server = server;
  }

  private MinecraftServer requireServer() {
    MinecraftServer s = server;
    if (s == null) {
      throw new IllegalStateException("MinecraftServer not available yet");
    }
    return s;
  }

  @Override
  public int runCommand(String command) {
    MinecraftServer s = requireServer();
    return s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command);
  }

  @Override
  public boolean isPlayerOnline(String name) {
    MinecraftServer s = server;
    return s != null && s.getPlayerList().getPlayerByName(name) != null;
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    ServerLevel overworld = s.overworld();
    if (overworld == null) {
      return false;
    }
    return overworld.getChunkSource().getPendingTasksCount() == 0;
  }
}
