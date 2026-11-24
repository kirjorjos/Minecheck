package net.minecheck.loader.legacyfabric;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtByteArray;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.nbt.NbtShort;
import net.minecraft.nbt.NbtString;

/**
 * NBT as the scripting layer sees it, and this line's names for things the newer lines renamed.
 *
 * <p>The map shape is the one {@code BlockInfo} and {@code SlotInfo} document — scalars, nested
 * maps, and arrays as lists of numbers — so a fixture's {@code nbt} reads are the same on every
 * loader.
 *
 * <p>The tag values are read reflectively: on this line the numeric tags inherit their accessors
 * from a package-private base class and the array tags keep their array in a private field, so
 * neither is reachable from here without going through the reflective path.
 */
final class LegacyFabricNbt {

  /** 1.13 names for blocks and items 1.12.2 only knows under the old id. */
  private static final Map<String, String> LEGACY_IDS = new HashMap<>();

  /** The reverse of {@link #LEGACY_IDS}: what a fixture should read back for an old id. */
  private static final Map<String, String> MODERN_IDS = new HashMap<>();

  static {
    // 1.4.7 has one sign block, named "Sign": the newer lines' standing and wall forms both
    // resolve to it, and it reads back as the oak sign a fixture names.
    LEGACY_IDS.put("minecraft:oak_sign", "minecraft:sign");
    LEGACY_IDS.put("minecraft:oak_wall_sign", "minecraft:sign");
    LEGACY_IDS.put("minecraft:oak_planks", "minecraft:planks");
    // This line's item name is "Wooden Planks", so a stack of planks reads back under that name.
    LEGACY_IDS.put("minecraft:wooden_planks", "minecraft:planks");
    MODERN_IDS.put("minecraft:sign", "minecraft:oak_sign");
    MODERN_IDS.put("minecraft:planks", "minecraft:oak_planks");
    MODERN_IDS.put("minecraft:wooden_planks", "minecraft:oak_planks");
  }

  private LegacyFabricNbt() {}

  /** The id 1.12.2 knows a renamed block or item by. */
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

  /**
   * 1.7.10's entity type names, keyed by the 1.13 id.
   *
   * <p>This line names a type in CamelCase and without a namespace ({@code Zombie}), and its type
   * table is keyed on that spelling, so an id has to be translated before an entity can be spawned
   * from NBT and translated back before one can be named in a script.
   */
  private static final Map<String, String> ENTITY_IDS = new HashMap<>();

  /** The reverse of {@link #ENTITY_IDS}. */
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

  /** The name this line's entity type table knows an id by, or {@code null} when it has none. */
  static String legacyEntityId(String modern) {
    return ENTITY_IDS.get(modern);
  }

  /** The id the newer lines use for a type this line names in CamelCase. */
  static String modernEntityId(String legacy) {
    String modern = MODERN_ENTITY_IDS.get(legacy);
    // A type this table does not list still has an id a script can compare: the lowercase of its
    // own name, which is how 1.12.2 derives the id the table lists explicitly.
    return modern != null ? modern : "minecraft:" + legacy.toLowerCase(java.util.Locale.ROOT);
  }

  /** Whether a tag holds a number, which is what a {@code data get} path can round. */
  static boolean isNumber(NbtElement element) {
    return element instanceof NbtByte
        || element instanceof NbtShort
        || element instanceof NbtInt
        || element instanceof NbtLong
        || element instanceof NbtFloat
        || element instanceof NbtDouble;
  }

  /** A numeric tag's value, or {@code 0} for anything else. */
  static double asDouble(NbtElement element) {
    Object value = numericValue(element);
    return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
  }

  /** A compound as the documented map shape. */
  static Map<String, Object> toMap(NbtCompound compound) {
    Map<String, Object> out = new HashMap<>();
    // This line's key set is raw: it is read as objects and each is used as the string it is.
    for (Object rawKey : keys(compound)) {
      String key = (String) rawKey;
      NbtElement element = compound.get(key);
      // A string is read through the compound, because this line's string tag keeps its value
      // private and exposes no accessor of its own.
      if (element instanceof NbtString) {
        out.put(key, compound.getString(key));
        continue;
      }
      out.put(key, value(element));
    }
    return out;
  }

  /** A compound's keys, from the map it keeps them in. */
  private static java.util.Set<?> keys(NbtCompound compound) {
    try {
      Field field = NbtCompound.class.getDeclaredField("data");
      field.setAccessible(true);
      Object data = field.get(compound);
      return data instanceof Map ? ((Map<?, ?>) data).keySet() : java.util.Collections.emptySet();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return java.util.Collections.emptySet();
    }
  }

  private static Object value(NbtElement element) {
    if (element == null) {
      return null;
    }
    if (element instanceof NbtCompound) {
      return toMap((NbtCompound) element);
    }
    if (isNumber(element)) {
      return numericValue(element);
    }
    if (element instanceof NbtByteArray || element instanceof NbtIntArray) {
      return arrayValue(element);
    }
    if (element instanceof NbtList) {
      NbtList list = (NbtList) element;
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < list.size(); i++) {
        out.add(value(elementAt(list, i)));
      }
      return out;
    }
    return null;
  }

  /** A numeric tag's value at its own width, so a byte reads back as a byte. */
  private static Object numericValue(NbtElement element) {
    if (!isNumber(element)) {
      return null;
    }
    // This line's numeric tags keep the value in a public field of the tag's own width type, and
    // expose no accessor method for it.
    try {
      return element.getClass().getField("value").get(element);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /**
   * The element at an index of a list tag.
   *
   * <p>This line's list exposes no index accessor at all — it answers by type — so the element is
   * read out of the list's own backing {@code value} field, which is what the array tags need the
   * same reflective route for.
   */
  private static NbtElement elementAt(NbtList list, int index) {
    try {
      Field field = NbtList.class.getDeclaredField("value");
      field.setAccessible(true);
      Object values = field.get(list);
      if (values instanceof List && index < ((List<?>) values).size()) {
        Object element = ((List<?>) values).get(index);
        return element instanceof NbtElement ? (NbtElement) element : null;
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
    return null;
  }

  /** An array tag as a list of numbers, from the tag's own backing array. */
  private static List<Object> arrayValue(NbtElement element) {
    List<Object> out = new ArrayList<>();
    try {
      Field field = element.getClass().getDeclaredField("value");
      field.setAccessible(true);
      Object array = field.get(element);
      if (array == null) {
        return out;
      }
      for (int i = 0; i < Array.getLength(array); i++) {
        out.add(Array.get(array, i));
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      return out;
    }
    return out;
  }
}
