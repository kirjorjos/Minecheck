package net.minecheck.platform.yarn;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;

public class YarnClientPlatform implements ClientPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private MinecraftClient mc() {
    return MinecraftClient.getInstance();
  }

  private void onClientThread(Runnable r) {
    MinecraftClient mc = mc();
    if (mc.isOnThread()) {
      r.run();
    } else {
      mc.execute(r);
    }
  }

  @Override
  public void joinLocalWorld(String worldName) {
    MinecraftClient mc = mc();
    LevelStorage storage = mc.getLevelStorage();
    boolean found = false;
    try {
      for (LevelStorage.LevelSave save : storage.getLevelList()) {
        if (save.getRootPath().equals(worldName)) {
          found = true;
          break;
        }
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to read level list: " + e.getMessage(), e);
    }
    if (!found) {
      throw new IllegalArgumentException("World not found: " + worldName);
    }
    IntegratedServerLoader loader = mc.createIntegratedServerLoader();
    onClientThread(() -> loader.start(mc.currentScreen, worldName));
  }

  @Override
  public void createWorld(CreateWorldOptions options) {
    GameRules rules = new GameRules();
    if (!options.mobSpawning()) {
      rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
    }
    LevelInfo levelInfo =
        new LevelInfo(
            options.name(),
            gameMode(options.gamemode()),
            false, // hardcore
            difficulty(options.difficulty()),
            options.allowCheats(),
            rules,
            DataPackSettings.SAFE_MODE);
    MinecraftClient mc = mc();
    onClientThread(
        () -> {
          DynamicRegistryManager.Immutable registryManager =
              DynamicRegistryManager.createAndLoad().toImmutable();
          WorldPreset flat =
              registryManager.get(Registry.WORLD_PRESET_KEY).getOrThrow(WorldPresets.FLAT);
          GeneratorOptions generatorOptions =
              flat.createGeneratorOptions(options.seed(), options.structures(), false);
          mc.createIntegratedServerLoader()
              .createAndStart(options.name(), levelInfo, registryManager, generatorOptions);
        });
  }

  @Override
  public void joinServer(String address) {
    ServerAddress serverAddress = ServerAddress.parse(address);
    ServerInfo info = new ServerInfo(address, address, false);
    onClientThread(
        () -> {
          try {
            ConnectScreen.connect(mc().currentScreen, mc(), serverAddress, info);
            LOG.info(
                "joinServer: ConnectScreen.connect("
                    + serverAddress.getAddress()
                    + ":"
                    + serverAddress.getPort()
                    + ")");
          } catch (Throwable t) {
            LOG.log(Level.SEVERE, "joinServer: failed to connect", t);
          }
        });
  }

  @Override
  public void leaveWorld() {
    MinecraftClient mc = mc();
    onClientThread(
        () -> {
          if (mc.world != null) {
            mc.world.disconnect();
          }
          mc.disconnect();
        });
  }

  @Override
  public void quit() {
    onClientThread(mc()::stop);
  }

  @Override
  public boolean isInWorld() {
    return mc().player != null;
  }

  @Override
  public boolean isOnTitleScreen() {
    return mc().currentScreen instanceof TitleScreen;
  }

  @Override
  public boolean isConnecting() {
    return mc().currentScreen instanceof ConnectScreen;
  }

  @Override
  public boolean isBootComplete() {
    return mc().getOverlay() == null;
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer server = mc().getServer();
    if (server == null) {
      LOG.warning("worldGenSettled: not in a local/integrated world; treating as settled");
      return true;
    }
    ServerWorld overworld = server.getWorld(World.OVERWORLD);
    if (overworld == null) {
      LOG.warning("worldGenSettled: no overworld loaded; treating as settled");
      return true;
    }
    int pending = overworld.getChunkManager().getPendingTasks();
    LOG.info("worldGenSettled: " + pending + " pending chunk task(s)");
    return pending == 0;
  }

  private static GameMode gameMode(String name) {
    return switch (name.toLowerCase()) {
      case "survival" -> GameMode.SURVIVAL;
      case "adventure" -> GameMode.ADVENTURE;
      case "spectator" -> GameMode.SPECTATOR;
      default -> GameMode.CREATIVE;
    };
  }

  private static Difficulty difficulty(String name) {
    return switch (name.toLowerCase()) {
      case "peaceful" -> Difficulty.PEACEFUL;
      case "easy" -> Difficulty.EASY;
      case "hard" -> Difficulty.HARD;
      default -> Difficulty.NORMAL;
    };
  }
}
