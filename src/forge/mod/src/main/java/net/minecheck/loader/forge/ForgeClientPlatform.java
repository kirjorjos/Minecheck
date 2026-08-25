package net.minecheck.loader.forge;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

public class ForgeClientPlatform implements ClientPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private Minecraft mc() {
    return Minecraft.getInstance();
  }

  private void onClientThread(Runnable r) {
    Minecraft mc = mc();
    if (mc.isSameThread()) {
      r.run();
    } else {
      mc.execute(r);
    }
  }

  @Override
  public void joinLocalWorld(String worldName) {
    Minecraft mc = mc();
    if (!mc.getLevelSource().levelExists(worldName)) {
      throw new IllegalArgumentException("World not found: " + worldName);
    }
    onClientThread(() -> mc.createWorldOpenFlows().loadLevel(mc.screen, worldName));
  }

  @Override
  public void createWorld(CreateWorldOptions options) {
    GameRules rules = new GameRules();
    if (!options.mobSpawning()) {
      rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
    }
    LevelSettings levelSettings =
        new LevelSettings(
            options.name(),
            gameMode(options.gamemode()),
            false, // hardcore
            difficulty(options.difficulty()),
            options.allowCheats(),
            rules,
            DataPackConfig.DEFAULT);
    Minecraft mc = mc();
    onClientThread(
        () -> {
          RegistryAccess.Frozen registryAccess = RegistryAccess.BUILTIN.get();
          WorldPreset flat =
              registryAccess
                  .registryOrThrow(Registry.WORLD_PRESET_REGISTRY)
                  .getOrThrow(WorldPresets.FLAT);
          WorldGenSettings generatorOptions =
              flat.createWorldGenSettings(options.seed(), options.structures(), false);
          mc.createWorldOpenFlows()
              .createFreshLevel(options.name(), levelSettings, registryAccess, generatorOptions);
        });
  }

  @Override
  public void joinServer(String address) {
    ServerAddress serverAddress = ServerAddress.parseString(address);
    ServerData info = new ServerData(address, address, false);
    onClientThread(
        () -> {
          try {
            Minecraft mc = mc();
            ConnectScreen.startConnecting(mc.screen, mc, serverAddress, info);
            LOG.info(
                "joinServer: ConnectScreen.startConnecting("
                    + serverAddress.getHost()
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
    onClientThread(
        () -> {
          Minecraft mc = mc();
          if (mc.level != null) {
            mc.level.disconnect();
          }
          mc.clearLevel();
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
    return mc().screen instanceof TitleScreen;
  }

  @Override
  public boolean isConnecting() {
    return mc().screen instanceof ConnectScreen;
  }

  @Override
  public boolean isBootComplete() {
    return mc().getOverlay() == null;
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer server = mc().getSingleplayerServer();
    if (server == null) {
      LOG.warning("worldGenSettled: not in a local/integrated world; treating as settled");
      return true;
    }
    ServerLevel overworld = server.overworld();
    if (overworld == null) {
      LOG.warning("worldGenSettled: no overworld loaded; treating as settled");
      return true;
    }
    int pending = overworld.getChunkSource().getPendingTasksCount();
    LOG.info("worldGenSettled: " + pending + " pending chunk task(s)");
    return pending == 0;
  }

  private static GameType gameMode(String name) {
    return switch (name.toLowerCase()) {
      case "survival" -> GameType.SURVIVAL;
      case "adventure" -> GameType.ADVENTURE;
      case "spectator" -> GameType.SPECTATOR;
      default -> GameType.CREATIVE;
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
