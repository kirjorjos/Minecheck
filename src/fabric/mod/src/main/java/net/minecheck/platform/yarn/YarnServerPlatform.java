package net.minecheck.platform.yarn;

import net.minecheck.platform.api.ServerPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

public class YarnServerPlatform implements ServerPlatform {

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
    return s.getCommandManager().executeWithPrefix(s.getCommandSource(), command);
  }

  @Override
  public boolean isPlayerOnline(String name) {
    MinecraftServer s = server;
    return s != null && s.getPlayerManager().getPlayer(name) != null;
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    ServerWorld overworld = s.getWorld(net.minecraft.world.World.OVERWORLD);
    if (overworld == null) {
      return false;
    }
    return overworld.getChunkManager().getPendingTasks() == 0;
  }
}
