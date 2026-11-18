package net.minecheck.loader.forge;

import java.util.function.Supplier;
import net.minecheck.platform.api.ServerPlatform;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.template.PlacementSettings;
import net.minecraft.world.gen.structure.template.Template;
import net.minecraft.world.gen.structure.template.TemplateManager;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

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
    if (s.isCallingFromMinecraftThread()) {
      return action.get();
    }
    java.util.concurrent.CompletableFuture<T> done = new java.util.concurrent.CompletableFuture<>();
    s.addScheduledTask(
        () -> {
          try {
            done.complete(action.get());
          } catch (Throwable t) {
            done.completeExceptionally(t);
          }
        });
    return done.join();
  }

  private WorldServer overworld() {
    WorldServer level = requireServer().getWorld(0);
    if (level == null) {
      throw new IllegalStateException("overworld not available yet");
    }
    return level;
  }

  /**
   * 1.12.2 resource locations are strict: they must be lower-case and non-empty. Trim and lower the
   * id first, so {@code minecraft:Gold_Block} is a name error and not a crash in the parser.
   */
  private static ResourceLocation parseId(String id, String what) {
    if (id == null) {
      throw new IllegalArgumentException("invalid " + what + " id: null");
    }
    String trimmed = id.trim().toLowerCase(java.util.Locale.ROOT);
    try {
      return new ResourceLocation(trimmed);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid " + what + " id: " + id, e);
    }
  }

  private static NBTTagCompound parseTag(String nbt) {
    if (nbt == null || nbt.isEmpty()) {
      return new NBTTagCompound();
    }
    try {
      return JsonToNBT.getTagFromJson(nbt);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid NBT: " + e.getMessage(), e);
    }
  }

  private static <T extends net.minecraftforge.registries.IForgeRegistryEntry<T>>
      T requireRegistryEntry(
          net.minecraftforge.registries.IForgeRegistry<T> registry,
          ResourceLocation id,
          String what) {
    T entry = registry.getValue(id);
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
          Integer conditional = LegacyCommandShim.executeIfBlock(s.getWorld(0), line);
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
    return onServerThread(() -> s.getPlayerList().getPlayerByUsername(name) != null);
  }

  @Override
  public void applyDeterministicRules() {
    onServerThread(
        () -> {
          WorldServer level = overworld();
          net.minecraft.world.GameRules rules = level.getGameRules();
          // 1.12.2 has no insomnia rule; it arrives in 1.15, and mob spawning is off anyway.
          rules.setOrCreateGameRule("doMobSpawning", "false");
          rules.setOrCreateGameRule("doDaylightCycle", "false");
          rules.setOrCreateGameRule("doWeatherCycle", "false");
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
    ResourceLocation typeId = type == null ? null : parseId(type, "entity type");
    WorldServer level = overworld();
    java.util.List<net.minecheck.platform.api.EntityInfo> out = new java.util.ArrayList<>();
    for (Object raw : level.loadedEntityList) {
      Entity entity = (Entity) raw;
      // 1.12.2 keeps a removed entity in the list until the next tick, and a dying mob stays listed
      // for its death animation. Neither is something a client can observe as present.
      if (entity.isDead
          || (entity instanceof EntityLiving && ((EntityLiving) entity).getHealth() <= 0)) {
        continue;
      }
      ResourceLocation actual = EntityList.getKey(entity);
      if (typeId != null && !typeId.equals(actual)) {
        continue;
      }
      if (tag != null && !entity.getTags().contains(tag)) {
        continue;
      }
      out.add(
          new net.minecheck.platform.api.EntityInfo(
              entity.getEntityId(),
              actual == null ? "" : actual.toString(),
              entity.posX,
              entity.posY,
              entity.posZ,
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
          WorldServer level = s.getWorld(0);
          if (level == null) {
            return false;
          }
          // The chunk the level spawned in, not chunk 0,0: a 1.12.2 world spawns its player away
          // from the origin and keeps only the spawn area loaded, so 0,0 is generated but unloaded
          // until a script teleports there and waits for it.
          net.minecraft.util.math.BlockPos spawn = level.getSpawnPoint();
          return level.getChunkProvider().chunkExists(spawn.getX() >> 4, spawn.getZ() >> 4);
        });
  }

  @Override
  public Integer groundHeight(int x, int z) {
    return onServerThread(
        () -> {
          WorldServer level = overworld();
          // Asked of the chunk, the same way `isChunkLoaded` answers: "does this column have a
          // height" is a property of the loaded chunk, not of one block in it.
          if (!level.getChunkProvider().chunkExists(x >> 4, z >> 4)) {
            return null;
          }
          // 1.12.2's heightmap already reports the first air block above the surface, which is
          // the height the newer lines get from MOTION_BLOCKING plus one.
          return level.getHeight(x, z);
        });
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
          WorldServer level = overworld();
          // Reading the chunk through makes the load synchronous rather than a later tick. 1.12.2
          // has no per-chunk forced ticket exposed without Forge's ticket manager, so the load is
          // not held against unload the way `forceload add` holds it.
          level.getChunk(chunkX, chunkZ);
          return level.getChunkProvider().chunkExists(chunkX, chunkZ);
        });
  }

  @Override
  public int spawnEntity(String id, double x, double y, double z, String nbt) {
    return onServerThread(() -> spawnEntityOnServerThread(id, x, y, z, nbt));
  }

  private int spawnEntityOnServerThread(String id, double x, double y, double z, String nbt) {
    ResourceLocation location = parseId(id, "entity type");
    requireRegistryEntry(ForgeRegistries.ENTITIES, location, "entity type");
    WorldServer level = overworld();
    NBTTagCompound tag = parseTag(nbt);
    tag.setString("id", location.toString());
    Entity entity = EntityList.createEntityFromNBT(tag, level);
    if (entity == null) {
      throw new IllegalArgumentException("entity type cannot be spawned directly: " + id);
    }
    entity.setPosition(x, y, z);
    if (entity instanceof EntityLiving) {
      ((EntityLiving) entity)
          .onInitialSpawn(level.getDifficultyForLocation(new BlockPos(entity)), null);
    }
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
    ResourceLocation location = parseId(id, "block");
    Block block = requireRegistryEntry(ForgeRegistries.BLOCKS, location, "block");
    WorldServer level = overworld();
    BlockPos pos = new BlockPos(x, y, z);
    if (!replace && !level.isAirBlock(pos)) {
      return false;
    }
    IBlockState state = block.getDefaultState();
    if (!level.setBlockState(pos, state, 3)) {
      return false;
    }
    NBTTagCompound tag = parseTag(nbt);
    if (!tag.getKeySet().isEmpty()) {
      TileEntity tile = level.getTileEntity(pos);
      if (tile != null) {
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tile.readFromNBT(tag);
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
    ResourceLocation location = parseId(id, "item");
    Item item = requireRegistryEntry(ForgeRegistries.ITEMS, location, "item");
    WorldServer level = overworld();
    ItemStack stack = new ItemStack(item, count);
    NBTTagCompound tag = parseTag(nbt);
    if (!tag.getKeySet().isEmpty()) {
      stack.setTagCompound(tag);
    }
    EntityItem itemEntity = new EntityItem(level, x, y, z, stack);
    if (!level.spawnEntity(itemEntity)) {
      throw new IllegalStateException("failed to spawn item drop " + id);
    }
    return itemEntity.getEntityId();
  }

  @Override
  public boolean spawnStructure(String id, int x, int y, int z) {
    return onServerThread(() -> spawnStructureOnServerThread(id, x, y, z));
  }

  private boolean spawnStructureOnServerThread(String id, int x, int y, int z) {
    ResourceLocation location = parseId(id, "structure");
    WorldServer level = overworld();
    TemplateManager manager = level.getStructureTemplateManager();
    Template template = manager.get(level.getMinecraftServer(), location);
    if (template == null) {
      throw new IllegalArgumentException("unknown structure: " + id);
    }
    template.addBlocksToWorld(level, new BlockPos(x, y, z), new PlacementSettings(), 2);
    return true;
  }
}
