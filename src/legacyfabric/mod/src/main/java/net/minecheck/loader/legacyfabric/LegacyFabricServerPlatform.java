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
import net.minecraft.nbt.NbtList;
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
          // 1.12.2's top position of a column is already the first air block above the surface —
          // the height a player standing there stands at — so it is returned as it is. Adding one
          // put every script's surface one block high, and every marker it placed one block above
          // where the world put the block.
          return level.getTopPosition(new BlockPos(x, 0, z)).getY();
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
    return onServerThread(() -> spawnStructureOnServerThread(id, x, y, z));
  }

  /**
   * Places a vanilla structure template.
   *
   * <p>This mapping exposes no template manager, so the template is read straight out of the game
   * jar — {@code assets/minecraft/structures/<id>.nbt} — and stamped block by block. A template
   * carries a palette and a list of positions; each position takes the palette entry's block, and
   * the block entity NBT it carries, if any. Block <em>states</em> are placed at the block's
   * default state, which is what this line's templates mostly are (planks, slabs, snow); a template
   * whose palette depends on orientation lands axis-aligned rather than rotated.
   */
  private boolean spawnStructureOnServerThread(String id, int x, int y, int z) {
    Identifier location = parseId(id, "structure");
    ServerWorld level = overworld();
    NbtCompound template = null;
    // 1.13 renamed the structure files: the group's own name left the file (`igloo/igloo_top`
    // became `igloo/top`), so both spellings are tried before giving up.
    for (String path : legacyStructurePaths(location.getPath())) {
      template = loadBundledStructure(location.getNamespace(), path);
      if (template != null) {
        break;
      }
    }
    if (template == null) {
      throw new IllegalArgumentException("unknown structure: " + id);
    }
    placeTemplate(level, template, new BlockPos(x, y, z));
    return true;
  }

  /** The names a structure can go by on 1.12.2, given the name a fixture uses. */
  private static List<String> legacyStructurePaths(String path) {
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

  /** A structure file as NBT, or {@code null} when the game jar has none under that name. */
  private static NbtCompound loadBundledStructure(String namespace, String path) {
    String asset = "/assets/" + namespace + "/structures/" + path + ".nbt";
    try (java.io.InputStream in = MinecraftServer.class.getResourceAsStream(asset)) {
      if (in == null) {
        return null;
      }
      return net.minecraft.nbt.NbtIo.readCompressed(in);
    } catch (Exception e) {
      return null;
    }
  }

  /** Stamps one template into the world, palette entry by palette entry. */
  private static void placeTemplate(ServerWorld level, NbtCompound template, BlockPos origin) {
    NbtList blocks = template.getList("blocks", 10);
    NbtList palette = template.getList("palette", 10);
    for (int i = 0; i < blocks.size(); i++) {
      NbtCompound entry = (NbtCompound) blocks.get(i);
      int stateIndex = entry.getInt("state");
      if (stateIndex < 0 || stateIndex >= palette.size()) {
        continue;
      }
      NbtCompound paletteEntry = (NbtCompound) palette.get(stateIndex);
      Block block = Block.get(paletteEntry.getString("Name"));
      if (block == null) {
        continue;
      }
      NbtList pos = entry.getList("pos", 3);
      BlockPos at = origin.add(pos.getInt(0), pos.getInt(1), pos.getInt(2));
      level.setBlockState(at, block.getDefaultState(), 2);
      NbtCompound blockEntityTag =
          entry.get("nbt") instanceof NbtCompound ? (NbtCompound) entry.get("nbt") : null;
      if (blockEntityTag != null) {
        BlockEntity blockEntity = level.getBlockEntity(at);
        if (blockEntity != null) {
          blockEntity.fromNbt(blockEntityTag);
        }
      }
    }
  }
}
