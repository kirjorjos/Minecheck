package net.minecheck.loader.forge;

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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * The server-side half of the Forge platform.
 *
 * <p>Every action is executed on the server thread: the script engine is driven from the server
 * tick, but a client's integrated server is queried from the client thread, so the marshaling here
 * is what makes one implementation serve both.
 *
 * <p>1.7.10's server has no {@code submit} and no {@code isCallingFromMinecraftThread}, so the
 * marshaling is its own: calls made off the server thread are queued and run from {@link
 * #setServer}, which the tick hook calls on the server thread once per tick. That is one tick of
 * latency for a cross-thread call, which is what a caller waiting on the answer pays; it is bounded
 * by {@link #WAIT_SECONDS} so a stopped server reports an error instead of hanging the caller for
 * ever.
 */
public class ForgeServerPlatform implements ServerPlatform {

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

  private WorldServer overworld() {
    WorldServer level = requireServer().worldServerForDimension(0);
    if (level == null) {
      throw new IllegalStateException("overworld not available yet");
    }
    return level;
  }

  /** The chunk provider of a server world, which is the only one that answers about chunks. */
  private static ChunkProviderServer chunks(WorldServer level) {
    return (ChunkProviderServer) level.getChunkProvider();
  }

  /**
   * A registry id as this line reads it: trimmed and lower-cased, so {@code minecraft:Gold_Block}
   * is a name error and not a crash in the parser.
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

  private static NBTTagCompound parseTag(String nbt) {
    if (nbt == null || nbt.isEmpty()) {
      return new NBTTagCompound();
    }
    try {
      // This line's reader answers a generic tag; a command or fixture always writes a compound.
      return (NBTTagCompound) JsonToNBT.func_150315_a(nbt);
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
          WorldServer world = s.worldServerForDimension(0);
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
          return s.getCommandManager().executeCommand(s, line);
        });
  }

  @Override
  public boolean isPlayerOnline(String name) {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(() -> s.getConfigurationManager().getPlayerByUsername(name) != null);
  }

  @Override
  public void applyDeterministicRules() {
    onServerThread(
        () -> {
          WorldServer level = overworld();
          net.minecraft.world.GameRules rules = level.getGameRules();
          // doWeatherCycle arrives in 1.11; this line knows no such rule and creates nothing for
          // it.
          // Weather is pinned by the run's own clock instead.
          rules.setOrCreateGameRule("doMobSpawning", "false");
          rules.setOrCreateGameRule("doDaylightCycle", "false");
          return null;
        });
  }

  @Override
  public List<EntityInfo> findEntities(String type, String tag) {
    return onServerThread(() -> findEntitiesOnServerThread(type, tag));
  }

  private List<EntityInfo> findEntitiesOnServerThread(String type, String tag) {
    String typeId = type == null ? null : parseId(type, "entity type");
    WorldServer level = overworld();
    List<EntityInfo> out = new ArrayList<>();
    // Players keep their own list on this line: the first thing a script asks for is the player
    // that just joined, which is not in the loaded-entity list until the world ticks it.
    Map<Integer, Entity> entities = new LinkedHashMap<>();
    for (Object o : level.loadedEntityList) {
      Entity entity = (Entity) o;
      entities.put(entity.getEntityId(), entity);
    }
    for (Object o : level.playerEntities) {
      Entity entity = (Entity) o;
      entities.put(entity.getEntityId(), entity);
    }
    for (Entity entity : entities.values()) {
      // A removed entity stays listed until the next tick; it is not something a client observes.
      if (entity.isDead) {
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
              entity.getEntityId(), actual, entity.posX, entity.posY, entity.posZ, tags));
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
    String legacy = EntityList.getEntityString(entity);
    if (legacy == null) {
      // The player is the one entity this line registers no type for; the newer lines answer
      // `minecraft:player` for it.
      return "minecraft:player";
    }
    return MinecheckNames.modernEntityId(legacy);
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(
        () -> {
          WorldServer level = s.worldServerForDimension(0);
          if (level == null) {
            return false;
          }
          // The chunk the level spawned in, not chunk 0,0: this line's world spawns its player away
          // from the origin, so 0,0 is generated but unloaded until something forces it.
          net.minecraft.util.ChunkCoordinates spawn = level.getSpawnPoint();
          return chunks(level).chunkExists(spawn.posX >> 4, spawn.posZ >> 4);
        });
  }

  @Override
  public Long worldTime() {
    MinecraftServer s = server;
    if (s == null) {
      return null;
    }
    return onServerThread(() -> overworld().getDayTime());
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
          ServerLevel level = overworld();
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
  public Integer groundHeight(int x, int z) {
    return onServerThread(
        () -> {
          WorldServer level = overworld();
          if (!chunks(level).chunkExists(x >> 4, z >> 4)) {
            return null;
          }
          // This line has no column-top query, so the column is scanned from the build limit down
          // and the first block that is not air is the surface. The answer is one above it — the
          // height a player standing there stands at — which is what the newer lines' top position
          // is.
          for (int y = level.getHeight() - 1; y >= 0; y--) {
            if (!level.isAirBlock(x, y, z)) {
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
    return onServerThread(() -> chunks(overworld()).chunkExists(chunkX, chunkZ));
  }

  @Override
  public boolean loadChunk(int chunkX, int chunkZ) {
    return onServerThread(
        () -> {
          WorldServer level = overworld();
          // Reading the chunk through makes the load synchronous rather than a later tick. This
          // line has no forced-chunk ticket exposed in its API, so the chunk is loaded but not held
          // against unload.
          level.getChunkProvider().loadChunk(chunkX, chunkZ);
          return chunks(level).chunkExists(chunkX, chunkZ);
        });
  }

  @Override
  public int spawnEntity(String id, double x, double y, double z, String nbt) {
    return onServerThread(() -> spawnEntityOnServerThread(id, x, y, z, nbt));
  }

  private int spawnEntityOnServerThread(String id, double x, double y, double z, String nbt) {
    String location = parseId(id, "entity type");
    WorldServer level = overworld();
    NBTTagCompound tag = parseTag(nbt);
    // The template is read by this line's own type table, which keys on the CamelCase name, so the
    // id is translated before it reaches it.
    String legacy = MinecheckNames.legacyEntityId(location);
    tag.setString("id", legacy == null ? location.substring(location.indexOf(':') + 1) : legacy);
    Entity entity = EntityList.createEntityFromNBT(tag, level);
    if (entity == null) {
      throw new IllegalArgumentException("unknown entity type: " + id);
    }
    entity.setPosition(x, y, z);
    // The chunk is read through first: this line's spawn answers false for a position in a chunk
    // that is not loaded, and a script may name a spot far from any player.
    level.getChunkProvider().loadChunk(floorChunk(x), floorChunk(z));
    if (!level.spawnEntityInWorld(entity)) {
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
    Block block = Block.getBlockFromName(MinecheckNames.legacyId(location));
    if (block == null) {
      throw new IllegalArgumentException("unknown block: " + id);
    }
    WorldServer level = overworld();
    if (!replace && !level.isAirBlock(x, y, z)) {
      return false;
    }
    // 1.7.10 has no block states: a block is placed with its metadata, and every id a script names
    // is placed at metadata 0, which is what the newer lines' default state resolves to.
    if (!level.setBlock(x, y, z, block, 0, 3)) {
      return false;
    }
    NBTTagCompound tag = parseTag(nbt);
    if (!tag.getKeySet().isEmpty()) {
      TileEntity blockEntity = level.getTileEntity(x, y, z);
      if (blockEntity != null) {
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        blockEntity.readFromNBT(tag);
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
    Object registered = Item.itemRegistry.getObject(location);
    if (!(registered instanceof Item)) {
      throw new IllegalArgumentException("unknown item: " + id);
    }
    WorldServer level = overworld();
    ItemStack stack = new ItemStack((Item) registered, count, 0);
    NBTTagCompound tag = parseTag(nbt);
    if (!tag.getKeySet().isEmpty()) {
      stack.setTagCompound(tag);
    }
    EntityItem itemEntity = new EntityItem(level, x, y, z, stack);
    level.getChunkProvider().loadChunk(floorChunk(x), floorChunk(z));
    if (!level.spawnEntityInWorld(itemEntity)) {
      throw new IllegalStateException("failed to spawn item drop " + id);
    }
    return itemEntity.getEntityId();
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
