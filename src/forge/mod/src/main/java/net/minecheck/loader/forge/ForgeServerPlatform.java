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
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.template.PlacementSettings;
import net.minecraft.world.gen.structure.template.Template;
import net.minecraft.world.gen.structure.template.TemplateManager;
import cpw.mods.fml.common.registry.ForgeRegistries;

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

  private static <T extends net.minecraftforge.fml.common.registry.IForgeRegistryEntry<T>>
      T requireRegistryEntry(
          net.minecraftforge.fml.common.registry.IForgeRegistry<T> registry,
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
          line = LegacyCommandShim.translate(line);
          Integer rule = LegacyCommandShim.gameruleValue(s.getWorld(0), line);
          if (rule != null) {
            return rule;
          }
          Integer entity = LegacyCommandShim.executeIfEntity(s, line);
          if (entity != null) {
            return entity;
          }
          Integer data = LegacyCommandShim.dataGetEntity(s, line);
          if (data != null) {
            return data;
          }
          Integer tagged = LegacyCommandShim.tagCommand(s, line);
          if (tagged != null) {
            return tagged;
          }
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
    // Players keep their own list on 1.12.2: the first thing a script asks for is the player that
    // just joined, which is not in the loaded-entity list until the world ticks it.
    java.util.Map<Integer, Entity> entities = new java.util.LinkedHashMap<>();
    for (Entity entity : level.loadedEntityList) {
      entities.put(entity.getEntityId(), entity);
    }
    for (Entity entity : level.playerEntities) {
      entities.put(entity.getEntityId(), entity);
    }
    for (Entity entity : entities.values()) {
      // 1.12.2 keeps a removed entity in the list until the next tick, and a dying mob stays listed
      // for its death animation. Neither is something a client can observe as present.
      if (entity.isDead
          || (entity instanceof EntityLiving && ((EntityLiving) entity).getHealth() <= 0)) {
        continue;
      }
      ResourceLocation actual = EntityList.getKey(entity);
      if (actual == null && entity instanceof net.minecraft.entity.player.EntityPlayer) {
        // 1.12.2 has no registry entry for the player class, so the entity that joined first is the
        // one a script cannot name.
        actual = new ResourceLocation("minecraft", "player");
      }
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
          net.minecraft.util.BlockPos spawn = level.getSpawnPoint();
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
      applyAttributeBases((EntityLiving) entity, tag);
    }
    if (!level.spawnEntity(entity)) {
      throw new IllegalStateException("failed to spawn entity " + id);
    }
    return entity.getEntityId();
  }

  /**
   * Applies the attribute base values a script asked for at spawn. 1.12.2 reads only attribute
   * <em>modifiers</em> from an entity tag, so a base such as knockback resistance was dropped and
   * the punch target slid out from under the crosshair on the first hit.
   */
  private static void applyAttributeBases(EntityLiving entity, NBTTagCompound tag) {
    if (!tag.hasKey("Attributes", 9)) {
      return;
    }
    net.minecraft.nbt.NBTTagList list = tag.getTagList("Attributes", 10);
    for (int i = 0; i < list.tagCount(); i++) {
      NBTTagCompound entry = list.getCompoundTagAt(i);
      net.minecraft.entity.ai.attributes.IAttributeInstance instance =
          entity.getAttributeMap().getAttributeInstanceByName(entry.getString("Name"));
      if (instance != null && entry.hasKey("Base")) {
        instance.setBaseValue(entry.getDouble("Base"));
      }
    }
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
    MinecraftServer server = level.getMinecraftServer();
    Template template = null;
    for (String path : legacyTemplatePaths(location.getPath())) {
      template = loadTemplate(manager, server, new ResourceLocation(location.getNamespace(), path));
      if (template != null) {
        break;
      }
    }
    if (template == null) {
      // A dedicated server in a development run has no vanilla assets in its resource pack, so
      // every
      // template answers null there; the structure files still ship with the game.
      template = loadBundledTemplate(location);
    }
    if (template == null) {
      throw new IllegalArgumentException("unknown structure: " + id);
    }
    template.addBlocksToWorld(level, new BlockPos(x, y, z), new PlacementSettings(), 2);
    return true;
  }

  /**
   * The names a structure can go by on 1.12.2, given the name a fixture uses. 1.13 renamed the
   * structure files: the group's own name left the file ({@code igloo/igloo_top} became {@code
   * igloo/top}), and a part that used to be a directory became a flat id.
   */
  private static java.util.List<String> legacyTemplatePaths(String path) {
    java.util.List<String> out = new java.util.ArrayList<>();
    out.add(path);
    int slash = path.indexOf('/');
    if (slash > 0) {
      String group = path.substring(0, slash);
      out.add(group + "/" + group + "_" + path.substring(slash + 1));
    }
    out.add(path.replace('/', '_'));
    return out;
  }

  /** A template, or {@code null} when this line has none under that name. */
  private static Template loadTemplate(
      TemplateManager manager, MinecraftServer server, ResourceLocation id) {
    try {
      return manager.get(server, id);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** A structure's template read out of the game jar, or {@code null} when it is not there. */
  private static Template loadBundledTemplate(ResourceLocation location) {
    for (String path : legacyTemplatePaths(location.getPath())) {
      String asset = "/assets/" + location.getNamespace() + "/structures/" + path + ".nbt";
      for (Class<?> anchor : new Class<?>[] {MinecraftServer.class, ForgeServerPlatform.class}) {
        java.io.InputStream in = anchor.getResourceAsStream(asset);
        if (in == null) {
          in = openClasspathEntry(asset.substring(1));
        }
        if (in == null) {
          continue;
        }
        try (java.io.InputStream stream = in) {
          Template template = new Template();
          template.read(net.minecraft.nbt.CompressedStreamTools.readCompressed(stream));
          return template;
        } catch (java.io.IOException e) {
          return null;
        }
      }
    }
    return null;
  }

  /** An entry of whichever classpath jar carries it, or {@code null} when none does. */
  private static java.io.InputStream openClasspathEntry(String entryName) {
    String classpath = System.getProperty("java.class.path", "");
    for (String element : classpath.split(java.io.File.pathSeparator)) {
      try {
        java.nio.file.Path path = java.nio.file.Paths.get(element);
        if (!java.nio.file.Files.isRegularFile(path)) {
          continue;
        }
        final java.util.zip.ZipFile jar = new java.util.zip.ZipFile(path.toFile());
        java.util.zip.ZipEntry entry = jar.getEntry(entryName);
        if (entry == null) {
          jar.close();
          continue;
        }
        java.io.InputStream raw = jar.getInputStream(entry);
        return new java.io.FilterInputStream(raw) {
          @Override
          public void close() throws java.io.IOException {
            super.close();
            jar.close();
          }
        };
      } catch (RuntimeException | java.io.IOException e) {
        // Not a readable jar: the next classpath entry may have the file.
      }
    }
    return null;
  }
}
