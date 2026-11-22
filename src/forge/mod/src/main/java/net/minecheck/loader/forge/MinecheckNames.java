package net.minecheck.loader.forge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/**
 * NBT as the scripting layer sees it, and this line's names for things the newer lines renamed.
 *
 * <p>The map shape is the one {@code BlockInfo} and {@code SlotInfo} document — scalars, nested
 * maps, and arrays as lists of numbers — so a fixture's {@code nbt} reads are the same on every
 * loader.
 *
 * <p>This is the same table the Legacy Fabric module carries: 1.7.10 is one game, and a script that
 * names {@code minecraft:oak_sign} or {@code minecraft:zombie} has to resolve to the same block and
 * the same entity on both loaders.
 */
final class MinecheckNames {

  /** 1.13 names for blocks and items this line only knows under the old id. */
  private static final Map<String, String> LEGACY_IDS = new HashMap<>();

  /** The reverse of {@link #LEGACY_IDS}: what a fixture should read back for an old id. */
  private static final Map<String, String> MODERN_IDS = new HashMap<>();

  static {
    LEGACY_IDS.put("minecraft:oak_sign", "minecraft:standing_sign");
    LEGACY_IDS.put("minecraft:oak_wall_sign", "minecraft:wall_sign");
    LEGACY_IDS.put("minecraft:oak_planks", "minecraft:planks");
    MODERN_IDS.put("minecraft:standing_sign", "minecraft:oak_sign");
    MODERN_IDS.put("minecraft:wall_sign", "minecraft:oak_wall_sign");
    MODERN_IDS.put("minecraft:planks", "minecraft:oak_planks");
  }

  /**
   * The entity type names this line keys its table on, by the 1.13 id.
   *
   * <p>1.7.10 names a type in CamelCase and without a namespace ({@code Zombie}), so an id is
   * translated before an entity is spawned from NBT and translated back before one is named in a
   * script.
   */
  private static final Map<String, String> ENTITY_IDS = new HashMap<>();

  private static final Map<String, String> MODERN_ENTITY_IDS = new HashMap<>();

  static {
    entity("minecraft:item", "Item");
    entity("minecraft:xp_orb", "XPOrb");
    entity("minecraft:leash_knot", "LeashKnot");
    entity("minecraft:painting", "Painting");
    entity("minecraft:arrow", "Arrow");
    entity("minecraft:snowball", "Snowball");
    entity("minecraft:fireball", "Fireball");
    entity("minecraft:small_fireball", "SmallFireball");
    entity("minecraft:ender_pearl", "ThrownEnderpearl");
    entity("minecraft:eye_of_ender", "EyeOfEnderSignal");
    entity("minecraft:potion", "ThrownPotion");
    entity("minecraft:experience_bottle", "ThrownExpBottle");
    entity("minecraft:item_frame", "ItemFrame");
    entity("minecraft:wither_skull", "WitherSkull");
    entity("minecraft:tnt", "PrimedTnt");
    entity("minecraft:falling_block", "FallingSand");
    entity("minecraft:fireworks_rocket", "FireworksRocketEntity");
    entity("minecraft:boat", "Boat");
    entity("minecraft:minecart", "MinecartRideable");
    entity("minecraft:chest_minecart", "MinecartChest");
    entity("minecraft:furnace_minecart", "MinecartFurnace");
    entity("minecraft:tnt_minecart", "MinecartTNT");
    entity("minecraft:hopper_minecart", "MinecartHopper");
    entity("minecraft:spawner_minecart", "MinecartSpawner");
    entity("minecraft:commandblock_minecart", "MinecartCommandBlock");
    entity("minecraft:creeper", "Creeper");
    entity("minecraft:skeleton", "Skeleton");
    entity("minecraft:spider", "Spider");
    entity("minecraft:giant", "Giant");
    entity("minecraft:zombie", "Zombie");
    entity("minecraft:slime", "Slime");
    entity("minecraft:ghast", "Ghast");
    entity("minecraft:zombie_pigman", "PigZombie");
    entity("minecraft:enderman", "Enderman");
    entity("minecraft:cave_spider", "CaveSpider");
    entity("minecraft:silverfish", "Silverfish");
    entity("minecraft:blaze", "Blaze");
    entity("minecraft:magma_cube", "LavaSlime");
    entity("minecraft:ender_dragon", "EnderDragon");
    entity("minecraft:wither", "WitherBoss");
    entity("minecraft:witch", "Witch");
    entity("minecraft:bat", "Bat");
    entity("minecraft:pig", "Pig");
    entity("minecraft:sheep", "Sheep");
    entity("minecraft:cow", "Cow");
    entity("minecraft:chicken", "Chicken");
    entity("minecraft:squid", "Squid");
    entity("minecraft:wolf", "Wolf");
    entity("minecraft:mooshroom", "MushroomCow");
    entity("minecraft:snow_golem", "SnowMan");
    entity("minecraft:ocelot", "Ozelot");
    entity("minecraft:iron_golem", "VillagerGolem");
    entity("minecraft:horse", "Horse");
    entity("minecraft:villager", "Villager");
    entity("minecraft:end_crystal", "EnderCrystal");
  }

