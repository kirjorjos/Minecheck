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
import net.minecheck.platform.api.ServerPlatformBase;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
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
public class LegacyFabricServerPlatform extends ServerPlatformBase {

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
      // The script bridge hands over SNBT (`{NoAI:true}`), whose keys are unquoted; the reader
      // accepts both forms.
      return LegacyJsonToNbt.parseSnbt(nbt);
    } catch (RuntimeException e) {
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
          // This line has no `/setblock` (and no `/fill`, which the shim expands into setblocks),
          // so the block is written through the world instead of the command table.
          Integer placed = LegacyCommandShim.setBlock(world, line);
          if (placed != null) {
            return placed;
          }
          // This line's give drops the stack on the ground and drops the tag argument, so gives
          // are answered here, straight into the inventory.
          Integer gave = LegacyCommandShim.give(null, line);
          if (gave != null) {
            return gave;
          }
          ((net.minecraft.server.command.CommandRegistry) s.getCommandManager())
              .executeCommand(s, line);
          // This line's command API returns nothing: a command that parses and runs has
          // succeeded, and a bad one throws before reaching here. One is the count a caller
          // that checks `result > 0` expects.
          return 1;
        });
  }

  /**
   * Applies a chat command this line's command table cannot run, for the server's command hook.
   *
   * <p>Only the block-writing lines are answered here; every other command is left to vanilla.
   * Returns {@code true} when the line was consumed.
   */
  public static boolean handleChatCommand(
      net.minecraft.entity.player.ServerPlayerEntity player, String command) {
    if (player == null || player.world == null) {
      return false;
    }
    MinecraftServer s = LegacyFabricEntrypoint.server();
    String line = command.startsWith("/") ? command.substring(1) : command;
    // This line's operator list is a raw set of names on the player manager, and `canCheat` is the
    // permission a command itself checks.
    boolean allowed =
        s != null
            && player.username != null
            && (s.getPlayerManager().getAdmins().contains(player.username)
                || s.getPlayerManager().canCheat(player.username));
    if (!allowed) {
      return false;
    }
    try {
      if (LegacyCommandShim.setBlock(player.world, line) != null) {
        return true;
      }
      return LegacyCommandShim.give(player, line) != null;
    } catch (IllegalArgumentException e) {
      // An unknown name is a failed command, not a broken connection: vanilla answers the line
      // itself, and the packet handler must not throw or the player is dropped.
      LOG.warning("Minecheck: " + e.getMessage());
      return false;
    }
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
      entities.put(entity.id, entity);
    }
    for (Object o : level.playerEntities) {
      Entity entity = (Entity) o;
      entities.put(entity.id, entity);
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
              entity.id,
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
   * <p>1.7.10 names its entity types in CamelCase and without a namespace ({@code Zombie}), and it
   * has no id for the player class, so both are translated here rather than leaking this line's
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
          BlockPos spawn = level.getWorldSpawnPos();
          return level.getChunkProvider().chunkExists(spawn.x >> 4, spawn.z >> 4);
        });
  }

  @Override
  public Integer groundHeight(int x, int z) {
    return onServerThread(
        () -> {
          ServerWorld level = overworld();
          if (!level.getChunkProvider().chunkExists(x >> 4, z >> 4)) {
            return null;
          }
          // This line has no column-top query, so the column is scanned from the build limit down
          // and the first block that is not air is the surface. The answer is one above it — the
          // height a player standing there stands at — which is what the newer lines' top position
          // is.
          for (int y = level.getEffectiveHeight() - 1; y >= 0; y--) {
            if (!level.isAir(x, y, z)) {
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
    return onServerThread(() -> overworld().getChunkProvider().chunkExists(chunkX, chunkZ));
  }

  @Override
  public boolean loadChunk(int chunkX, int chunkZ) {
    return onServerThread(
        () -> {
          ServerWorld level = overworld();
          // Reading the chunk through makes the load synchronous rather than a later tick. This
          // line has no forced-chunk ticket exposed in its API, so the chunk is loaded but not held
          // against unload.
          level.getChunkProvider().getOrGenerateChunk(chunkX, chunkZ);
          return level.getChunkProvider().chunkExists(chunkX, chunkZ);
        });
  }

  @Override
  public int spawnEntity(String id, double x, double y, double z, String nbt) {
    try {
      return onServerThread(() -> spawnEntityOnServerThread(id, x, y, z, nbt));
    } catch (RuntimeException e) {
      LOG.log(java.util.logging.Level.WARNING, "Minecheck: spawnEntity(" + id + ") failed", e);
      throw e;
    }
  }

  private int spawnEntityOnServerThread(String id, double x, double y, double z, String nbt) {
    String location = parseId(id, "entity type");
    ServerWorld level = overworld();
    NbtCompound tag = parseTag(nbt);
    // The template is read by this line's own type table, which keys on the CamelCase name, so the
    // id is translated before it reaches it.
    String legacy = LegacyFabricNbt.legacyEntityId(location);
    tag.putString("id", legacy == null ? location.substring(location.indexOf(':') + 1) : legacy);
    // This far back an entity's tag carries its position, motion and rotation, and the loader reads
    // all three unconditionally: a tag without them throws before the entity exists. They are
    // written from the spawn position, so the loader starts where the script asked.
    net.minecraft.nbt.NbtList pos = new net.minecraft.nbt.NbtList();
    pos.addItem(new net.minecraft.nbt.NbtDouble("", x));
    pos.addItem(new net.minecraft.nbt.NbtDouble("", y));
    pos.addItem(new net.minecraft.nbt.NbtDouble("", z));
    tag.put("Pos", pos);
    net.minecraft.nbt.NbtList motion = new net.minecraft.nbt.NbtList();
    for (int i = 0; i < 3; i++) {
      motion.addItem(new net.minecraft.nbt.NbtDouble("", 0.0));
    }
    tag.put("Motion", motion);
    net.minecraft.nbt.NbtList rotation = new net.minecraft.nbt.NbtList();
    rotation.addItem(new net.minecraft.nbt.NbtFloat("", 0.0f));
    rotation.addItem(new net.minecraft.nbt.NbtFloat("", 0.0f));
    tag.put("Rotation", rotation);
    Entity entity = EntityType.createInstanceFromNbt(tag, level);
    if (entity == null) {
      throw new IllegalArgumentException("unknown entity type: " + id);
    }
    entity.updatePosition(x, y, z);
    // The chunk is read through first: this line's spawn answers false for a position in a chunk
    // that is not loaded, and a script may name a spot far from any player.
    level.getChunkProvider().getOrGenerateChunk(floorChunk(x), floorChunk(z));
    if (!level.spawnEntity(entity)) {
      throw new IllegalStateException("failed to spawn entity " + id);
    }
    // The tag is kept for the parts this line has no system to hold (see dataGetEntity).
    LegacyCommandShim.rememberSpawnTag(entity.id, tag);
    return entity.id;
  }

  @Override
  public boolean spawnBlock(String id, int x, int y, int z, String nbt, boolean replace) {
    return onServerThread(() -> spawnBlockOnServerThread(id, x, y, z, nbt, replace));
  }

  private boolean spawnBlockOnServerThread(
      String id, int x, int y, int z, String nbt, boolean replace) {
    String location = parseId(id, "block");
    Block block = LegacyFabricNames.block(location);
    if (block == null) {
      throw new IllegalArgumentException("unknown block: " + id);
    }
    ServerWorld level = overworld();
    if (!replace && !level.isAir(x, y, z)) {
      return false;
    }
    // 1.7.10 has no block states: a block is placed with its metadata, and every id a script names
    // is placed at metadata 0, which is what the newer lines' default state resolves to.
    if (!LegacyFabricNames.setBlock(level, x, y, z, block)) {
      return false;
    }
    NbtCompound tag = parseTag(nbt);
    if (!tag.isEmpty()) {
      BlockEntity blockEntity = level.getBlockEntity(x, y, z);
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
    Item registered = LegacyFabricNames.item(location);
    if (registered == null) {
      throw new IllegalArgumentException("unknown item: " + id);
    }
    ServerWorld level = overworld();
    ItemStack stack = new ItemStack(registered, count, 0);
    NbtCompound tag = parseTag(nbt);
    if (!tag.isEmpty()) {
      stack.setNbt(tag);
    }
    ItemEntity itemEntity = new ItemEntity(level, x, y, z, stack);
    level.getChunkProvider().getOrGenerateChunk(floorChunk(x), floorChunk(z));
    if (!level.spawnEntity(itemEntity)) {
      throw new IllegalStateException("failed to spawn item drop " + id);
    }
    return itemEntity.id;
  }

  /**
   * Places a vanilla structure template.
   *
   * <p>This line predates bundled templates: 1.7.10 has no {@code assets/minecraft/structures}, and
   * its world generator builds villages and temples from code. There is nothing to stamp, so the
   * name is reported as unknown rather than silently doing nothing — the same answer a name that
   * does not exist gets on the newer lines.
   */
  @Override
  public boolean spawnStructure(String id, int x, int y, int z) {
    return onServerThread(
        () -> {
          throw new IllegalArgumentException(
              "unknown structure: " + id + " (this line has no bundled structure templates)");
        });
  }
}
