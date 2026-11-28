package net.minecheck.loader.legacyfabric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.minecheck.platform.api.EntityInfo;
import net.minecheck.platform.api.ServerPlatform;
import net.minecraft.block.Block;
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
import net.minecraft.util.math.BlockPos;

/**
 * The server-side half of the Legacy Fabric platform.
 *
 * <p>Every action is executed on the server thread: the script engine is driven from the server
 * tick, but a client's integrated server is queried from the client thread, so the marshaling here
 * is what makes one implementation serve both.
 *
 * <p>This line has no {@code submit} and no {@code isOnThread}, so the marshaling is its own: calls
 * made off the server thread are queued and run from {@link #setServer}, which the tick hook calls
 * on the server thread once per tick. That is one tick of latency for a cross-thread call, which is
 * what a caller waiting on the answer pays; it is bounded by {@link #WAIT_SECONDS} so a stopped
 * server reports an error instead of hanging the caller for ever.
 */
public class LegacyFabricServerPlatform implements ServerPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** How long a caller waits for its queued server-thread action to run. */
  private static final long WAIT_SECONDS = 10;

  private volatile MinecraftServer server;

  /** The thread the server ticks on, learned from the first tick the hook reports. */
  private volatile Thread serverThread;

  /** Calls made off the server thread, drained at the end of the next server tick. */
  private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

  public void setServer(MinecraftServer server) {
    this.server = server;
    if (server == null) {
      return;
    }
    serverThread = Thread.currentThread();
    Runnable task;
    while ((task = pending.poll()) != null) {
      try {
        task.run();
      } catch (RuntimeException e) {
        // The caller has already been failed through its future; one bad action must not stop the
        // rest of the queue from running.
        LOG.warning("Minecheck: queued server action failed: " + e);
      }
    }
  }

  private MinecraftServer requireServer() {
    MinecraftServer s = server;
    if (s == null) {
      throw new IllegalStateException("MinecraftServer not available yet");
    }
    return s;
  }

  private <T> T onServerThread(Supplier<T> action) {
    requireServer();
    Thread thread = serverThread;
    // Before the first tick there is no server thread to hand work to, and nothing else is running
    // on the world either, so the call runs where it is.
    if (thread == null || thread == Thread.currentThread()) {
      return action.get();
    }
    CompletableFuture<T> done = new CompletableFuture<>();
    pending.add(
        () -> {
          try {
            done.complete(action.get());
          } catch (Throwable t) {
            done.completeExceptionally(t);
          }
        });
    try {
      return done.get(WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new IllegalStateException("the server did not reach the action in time", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the server", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new IllegalStateException(cause);
    }
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
   *
   * <p>A plain string, not an identifier: on this line {@code Identifier} is a client class and the
   * dedicated server cannot load it at all.
   */
  private static String parseId(String id, String what) {
    if (id == null) {
      throw new IllegalArgumentException("invalid " + what + " id: null");
    }
    String trimmed = id.trim().toLowerCase(java.util.Locale.ROOT);
    if (!trimmed.matches("([a-z0-9_.\\-]+:)?[a-z0-9_./\\-]+")) {
      throw new IllegalArgumentException("invalid " + what + " id: " + id);
    }
    return trimmed;
  }

  private static NbtCompound parseTag(String nbt) {
    if (nbt == null || nbt.isEmpty()) {
      return new NbtCompound();
    }
    try {
      // This line's reader keeps its entry point under an unmapped name; it answers the same
      // element the newer lines' `parse` does.
      return (NbtCompound) StringNbtReader.parse(nbt);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid NBT: " + e.getMessage(), e);
    }
  }

  @Override
  public int runCommand(String command) {
    return onServerThread(
        () -> {
          MinecraftServer s = requireServer();
          String line =
              LegacyCommandShim.translate(command.startsWith("/") ? command.substring(1) : command);
          ServerWorld world = s.getWorld(0);
          Integer rule = LegacyCommandShim.gameruleValue(world, line);
          if (rule != null) {
            return rule;
          }
          Integer entity = LegacyCommandShim.executeIfEntity(world, line);
          if (entity != null) {
            return entity;
          }
          Integer data = LegacyCommandShim.dataGetEntity(world, line);
          if (data != null) {
            return data;
          }
          Integer tagged = LegacyCommandShim.tagCommand(world, line);
          if (tagged != null) {
            return tagged;
          }
          Integer conditional = LegacyCommandShim.executeIfBlock(world, line);
          if (conditional != null) {
            return conditional;
          }
          // Every 1.9.4 command root returns void, so the handler answers 0 for a command that
          // succeeded; the earlier lines answered 1, and a fixture reads that count back. An
          // executed command therefore reports at least one.
          int result = s.getCommandManager().execute(s, line);
          return Math.max(1, result);
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
          // doWeatherCycle arrives in 1.11 and doInsomnia in 1.15; this line knows neither rule and
          // its manager rejects an unknown name, so each is set only when it exists. Weather is
          // pinned by the run's own clock instead.
          setRule(level, "doMobSpawning");
          setRule(level, "doDaylightCycle");
          setRule(level, "doWeatherCycle");
          return null;
        });
  }

  private static void setRule(ServerWorld level, String rule) {
    if (level.getGameRules().contains(rule)) {
      level.getGameRules().setGameRule(rule, "false");
    }
  }

  @Override
  public List<EntityInfo> findEntities(String type, String tag) {
    return onServerThread(() -> findEntitiesOnServerThread(type, tag));
  }

  private List<EntityInfo> findEntitiesOnServerThread(String type, String tag) {
    String typeId = type == null ? null : parseId(type, "entity type");
    ServerWorld level = overworld();
    List<EntityInfo> out = new ArrayList<>();
    // Players keep their own list on this line: the first thing a script asks for is the player
    // that
    // just joined, which is not in the loaded-entity list until the world ticks it.
    Map<Integer, Entity> entities = new LinkedHashMap<>();
    for (Object o : level.loadedEntities) {
      Entity entity = (Entity) o;
      entities.put(entity.getEntityId(), entity);
    }
    for (Object o : level.playerEntities) {
      Entity entity = (Entity) o;
      entities.put(entity.getEntityId(), entity);
    }
    for (Entity entity : entities.values()) {
      // A removed entity stays listed until the next tick; it is not something a client observes.
      if (entity.removed) {
        continue;
      }
      String actual = entityId(entity);
      if (typeId != null && !typeId.equals(actual)) {
        continue;
      }
      java.util.Set<String> tags = LegacyCommandShim.tagsOf(entity);
      if (tag != null && !tags.contains(tag)) {
        continue;
      }
      out.add(
          new EntityInfo(
              entity.getEntityId(),
              actual.toString(),
              entity.x,
              LegacyEntitySpace.feetY(entity),
              entity.z,
              tags));
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * The 1.13-style id of an entity.
   *
   * <p>This line names its entity types in CamelCase and without a namespace ({@code Zombie}), and
   * it has no id for the player class, so both are translated here rather than leaking this line's
   * spelling into a script.
   */
  private static String entityId(Entity entity) {
    String legacy = EntityType.getEntityName(entity);
    if (legacy == null) {
      // The player is the one entity this line registers no type for; the newer lines answer
      // `minecraft:player` for it.
      return "minecraft:player";
    }
    return LegacyFabricNbt.modernEntityId(legacy);
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
          // The chunk the level spawned in, not chunk 0,0: this line's world spawns its player away
          // from the origin, so 0,0 is generated but unloaded until something forces it.
          BlockPos spawn = level.getSpawnPos();
          return level.getChunkProvider().getLoadedChunk(spawn.getX() >> 4, spawn.getZ() >> 4)
              != null;
        });
  }

  @Override
  public Integer groundHeight(int x, int z) {
    return onServerThread(
        () -> {
          ServerWorld level = overworld();
          if (level.getChunkProvider().getLoadedChunk(x >> 4, z >> 4) == null) {
            return null;
          }
          // This line has no column-top query, so the column is scanned from the build limit down
          // and the first block that is not air is the surface. The answer is one above it — the
          // height a player standing there stands at — which is what the newer lines' top position
          // is.
          for (int y = level.getEffectiveHeight() - 1; y >= 0; y--) {
            if (!level.isAir(new net.minecraft.util.math.BlockPos(x, y, z))) {
              return y + 1;
            }
          }
          return 0;
        });
  }

  /** The chunk a world coordinate falls in, for a load-before-use call. */
  private static int floorChunk(double coordinate) {
    return (int) Math.floor(coordinate) >> 4;
  }

  @Override
  public boolean isChunkLoaded(int chunkX, int chunkZ) {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(
        () -> overworld().getChunkProvider().getLoadedChunk(chunkX, chunkZ) != null);
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
          return level.getChunkProvider().getLoadedChunk(chunkX, chunkZ) != null;
        });
  }

  @Override
  public Long worldTime() {
    MinecraftServer s = server;
    if (s == null) {
      return null;
    }
    return onServerThread(() -> overworld().getTimeOfDay());
  }

  @Override
  public boolean setWorldTime(long ticks) {
    return runCommand("time set " + ticks) > 0;
  }

  @Override
  public String weather() {
    MinecraftServer s = server;
    if (s == null) {
      return null;
    }
    return onServerThread(
        () -> {
          ServerWorld level = overworld();
          if (level.isThundering()) {
            return "thunder";
          }
          return level.isRaining() ? "rain" : "clear";
        });
  }

  @Override
  public boolean setWeather(String weather) {
    return runCommand("weather " + weather) > 0;
  }

  @Override
  public int spawnEntity(String id, double x, double y, double z, String nbt) {
    return onServerThread(() -> spawnEntityOnServerThread(id, x, y, z, nbt));
  }

  private int spawnEntityOnServerThread(String id, double x, double y, double z, String nbt) {
    String location = parseId(id, "entity type");
    ServerWorld level = overworld();
    NbtCompound tag = parseTag(nbt);
    // The template is read by this line's own type table, which keys on the CamelCase name, so the
    // id is translated before it reaches it.
    String legacy = LegacyFabricNbt.legacyEntityId(location);
    tag.putString("id", legacy == null ? location.substring(location.indexOf(':') + 1) : legacy);
    Entity entity = EntityType.createInstanceFromNbt(tag, level);
    if (entity == null) {
      throw new IllegalArgumentException("unknown entity type: " + id);
    }
    entity.updatePosition(x, y, z);
    // The chunk is read through first: this line's spawn answers false for a position in a chunk
    // that is not loaded, and a script may name a spot far from any player.
    level.getChunkProvider().getOrGenerateChunks(floorChunk(x), floorChunk(z));
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
    String location = parseId(id, "block");
    Block block = Block.get(location);
    if (block == null) {
      throw new IllegalArgumentException("unknown block: " + id);
    }
    ServerWorld level = overworld();
    if (!replace && !level.isAir(new net.minecraft.util.math.BlockPos(x, y, z))) {
      return false;
    }
    // An id names a block, not one of its states: an id with no properties is the default state,
    // which is what the newer lines place for it too.
    if (!level.setBlockState(
        new net.minecraft.util.math.BlockPos(x, y, z), block.getDefaultState(), 3)) {
      return false;
    }
    NbtCompound tag = parseTag(nbt);
    if (!tag.getKeys().isEmpty()) {
      BlockEntity blockEntity = level.getBlockEntity(new net.minecraft.util.math.BlockPos(x, y, z));
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
    String location = parseId(id, "item");
    // This line's registry is keyed by Identifier, not by the string a script writes: looking up a
    // raw String answers null for every id. The string entry point wraps it, and still accepts a
    // numeric raw id the way the newer lines' lookup does.
    Item item = Item.getFromId(location);
    if (item == null) {
      throw new IllegalArgumentException("unknown item: " + id);
    }
    ServerWorld level = overworld();
    ItemStack stack = new ItemStack(item, count, 0);
    NbtCompound tag = parseTag(nbt);
    if (!tag.getKeys().isEmpty()) {
      stack.setNbt(tag);
    }
    ItemEntity itemEntity = new ItemEntity(level, x, y, z, stack);
    level.getChunkProvider().getOrGenerateChunks(floorChunk(x), floorChunk(z));
    if (!level.spawnEntity(itemEntity)) {
      throw new IllegalStateException("failed to spawn item drop " + id);
    }
    return itemEntity.getEntityId();
  }

  /**
   * Places a vanilla structure template.
   *
   * <p>1.9 ships its structures as NBT under {@code assets/minecraft/structures}, and the world
   * generator reads them through the structure manager. A 1.13 name resolves to the file this line
   * ships under its pre-1.13 name ({@code minecraft:igloo/top} reads {@code igloo/igloo_top.nbt}),
   * and the template is stamped without its entities.
   */
  @Override
  public boolean spawnStructure(String id, int x, int y, int z) {
    return onServerThread(() -> spawnStructureOnServerThread(id, x, y, z));
  }

  private boolean spawnStructureOnServerThread(String id, int x, int y, int z) {
    String identifier = parseId(id, "structure");
    int colon = identifier.indexOf(':');
    String namespace = colon < 0 ? "minecraft" : identifier.substring(0, colon);
    String path = colon < 0 ? identifier : identifier.substring(colon + 1);
    MinecraftServer s = requireServer();
    ServerWorld level = overworld();
    net.minecraft.structure.class_2763 manager = level.method_12783();
    net.minecraft.structure.Structure structure = null;
    for (String candidate : legacyTemplatePaths(path)) {
      try {
        structure =
            manager.method_11861(s, new net.minecraft.util.Identifier(namespace, candidate));
      } catch (RuntimeException e) {
        structure = null;
      }
      if (structure != null) {
        break;
      }
    }
    if (structure == null) {
      throw new IllegalArgumentException("unknown structure: " + id);
    }
    // The API places blocks only, which is the first boolean's ignore-entities flag.
    structure.method_11882(
        level,
        new BlockPos(x, y, z),
        new net.minecraft.structure.StructurePlacementData().method_11870(true));
    return true;
  }

  /**
   * The names a structure can go by on this line, given the 1.13 name a fixture uses: the group's
   * own name left the file in 1.13 ({@code igloo/igloo_top} became {@code igloo/top}).
   */
  private static List<String> legacyTemplatePaths(String path) {
    List<String> out = new ArrayList<>();
    out.add(path);
    int slash = path.indexOf('/');
    if (slash > 0) {
      String group = path.substring(0, slash);
      out.add(group + "/" + group + "_" + path.substring(slash + 1));
    }
    out.add(path.replace('/', '_'));
    return out;
  }
}
