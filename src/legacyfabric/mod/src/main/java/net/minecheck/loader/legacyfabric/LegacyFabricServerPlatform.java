package net.minecheck.loader.legacyfabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.minecheck.platform.api.EntityInfo;
import net.minecheck.platform.api.ServerPlatform;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * The server-side half of the Legacy Fabric platform.
 *
 * <p>Every action is executed on the server thread: the script engine is driven from the server
 * tick, but a client's integrated server is queried from the client thread, so the marshaling here
 * is what makes one implementation serve both.
 */
public class LegacyFabricServerPlatform implements ServerPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

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

  private <T> T onServerThread(Supplier<T> action) {
    MinecraftServer s = requireServer();
    if (s.isOnThread()) {
      return action.get();
    }
    java.util.concurrent.CompletableFuture<T> done = new java.util.concurrent.CompletableFuture<>();
    s.submit(
        () -> {
          try {
            done.complete(action.get());
          } catch (Throwable t) {
            done.completeExceptionally(t);
          }
        });
    return done.join();
  }

  private ServerWorld overworld() {
    ServerWorld level = requireServer().getWorld(0);
    if (level == null) {
      throw new IllegalStateException("overworld not available yet");
    }
    return level;
  }

  /**
   * A registry id as this line reads it: trimmed and lower-cased, so {@code minecraft:Gold_Block}
   * is a name error and not a crash in the parser.
   */
  private static Identifier parseId(String id, String what) {
    if (id == null) {
      throw new IllegalArgumentException("invalid " + what + " id: null");
    }
    try {
      return new Identifier(id.trim().toLowerCase(java.util.Locale.ROOT));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid " + what + " id: " + id, e);
    }
  }

  private static NbtCompound parseTag(String nbt) {
    if (nbt == null || nbt.isEmpty()) {
      return new NbtCompound();
    }
    try {
      return StringNbtReader.parse(nbt);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid NBT: " + e.getMessage(), e);
    }
  }

  @Override
  public int runCommand(String command) {
    return onServerThread(
        () -> {
          MinecraftServer s = requireServer();
          String line = command.startsWith("/") ? command.substring(1) : command;
          return s.getCommandManager().execute(s, line);
        });
  }

  @Override
  public boolean isPlayerOnline(String name) {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(() -> s.getPlayerManager().getPlayer(name) != null);
  }

  @Override
  public void applyDeterministicRules() {
    onServerThread(
        () -> {
          ServerWorld level = overworld();
          // 1.12.2 has no insomnia rule; it arrives in 1.15, and mob spawning is off anyway.
          level.getGameRules().setGameRule("doMobSpawning", "false");
          level.getGameRules().setGameRule("doDaylightCycle", "false");
          level.getGameRules().setGameRule("doWeatherCycle", "false");
          return null;
        });
  }

  @Override
  public List<EntityInfo> findEntities(String type, String tag) {
    return onServerThread(() -> findEntitiesOnServerThread(type, tag));
  }

  private List<EntityInfo> findEntitiesOnServerThread(String type, String tag) {
    Identifier typeId = type == null ? null : parseId(type, "entity type");
    ServerWorld level = overworld();
    List<EntityInfo> out = new ArrayList<>();
    // Players keep their own list on 1.12.2: the first thing a script asks for is the player that
    // just joined, which is not in the loaded-entity list until the world ticks it.
    Map<Integer, Entity> entities = new LinkedHashMap<>();
    for (Entity entity : level.loadedEntities) {
      entities.put(entity.getEntityId(), entity);
    }
    for (Entity entity : level.playerEntities) {
      entities.put(entity.getEntityId(), entity);
    }
    for (Entity entity : entities.values()) {
      // A removed entity stays listed until the next tick; it is not something a client observes.
      if (entity.removed) {
        continue;
      }
      Identifier actual = EntityType.getId(entity);
      if (actual == null && entity instanceof net.minecraft.entity.player.PlayerEntity) {
        // 1.12.2 has no registry entry for the player class, so the entity that joined first is the
        // one a script cannot otherwise name.
        actual = new Identifier("minecraft", "player");
      }
      if (typeId != null && !typeId.equals(actual)) {
        continue;
      }
      if (tag != null && !entity.getScoreboardTags().contains(tag)) {
        continue;
      }
      out.add(
          new EntityInfo(
              entity.getEntityId(),
              actual == null ? "" : actual.toString(),
              entity.x,
              entity.y,
              entity.z,
              new java.util.HashSet<>(entity.getScoreboardTags())));
    }
    return List.copyOf(out);
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(
        () -> {
          ServerWorld level = s.getWorld(0);
          if (level == null) {
            return false;
          }
          // The chunk the level spawned in, not chunk 0,0: a 1.12.2 world spawns its player away
          // from the origin, so 0,0 is generated but unloaded until something forces it.
          BlockPos spawn = level.getSpawnPos();
          return level.getChunkProvider().isChunkGenerated(spawn.getX() >> 4, spawn.getZ() >> 4);
        });
  }

  @Override
  public Integer groundHeight(int x, int z) {
    return onServerThread(
        () -> {
          ServerWorld level = overworld();
          if (!level.getChunkProvider().isChunkGenerated(x >> 4, z >> 4)) {
            return null;
          }
          // 1.12.2 answers with the top position of the column; the height a player stands at is
          // one above the surface block.
          return level.getTopPosition(new BlockPos(x, 0, z)).getY() + 1;
        });
  }

  @Override
  public boolean isChunkLoaded(int chunkX, int chunkZ) {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(() -> overworld().getChunkProvider().isChunkGenerated(chunkX, chunkZ));
  }

  @Override
  public boolean loadChunk(int chunkX, int chunkZ) {
    return onServerThread(
        () -> {
          ServerWorld level = overworld();
          // Reading the chunk through makes the load synchronous rather than a later tick. This
          // line has no forced-chunk ticket exposed in its API, so the chunk is loaded but not held
          // against unload.
          level.getChunkProvider().getOrGenerateChunks(chunkX, chunkZ);
          return level.getChunkProvider().isChunkGenerated(chunkX, chunkZ);
        });
  }

  @Override
  public int spawnEntity(String id, double x, double y, double z, String nbt) {
    return onServerThread(() -> spawnEntityOnServerThread(id, x, y, z, nbt));
  }

  private int spawnEntityOnServerThread(String id, double x, double y, double z, String nbt) {
    Identifier location = parseId(id, "entity type");
    ServerWorld level = overworld();
    NbtCompound tag = parseTag(nbt);
    tag.putString("id", location.toString());
    Entity entity = EntityType.createInstanceFromNbt(tag, level);
    if (entity == null) {
      throw new IllegalArgumentException("unknown entity type: " + id);
    }
    entity.updatePosition(x, y, z);
    if (!level.spawnEntity(entity)) {
      throw new IllegalStateException("failed to spawn entity " + id);
    }
    return entity.getEntityId();
  }

  @Override
  public boolean spawnBlock(String id, int x, int y, int z, String nbt, boolean replace) {
    return onServerThread(() -> spawnBlockOnServerThread(id, x, y, z, nbt, replace));
  }

  private boolean spawnBlockOnServerThread(
      String id, int x, int y, int z, String nbt, boolean replace) {
    Identifier location = parseId(id, "block");
    Block block = Block.get(location.toString());
    if (block == null) {
      throw new IllegalArgumentException("unknown block: " + id);
    }
    ServerWorld level = overworld();
    BlockPos pos = new BlockPos(x, y, z);
    if (!replace && !level.isAir(pos)) {
      return false;
    }
    BlockState state = block.getDefaultState();
    if (!level.setBlockState(pos, state, 3)) {
      return false;
    }
    NbtCompound tag = parseTag(nbt);
    if (!tag.getKeys().isEmpty()) {
      BlockEntity blockEntity = level.getBlockEntity(pos);
      if (blockEntity != null) {
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        blockEntity.fromNbt(tag);
      }
    }
    return true;
  }

  @Override
  public int spawnItemDrop(String id, int count, double x, double y, double z, String nbt) {
    return onServerThread(() -> spawnItemDropOnServerThread(id, count, x, y, z, nbt));
  }

  private int spawnItemDropOnServerThread(
      String id, int count, double x, double y, double z, String nbt) {
    Identifier location = parseId(id, "item");
    Item item = Item.REGISTRY.get(location);
    if (item == null) {
      throw new IllegalArgumentException("unknown item: " + id);
    }
    ServerWorld level = overworld();
    ItemStack stack = new ItemStack(item, count);
    NbtCompound tag = parseTag(nbt);
    if (!tag.getKeys().isEmpty()) {
      stack.setNbt(tag);
    }
    ItemEntity itemEntity = new ItemEntity(level, x, y, z, stack);
    if (!level.spawnEntity(itemEntity)) {
      throw new IllegalStateException("failed to spawn item drop " + id);
    }
    return itemEntity.getEntityId();
  }

  @Override
  public boolean spawnStructure(String id, int x, int y, int z) {
    // This line's template API is not wired yet: 1.12.2 keeps structure templates behind a manager
    // this platform has not been taught to reach, and a fixture that places one must otherwise read
    // as "placed" while nothing happened.
    throw new IllegalStateException(
        "spawnStructure is not implemented on the Legacy Fabric platform yet: " + id);
  }
}
