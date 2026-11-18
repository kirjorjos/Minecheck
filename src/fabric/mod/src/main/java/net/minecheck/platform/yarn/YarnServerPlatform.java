package net.minecheck.platform.yarn;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.function.Supplier;
import net.minecheck.platform.api.ServerPlatform;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.Structure;
import net.minecraft.structure.StructureManager;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;

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

  private <T> T onServerThread(Supplier<T> action) {
    MinecraftServer s = requireServer();
    if (s.isOnThread()) {
      return action.get();
    }
    // Not MinecraftServer#submit: 1.16.5 only inherits it from ThreadExecutor, and the runtime
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

  private ServerWorld overworld() {
    MinecraftServer s = requireServer();
    ServerWorld world = s.getWorld(net.minecraft.world.dimension.DimensionType.OVERWORLD);
    if (world == null) {
      throw new IllegalStateException("overworld not available yet");
    }
    return world;
  }

  private static CompoundTag parseNbt(String nbt) {
    if (nbt == null || nbt.isEmpty()) {
      return new CompoundTag();
    }
    try {
      return StringNbtReader.parse(nbt);
    } catch (CommandSyntaxException e) {
      throw new IllegalArgumentException("invalid NBT: " + e.getMessage(), e);
    }
  }

  private static <T> T requireRegistryEntry(Registry<T> registry, Identifier id, String what) {
    // A null get is the answer: 1.16.5's Registry has no containsId on the interface, and
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
          return s.getCommandManager().execute(s.getCommandSource(), line);
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
          MinecraftServer s = requireServer();
          s.getGameRules().get(net.minecraft.world.GameRules.DO_MOB_SPAWNING).set(false, null);
          s.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
          s.getGameRules().get(net.minecraft.world.GameRules.DO_WEATHER_CYCLE).set(false, null);
          s.getGameRules().get(net.minecraft.world.GameRules.DO_INSOMNIA).set(false, null);
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
    Identifier typeId = type == null ? null : Identifier.tryParse(type);
    ServerWorld world = overworld();
    java.util.List<net.minecheck.platform.api.EntityInfo> out = new java.util.ArrayList<>();
    // 1.15 has no iterateEntities: the same list comes from a typeless entity query.
    for (Entity entity : world.getEntities((net.minecraft.entity.EntityType<?>) null, e -> true)) {
      // 1.16.5 keeps an entity until the tick after it was removed, and a dying mob stays listed
      // for
      // its death animation. Neither is observable as present on a client, so neither is a match.
      if (entity.removed
          || (entity instanceof net.minecraft.entity.LivingEntity living && !living.isAlive())) {
        continue;
      }
      if (typeId != null && !Registry.ENTITY_TYPE.getId(entity.getType()).equals(typeId)) {
        continue;
      }
      if (tag != null && !entity.getScoreboardTags().contains(tag)) {
        continue;
      }
      out.add(
          new net.minecheck.platform.api.EntityInfo(
              entity.getEntityId(),
              Registry.ENTITY_TYPE.getId(entity.getType()).toString(),
              entity.getX(),
              entity.getY(),
              entity.getZ(),
              new java.util.HashSet<>(entity.getScoreboardTags())));
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
          ServerWorld overworld = s.getWorld(net.minecraft.world.dimension.DimensionType.OVERWORLD);
          // 1.15 exposes no pending-task count; the origin chunk every fixture stands on being
          // generated is the observable form of "generation settled" there.
          return overworld != null
              && overworld
                      .getChunkManager()
                      .getChunk(0, 0, net.minecraft.world.chunk.ChunkStatus.FULL, false)
                  != null;
        });
  }

  @Override
  public Integer groundHeight(int x, int z) {
    return onServerThread(
        () -> {
          ServerWorld world = overworld();
          if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
            return null;
          }
          return world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
        });
  }

  @Override
  public boolean isChunkLoaded(int chunkX, int chunkZ) {
    MinecraftServer s = server;
    if (s == null) {
      return false;
    }
    return onServerThread(() -> overworld().getChunkManager().isChunkLoaded(chunkX, chunkZ));
  }

  @Override
  public boolean loadChunk(int chunkX, int chunkZ) {
    return onServerThread(
        () -> {
          ServerWorld world = overworld();
          // The same forced ticket `forceload add` sets, so the chunk stays loaded for the rest of
          // the run; reading it through makes the load synchronous rather than a later tick.
          world.setChunkForced(chunkX, chunkZ, true);
          world.getChunk(chunkX, chunkZ);
          return world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
        });
  }

  @Override
  public int spawnEntity(String id, double x, double y, double z, String nbt) {
    return onServerThread(() -> spawnEntityOnServerThread(id, x, y, z, nbt));
  }

  private int spawnEntityOnServerThread(String id, double x, double y, double z, String nbt) {
    Identifier identifier = Identifier.tryParse(id);
    if (identifier == null) {
      throw new IllegalArgumentException("invalid entity id: " + id);
    }
    requireRegistryEntry(Registry.ENTITY_TYPE, identifier, "entity type");
    ServerWorld world = overworld();
    CompoundTag compound = parseNbt(nbt);
    compound.putString("id", identifier.toString());
    Entity entity =
        EntityType.loadEntityWithPassengers(
            compound,
            world,
            loaded -> {
              loaded.refreshPositionAndAngles(x, y, z, loaded.getYaw(1.0f), loaded.getPitch(1.0f));
              return loaded;
            });
    if (entity == null) {
      throw new IllegalArgumentException("entity type cannot be spawned directly: " + id);
    }
    if (entity instanceof MobEntity mob) {
      mob.initialize(
          world, world.getLocalDifficulty(entity.getBlockPos()), SpawnType.COMMAND, null, null);
    }
    if (!world.spawnEntity(entity)) {
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
    Identifier identifier = Identifier.tryParse(id);
    if (identifier == null) {
      throw new IllegalArgumentException("invalid block id: " + id);
    }
    net.minecraft.block.Block block = requireRegistryEntry(Registry.BLOCK, identifier, "block");
    ServerWorld world = overworld();
    BlockPos pos = new BlockPos(x, y, z);
    if (!replace && !world.getBlockState(pos).isAir()) {
      return false;
    }
    BlockState state = block.getDefaultState();
    if (!world.setBlockState(pos, state, 3)) {
      return false;
    }
    CompoundTag compound = parseNbt(nbt);
    if (!compound.isEmpty()) {
      BlockEntity blockEntity = world.getBlockEntity(pos);
      if (blockEntity != null) {
        blockEntity.fromTag(compound);
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
    Identifier identifier = Identifier.tryParse(id);
    if (identifier == null) {
      throw new IllegalArgumentException("invalid item id: " + id);
    }
    Item item = requireRegistryEntry(Registry.ITEM, identifier, "item");
    ServerWorld world = overworld();
    ItemStack stack = new ItemStack(item);
    stack.setCount(count);
    CompoundTag compound = parseNbt(nbt);
    if (!compound.isEmpty()) {
      stack.setTag(compound);
    }
    ItemEntity itemEntity = new ItemEntity(world, x, y, z, stack);
    if (!world.spawnEntity(itemEntity)) {
      throw new IllegalStateException("failed to spawn item drop " + id);
    }
    return itemEntity.getEntityId();
  }

  @Override
  public boolean spawnStructure(String id, int x, int y, int z) {
    return onServerThread(() -> spawnStructureOnServerThread(id, x, y, z));
  }

  private boolean spawnStructureOnServerThread(String id, int x, int y, int z) {
    Identifier identifier = Identifier.tryParse(id);
    if (identifier == null) {
      throw new IllegalArgumentException("invalid structure id: " + id);
    }
    ServerWorld world = overworld();
    StructureManager manager = world.getStructureManager();
    Structure template = manager.getStructure(identifier);
    if (template == null) {
      throw new IllegalArgumentException("unknown structure: " + id);
    }
    BlockPos pos = new BlockPos(x, y, z);
    StructurePlacementData placementData = new StructurePlacementData().setIgnoreEntities(true);
    // 1.15's Structure#place is void: it reports nothing, so a placed template is a success.
    template.place(world, pos, placementData);
    return true;
  }
}
