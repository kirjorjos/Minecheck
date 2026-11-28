package net.minecheck.loader.forge;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.function.Supplier;
import net.minecheck.platform.api.ServerPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

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

  private <T> T onServerThread(Supplier<T> action) {
    MinecraftServer s = requireServer();
    if (s.isSameThread()) {
      return action.get();
    }
    // Not MinecraftServer#submit: 1.16.5 only inherits it from BlockableEventLoop, and the runtime
    // method lookup on the server class fails there. execute(Runnable) is the same seam without the
    // signature that moved.
    java.util.concurrent.CompletableFuture<T> done = new java.util.concurrent.CompletableFuture<>();
    s.execute(
        () -> {
          try {
            done.complete(action.get());
          } catch (Throwable t) {
            done.completeExceptionally(t);
          }
        });
    return done.join();
  }

  private ServerLevel overworld() {
    MinecraftServer s = requireServer();
    ServerLevel level = s.getLevel(net.minecraft.world.level.dimension.DimensionType.OVERWORLD);
    if (level == null) {
      throw new IllegalStateException("overworld not available yet");
    }
    return level;
  }

  private static CompoundTag parseTag(String nbt) {
    if (nbt == null || nbt.isEmpty()) {
      return new CompoundTag();
    }
    try {
      return TagParser.parseTag(nbt);
    } catch (CommandSyntaxException e) {
      throw new IllegalArgumentException("invalid NBT: " + e.getMessage(), e);
    }
  }

  private static <T> T requireRegistryEntry(
      Registry<T> registry, ResourceLocation id, String what) {
    // A null get is the answer: 1.16.5's Registry interface has no containsKey(ResourceLocation),
    // and
    // calling it was a NoSuchMethodError at runtime rather than a compile error.
    T entry = registry.get(id);
    if (entry == null) {
      throw new IllegalArgumentException("unknown " + what + ": " + id);
    }
    return entry;
  }

  @Override
  public int runCommand(String command) {
    return onServerThread(
        () -> {
          MinecraftServer s = requireServer();
          String line = command.startsWith("/") ? command.substring(1) : command;
          return s.getCommands().performCommand(s.createCommandSourceStack(), line);
        });
  }

  @Override
  public boolean isPlayerOnline(String name) {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(() -> s.getPlayerList().getPlayerByName(name) != null);
  }

  @Override
  public void applyDeterministicRules() {
    onServerThread(
        () -> {
          MinecraftServer s = requireServer();
          s.getGameRules()
              .getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING)
              .set(false, null);
          s.getGameRules()
              .getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)
              .set(false, null);
          s.getGameRules()
              .getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE)
              .set(false, null);
          // 1.14 has no insomnia rule; it arrives in 1.15. Mob spawning is off anyway.
          return null;
        });
  }

  @Override
  public java.util.List<net.minecheck.platform.api.EntityInfo> findEntities(
      String type, String tag) {
    return onServerThread(() -> findEntitiesOnServerThread(type, tag));
  }

  private java.util.List<net.minecheck.platform.api.EntityInfo> findEntitiesOnServerThread(
      String type, String tag) {
    ResourceLocation typeId = type == null ? null : ResourceLocation.tryParse(type);
    ServerLevel level = overworld();
    java.util.List<net.minecheck.platform.api.EntityInfo> out = new java.util.ArrayList<>();
    for (Entity entity : (Iterable<Entity>) level.getEntities()::iterator) {
      // 1.16.5's getAllEntities() keeps an entity until the tick after it was removed, and a dying
      // mob
      // stays listed for its death animation. Neither is something a client can observe as present,
      // so neither is a match for a query.
      if (entity.removed
          || (entity instanceof net.minecraft.world.entity.LivingEntity living
              && !living.isAlive())) {
        continue;
      }
      if (typeId != null && !Registry.ENTITY_TYPE.getKey(entity.getType()).equals(typeId)) {
        continue;
      }
      if (tag != null && !entity.getTags().contains(tag)) {
        continue;
      }
      out.add(
          new net.minecheck.platform.api.EntityInfo(
              entity.getId(),
              Registry.ENTITY_TYPE.getKey(entity.getType()).toString(),
              entity.x,
              entity.y,
              entity.z,
              new java.util.HashSet<>(entity.getTags())));
    }
    return java.util.List.copyOf(out);
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(
        () -> {
          ServerLevel overworld =
              s.getLevel(net.minecraft.world.level.dimension.DimensionType.OVERWORLD);
          return overworld != null && overworld.getChunkSource().getPendingTasksCount() == 0;
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
          ServerLevel level = overworld();
          // Asked of the chunk, the same way `isChunkLoaded` answers: "does this column have a
          // height" is a property of the loaded chunk, not of one block in it.
          if (!level.hasChunk(x >> 4, z >> 4)) {
            return null;
          }
          return level.getHeight(
              net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        });
  }

  @Override
  public boolean isChunkLoaded(int chunkX, int chunkZ) {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(() -> overworld().hasChunk(chunkX, chunkZ));
  }

  @Override
  public boolean loadChunk(int chunkX, int chunkZ) {
    return onServerThread(
        () -> {
          ServerLevel level = overworld();
          // The same forced ticket `forceload add` sets, so the chunk stays loaded for the rest of
          // the run; reading it through makes the load synchronous rather than a later tick.
          level.setChunkForced(chunkX, chunkZ, true);
          level.getChunk(chunkX, chunkZ);
          return level.hasChunk(chunkX, chunkZ);
        });
  }

  @Override
  public int spawnEntity(String id, double x, double y, double z, String nbt) {
    return onServerThread(() -> spawnEntityOnServerThread(id, x, y, z, nbt));
  }

  private int spawnEntityOnServerThread(String id, double x, double y, double z, String nbt) {
    ResourceLocation location = ResourceLocation.tryParse(id);
    if (location == null) {
      throw new IllegalArgumentException("invalid entity id: " + id);
    }
    requireRegistryEntry(Registry.ENTITY_TYPE, location, "entity type");
    ServerLevel level = overworld();
    CompoundTag tag = parseTag(nbt);
    tag.putString("id", location.toString());
    Entity entity =
        EntityType.loadEntityRecursive(
            tag,
            level,
            loaded -> {
              loaded.moveTo(x, y, z, loaded.yRot, loaded.xRot);
              return loaded;
            });
    if (entity == null) {
      throw new IllegalArgumentException("entity type cannot be spawned directly: " + id);
    }
    if (entity instanceof Mob mob) {
      mob.finalizeSpawn(
          level,
          level.getCurrentDifficultyAt(
              new BlockPos((int) entity.x, (int) entity.y, (int) entity.z)),
          MobSpawnType.COMMAND,
          null,
          null);
    }
    if (!level.addFreshEntity(entity)) {
      throw new IllegalStateException("failed to spawn entity " + id);
    }
    return entity.getId();
  }

  @Override
  public boolean spawnBlock(String id, int x, int y, int z, String nbt, boolean replace) {
    return onServerThread(() -> spawnBlockOnServerThread(id, x, y, z, nbt, replace));
  }

  private boolean spawnBlockOnServerThread(
      String id, int x, int y, int z, String nbt, boolean replace) {
    ResourceLocation location = ResourceLocation.tryParse(id);
    if (location == null) {
      throw new IllegalArgumentException("invalid block id: " + id);
    }
    Block block = requireRegistryEntry(Registry.BLOCK, location, "block");
    ServerLevel level = overworld();
    BlockPos pos = new BlockPos(x, y, z);
    if (!replace && !level.getBlockState(pos).isAir()) {
      return false;
    }
    BlockState state = block.defaultBlockState();
    if (!level.setBlock(pos, state, 3)) {
      return false;
    }
    CompoundTag tag = parseTag(nbt);
    if (!tag.isEmpty()) {
      BlockEntity blockEntity = level.getBlockEntity(pos);
      if (blockEntity != null) {
        blockEntity.load(tag);
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
    ResourceLocation location = ResourceLocation.tryParse(id);
    if (location == null) {
      throw new IllegalArgumentException("invalid item id: " + id);
    }
    Item item = requireRegistryEntry(Registry.ITEM, location, "item");
    ServerLevel level = overworld();
    ItemStack stack = new ItemStack(item);
    stack.setCount(count);
    CompoundTag tag = parseTag(nbt);
    if (!tag.isEmpty()) {
      stack.setTag(tag);
    }
    ItemEntity itemEntity = new ItemEntity(level, x, y, z, stack);
    if (!level.addFreshEntity(itemEntity)) {
      throw new IllegalStateException("failed to spawn item drop " + id);
    }
    return itemEntity.getId();
  }

  @Override
  public boolean spawnStructure(String id, int x, int y, int z) {
    return onServerThread(() -> spawnStructureOnServerThread(id, x, y, z));
  }

  private boolean spawnStructureOnServerThread(String id, int x, int y, int z) {
    ResourceLocation location = ResourceLocation.tryParse(id);
    if (location == null) {
      throw new IllegalArgumentException("invalid structure id: " + id);
    }
    ServerLevel level = overworld();
    StructureManager manager = level.getStructureManager();
    // 1.16.5's get() answers the template itself, null when the id is unknown.
    StructureTemplate template = manager.get(location);
    if (template == null) {
      throw new IllegalArgumentException("unknown structure: " + id);
    }
    BlockPos pos = new BlockPos(x, y, z);
    StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
    return template.placeInWorld(level, pos, settings, 2);
  }
}
