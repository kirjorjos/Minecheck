package net.minecheck.loader.forge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.minecheck.platform.api.EntityInfo;
import net.minecheck.platform.api.ServerPlatformBase;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
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
public class ForgeServerPlatform extends ServerPlatformBase {

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
    // A latch rather than a future: on Java 7 JvmDowngrader rewrites `CompletableFuture` to a stub
    // that only carries the static factories, so `new CompletableFuture<>()` does not survive the
    // downgrade.
    Object[] value = new Object[1];
    Throwable[] failure = new Throwable[1];
    CountDownLatch done = new CountDownLatch(1);
    pending.add(
        () -> {
          try {
            value[0] = action.get();
          } catch (Throwable t) {
            failure[0] = t;
          } finally {
            done.countDown();
          }
        });
    try {
      if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("the server did not reach the action in time");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the server", e);
    }
    Throwable cause = failure[0];
    if (cause != null) {
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new IllegalStateException(cause);
    }
    @SuppressWarnings("unchecked")
    T result = (T) value[0];
    return result;
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
      return JsonToNbt.parse(nbt);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid NBT: " + e.getMessage(), e);
    }
  }

  @Override
  public int runCommand(String command) {
    return onServerThread(
        (Supplier<Integer>)
            () -> {
              MinecraftServer s = requireServer();
              String line =
                  LegacyCommandShim.translate(
                      command.startsWith("/") ? command.substring(1) : command);
              WorldServer world = s.worldServerForDimension(0);
              Integer given = LegacyCommandShim.give(world, line);
              if (given != null) {
                return given;
              }
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
              // 1.4.7's command manager answers void, where the newer lines answer the result
              // count;
              // reaching here means the command was dispatched, which is the success this API
              // reports.
              s.getCommandManager().executeCommand(s, line);
              return 1;
            });
  }

  @Override
  public boolean isPlayerOnline(String name) {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(() -> s.getConfigurationManager().getPlayerForUsername(name) != null);
  }

  @Override
  public void applyDeterministicRules() {
    onServerThread(
        () -> {
          WorldServer level = overworld();
          net.minecraft.world.GameRules rules = level.getGameRules();
          // The two rules this line's own game loop reads, and the two later lines add. The later
          // pair changes no behaviour here — nothing reads them — but a fixture queries them, and a
          // rule that does not exist reads as whatever the world saved: created as `false` the
          // query answers what the run actually guarantees (weather and insomnia are pinned by the
          // run's own clock).
          rules.setOrCreateGameRule("doMobSpawning", "false");
          rules.setOrCreateGameRule("doDaylightCycle", "false");
          rules.setOrCreateGameRule("doWeatherCycle", "false");
          rules.setOrCreateGameRule("doInsomnia", "false");
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
      entities.put(entity.entityId, entity);
    }
    for (Object o : level.playerEntities) {
      Entity entity = (Entity) o;
      entities.put(entity.entityId, entity);
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
      out.add(new EntityInfo(entity.entityId, actual, entity.posX, entity.posY, entity.posZ, tags));
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
    String name = legacy == null ? location.substring(location.indexOf(':') + 1) : legacy;
    // This line's reader takes a *saved* entity, not a script's field list: it reads `Pos`,
    // `Motion` and `Rotation` out of the tag without checking that they are there, so a tag of
    // nothing but `NoAI` answered `IndexOutOfBoundsException: Index: 0, Size: 0` and the spawn
    // failed. A blank instance of the type is written out first, and the script's fields are merged
    // over the top of it — the same shape a chunk save would have produced.
    Entity blank = EntityList.createEntityByName(name, level);
    if (blank == null) {
      throw new IllegalArgumentException("unknown entity type: " + id);
    }
    NBTTagCompound full = new NBTTagCompound();
    // The public writer, which is the one chunk saving uses: it writes `Pos`, `Motion`,
    // `Rotation` and the flags this line's reader expects, and delegates the type's own fields to
    // the protected `writeEntityToNBT`.
    blank.writeToNBT(full);
    for (Object raw : tag.getTags()) {
      NBTBase child = (NBTBase) raw;
      full.setTag(child.getName(), child);
    }
    full.setString("id", name);
    Entity entity = EntityList.createEntityFromNBT(full, level);
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
    // A field the spawn declared that this line has no storage for stays readable through
    // `data get entity` (see LegacyCommandShim#rememberSpawnTag).
    LegacyCommandShim.rememberSpawnTag(entity.entityId, tag);
    return entity.entityId;
  }

  @Override
  public boolean spawnBlock(String id, int x, int y, int z, String nbt, boolean replace) {
    return onServerThread(() -> spawnBlockOnServerThread(id, x, y, z, nbt, replace));
  }

  private boolean spawnBlockOnServerThread(
      String id, int x, int y, int z, String nbt, boolean replace) {
    String location = parseId(id, "block");
    Block block = MinecheckNames.block(location);
    if (block == null) {
      throw new IllegalArgumentException("unknown block: " + id);
    }
    WorldServer level = overworld();
    if (!replace && !level.isAirBlock(x, y, z)) {
      return false;
    }
    // 1.7.10 has no block states: a block is placed with its metadata, and every id a script names
    // is placed at metadata 0, which is what the newer lines' default state resolves to.
    if (!level.setBlockAndMetadataWithNotify(x, y, z, block.blockID, 0)) {
      return false;
    }
    NBTTagCompound tag = parseTag(nbt);
    if (!tag.hasNoTags()) {
      TileEntity blockEntity = level.getBlockTileEntity(x, y, z);
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
    Item registered = MinecheckNames.item(location);
    if (registered == null) {
      throw new IllegalArgumentException("unknown item: " + id);
    }
    WorldServer level = overworld();
    ItemStack stack = new ItemStack(registered, count, 0);
    NBTTagCompound tag = parseTag(nbt);
    if (!tag.hasNoTags()) {
      stack.setTagCompound(tag);
    }
    EntityItem itemEntity = new EntityItem(level, x, y, z, stack);
    level.getChunkProvider().loadChunk(floorChunk(x), floorChunk(z));
    if (!level.spawnEntityInWorld(itemEntity)) {
      throw new IllegalStateException("failed to spawn item drop " + id);
    }
    return itemEntity.entityId;
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
