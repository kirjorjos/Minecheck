package net.minecheck.platform.yarn;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.function.Supplier;
import net.minecheck.platform.api.ServerPlatform;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
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
    return s.submit(action).join();
  }

  private ServerWorld overworld() {
    MinecraftServer s = requireServer();
    ServerWorld world = s.getWorld(net.minecraft.world.World.OVERWORLD);
    if (world == null) {
      throw new IllegalStateException("overworld not available yet");
    }
    return world;
  }

  private static NbtCompound parseNbt(String nbt) {
    if (nbt == null || nbt.isEmpty()) {
      return new NbtCompound();
    }
    try {
      return NbtHelper.fromNbtProviderString(nbt);
    } catch (CommandSyntaxException e) {
      throw new IllegalArgumentException("invalid NBT: " + e.getMessage(), e);
    }
  }

  private static <T> T requireRegistryEntry(Registry<T> registry, Identifier id, String what) {
    if (!registry.containsId(id)) {
      throw new IllegalArgumentException("unknown " + what + ": " + id);
    }
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
          return s.getCommandManager().executeWithPrefix(s.getCommandSource(), command);
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
    for (Entity entity : world.iterateEntities()) {
      if (typeId != null && !Registry.ENTITY_TYPE.getId(entity.getType()).equals(typeId)) {
        continue;
      }
      if (tag != null && !entity.getScoreboardTags().contains(tag)) {
        continue;
      }
      out.add(
          new net.minecheck.platform.api.EntityInfo(
              entity.getId(),
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
          ServerWorld overworld = s.getWorld(net.minecraft.world.World.OVERWORLD);
          return overworld != null && overworld.getChunkManager().getPendingTasks() == 0;
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
    NbtCompound compound = parseNbt(nbt);
    compound.putString("id", identifier.toString());
    Entity entity =
        EntityType.loadEntityWithPassengers(
            compound,
            world,
            loaded -> {
              loaded.refreshPositionAndAngles(x, y, z, loaded.getYaw(), loaded.getPitch());
              return loaded;
            });
    if (entity == null) {
      throw new IllegalArgumentException("entity type cannot be spawned directly: " + id);
    }
    if (entity instanceof MobEntity mob) {
      mob.initialize(
          world, world.getLocalDifficulty(entity.getBlockPos()), SpawnReason.COMMAND, null, null);
    }
    if (!world.spawnNewEntityAndPassengers(entity)) {
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
    NbtCompound compound = parseNbt(nbt);
    if (!compound.isEmpty()) {
      BlockEntity blockEntity = world.getBlockEntity(pos);
      if (blockEntity != null) {
        blockEntity.readNbt(compound);
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
    NbtCompound compound = parseNbt(nbt);
    if (!compound.isEmpty()) {
      stack.setNbt(compound);
    }
    ItemEntity itemEntity = new ItemEntity(world, x, y, z, stack);
    if (!world.spawnEntity(itemEntity)) {
      throw new IllegalStateException("failed to spawn item drop " + id);
    }
    return itemEntity.getId();
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
    StructureTemplateManager manager = world.getStructureTemplateManager();
    StructureTemplate template =
        manager
            .getTemplate(identifier)
            .orElseThrow(() -> new IllegalArgumentException("unknown structure: " + id));
    BlockPos pos = new BlockPos(x, y, z);
    StructurePlacementData placementData = new StructurePlacementData().setIgnoreEntities(true);
    return template.place(world, pos, pos, placementData, world.getRandom(), 2);
  }
}