  private static void entity(String modern, String legacy) {
    ENTITY_IDS.put(modern, legacy);
    MODERN_ENTITY_IDS.put(legacy, modern);
  }

  private MinecheckNames() {}

  /** The id this line knows a renamed block or item by. */
  static String legacyId(String id) {
    return LEGACY_IDS.getOrDefault(id, id);
  }

  /**
   * Every rename, keyed by the 1.13 name.
   *
   * <p>Exposed so a command can have each one replaced in place: a rename applies to the id inside
   * a command, not to the command as a whole.
   */
  static Map<String, String> legacyAliases() {
    return LEGACY_IDS;
  }

  /** The name the newer lines use, so one fixture can name a block and read it back. */
  static String modernId(String id) {
    return MODERN_IDS.getOrDefault(id, id);
  }

  /** The name this line's entity type table knows an id by, or {@code null} when it has none. */
  static String legacyEntityId(String modern) {
    return ENTITY_IDS.get(modern);
  }

  /** The id the newer lines use for a type this line names in CamelCase. */
  static String modernEntityId(String legacy) {
    String modern = MODERN_ENTITY_IDS.get(legacy);
    // A type this table does not list still has an id a script can compare: the lowercase of its
    // own name, which is how the newer lines derive the id the table lists explicitly.
    return modern != null ? modern : "minecraft:" + legacy.toLowerCase(java.util.Locale.ROOT);
  }

  /** Whether a tag holds a number, which is what a {@code data get} path can round. */
  static boolean isNumber(NBTBase base) {
    int id = base.getId();
    return id >= 1 && id <= 6;
  }

  /** A numeric tag's value, or {@code 0} for anything else. */
  static double asDouble(NBTBase base) {
    return base instanceof NBTPrimitive ? ((NBTPrimitive) base).getDouble() : 0.0;
  }

  /** A compound as the documented map shape. */
  static Map<String, Object> toMap(NBTTagCompound compound) {
    Map<String, Object> out = new HashMap<>();
    for (Object rawKey : compound.getKeySet()) {
      String key = (String) rawKey;
      out.put(key, value(compound.getTag(key)));
    }
    return out;
  }

  private static Object value(NBTBase base) {
    if (base == null) {
      return null;
    }
    switch (base.getId()) {
      case 1:
        return ((NBTPrimitive) base).getByte();
      case 2:
        return ((NBTPrimitive) base).getShort();
      case 3:
        return ((NBTPrimitive) base).getInt();
      case 4:
        return ((NBTPrimitive) base).getLong();
      case 5:
        return ((NBTPrimitive) base).getFloat();
      case 6:
        return ((NBTPrimitive) base).getDouble();
      case 7:
        return numbers(((NBTTagByteArray) base).getByteArray());
      case 8:
        return ((NBTTagString) base).getString();
      case 9:
        return listValues((NBTTagList) base);
      case 10:
        return toMap((NBTTagCompound) base);
      case 11:
        return numbers(((NBTTagIntArray) base).getIntArray());
      default:
        return null;
    }
  }

  private static List<Object> listValues(NBTTagList list) {
    List<Object> out = new ArrayList<>();
    for (int i = 0; i < list.tagCount(); i++) {
      out.add(value(elementAt(list, i)));
    }
    return out;
  }

  /**
   * The element at an index of a list tag.
   *
   * <p>This line's list exposes no untyped index accessor — it answers by type — so the element is
   * read out of the list's own backing list field, which is found by its type rather than by a name
   * that a mapping can change.
   */
  private static NBTBase elementAt(NBTTagList list, int index) {
    try {
      for (java.lang.reflect.Field field : NBTTagList.class.getDeclaredFields()) {
        if (!java.util.List.class.isAssignableFrom(field.getType())) {
          continue;
        }
        field.setAccessible(true);
        Object values = field.get(list);
        if (values instanceof List && index < ((List<?>) values).size()) {
          Object element = ((List<?>) values).get(index);
          return element instanceof NBTBase ? (NBTBase) element : null;
        }
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
    return null;
  }

  private static List<Object> numbers(byte[] values) {
    List<Object> out = new ArrayList<>(values.length);
    for (byte value : values) {
      out.add(value);
    }
    return out;
  }

  private static List<Object> numbers(int[] values) {
    List<Object> out = new ArrayList<>(values.length);
    for (int value : values) {
      out.add(value);
    }
    return out;
  }
}
