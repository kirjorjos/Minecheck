package net.minecheck.loader.forge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
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
 * <p>The 1.13 names are the ones every line shares, so a script writes one spelling of a block; the
 * legacy side of the table is this line's own, and it is not 1.7.10's: 1.4.7 has one {@code sign}
 * block rather than {@code standing_sign}/{@code wall_sign}, and calls the plank block {@code
 * wood}.
 */
final class MinecheckNames {

  /** 1.13 names for blocks and items this line only knows under the old id. */
  private static final Map<String, String> LEGACY_IDS = new HashMap<>();

  /** The reverse of {@link #LEGACY_IDS}: what a fixture should read back for an old id. */
  private static final Map<String, String> MODERN_IDS = new HashMap<>();

  static {
    // One sign block stands for both, and the plank block is `wood`.
    LEGACY_IDS.put("minecraft:oak_sign", "minecraft:sign");
    LEGACY_IDS.put("minecraft:oak_wall_sign", "minecraft:sign");
    LEGACY_IDS.put("minecraft:oak_planks", "minecraft:wood");
    // The crafting table is `workbench` here, and a block a script names has to resolve or the
    // `/setblock` that places it silently leaves air behind.
    LEGACY_IDS.put("minecraft:crafting_table", "minecraft:workbench");
    MODERN_IDS.put("minecraft:sign", "minecraft:oak_sign");
    MODERN_IDS.put("minecraft:wood", "minecraft:oak_planks");
    MODERN_IDS.put("minecraft:workbench", "minecraft:crafting_table");
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
    String mapped = MODERN_IDS.get(id);
    if (mapped != null) {
      return mapped;
    }
    // This line names a thing in reverse word order (`blockIron`, `swordIron`, `helmetDiamond`),
    // where the newer lines write the words in order in snake_case. A one-word name is already its
    // own id, so only the multi-word ones are rewritten — without this a fixture that placed an
    // iron block read back `blockIron` and compared it against `minecraft:iron_block`.
    String bare = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
    String[] words = bare.replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim().split("\\s+");
    if (words.length < 2) {
      return id;
    }
    StringBuilder out = new StringBuilder("minecraft:");
    for (int i = words.length - 1; i >= 0; i--) {
      if (out.length() > "minecraft:".length()) {
        out.append('_');
      }
      out.append(words[i].toLowerCase(java.util.Locale.ROOT));
    }
    return out.toString();
  }

  /** This line's block for a legacy id, or {@code null} when it has none. */
  static Block block(String id) {
    String wanted = legacyId(id);
    for (Block block : Block.blocksList) {
      if (block != null && gameId(block.getBlockName()).equals(wanted)) {
        return block;
      }
    }
    return blocksByName().get(normalized(wanted));
  }

  /** This line's item for a legacy id, or {@code null} when it has none. */
  static Item item(String id) {
    String wanted = legacyId(id);
    for (Item item : Item.itemsList) {
      if (item != null && gameId(item.getItemName()).equals(wanted)) {
        return item;
      }
    }
    return itemsByName().get(normalized(wanted));
  }

  /**
   * Every block, by the spellings its name can be written in.
   *
   * <p>This line names a thing in <em>reverse</em> word order — {@code swordIron}, {@code
   * blockIron}, {@code helmetDiamond} — where a script names it the way 1.13 does: {@code
   * minecraft:iron_sword}. An exact match therefore misses most items, which is what made a `/give`
   * of a named sword reach 1.4.7's parser and be answered "is not a valid number". Built on first
   * use, after every block and item has been registered.
   */
  private static Map<String, Block> blocksByName;

  private static Map<String, Item> itemsByName;

  private static Map<String, Block> blocksByName() {
    synchronized (MinecheckNames.class) {
      if (blocksByName == null) {
        Map<String, Block> index = new HashMap<>();
        for (Block block : Block.blocksList) {
          if (block != null) {
            spellings(index, gameId(block.getBlockName()), block);
          }
        }
        blocksByName = index;
      }
      return blocksByName;
    }
  }

  private static Map<String, Item> itemsByName() {
    synchronized (MinecheckNames.class) {
      if (itemsByName == null) {
        Map<String, Item> index = new HashMap<>();
        for (Item item : Item.itemsList) {
          if (item != null) {
            spellings(index, gameId(item.getItemName()), item);
          }
        }
        itemsByName = index;
      }
      return itemsByName;
    }
  }

  /** Puts a value under both spellings a name can be written in. */
  private static <T> void spellings(Map<String, T> index, String id, T value) {
    // No `putIfAbsent`: this module is downgraded to Java 7 bytecode, where that Map method is a
    // stub (see RunResult.passed).
    put(index, normalized(id), value);
    String bare = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
    String[] words =
        bare.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').trim().split("\\s+");
    StringBuilder reversed = new StringBuilder(bare.length());
    for (int i = words.length - 1; i >= 0; i--) {
      reversed.append(words[i]);
    }
    put(index, normalized(reversed.toString()), value);
  }

  private static <T> void put(Map<String, T> index, String key, T value) {
    if (!index.containsKey(key)) {
      index.put(key, value);
    }
  }

  /**
   * A name with its namespace, punctuation and case removed: {@code minecraft:iron_sword}, {@code
   * swordIron} and {@code Iron Sword} all answer {@code ironsword}.
   */
  private static String normalized(String id) {
    String bare = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
    StringBuilder out = new StringBuilder(bare.length());
    for (int i = 0; i < bare.length(); i++) {
      char c = bare.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        out.append(Character.toLowerCase(c));
      }
    }
    return out.toString();
  }

  /** The modern id of a block, from this line's name for it. */
  static String blockId(Block block) {
    return modernId(gameId(block.getBlockName()));
  }

  /** The modern id of an item stack, from this line's name for its item. */
  static String itemId(ItemStack stack) {
    return modernId(gameId(stack.getItem().getItemName()));
  }

  /**
   * This line's unlocalized name as an id: {@code tile.stone} and {@code item.apple} both drop the
   * prefix, and both become {@code minecraft:}-namespaced (the pre-1.6 id space is one namespace).
   */
  private static String gameId(String name) {
    // Some of this line's items answer no name at all (a music disc is the one that does), and the
    // id tables are walked in full to resolve a name — so a null here used to be a
    // NullPointerException out of `/give`, answered to the script as "runCommand:
    // NullPointerException" with nothing in it about the item. No name matches nothing.
    if (name == null) {
      return "";
    }
    int dot = name.indexOf('.');
    String bare = dot >= 0 ? name.substring(dot + 1) : name;
    return bare.indexOf(':') >= 0 ? bare : "minecraft:" + bare;
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
    if (base instanceof net.minecraft.nbt.NBTTagByte) {
      return ((net.minecraft.nbt.NBTTagByte) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagShort) {
      return ((net.minecraft.nbt.NBTTagShort) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagInt) {
      return ((net.minecraft.nbt.NBTTagInt) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagLong) {
      return ((net.minecraft.nbt.NBTTagLong) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagFloat) {
      return ((net.minecraft.nbt.NBTTagFloat) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagDouble) {
      return ((net.minecraft.nbt.NBTTagDouble) base).data;
    }
    return 0.0;
  }

  /** A compound as the documented map shape. */
  static Map<String, Object> toMap(NBTTagCompound compound) {
    Map<String, Object> out = new HashMap<>();
    // This line's `getTags` hands back the tags themselves rather than their keys, which the newer
    // lines' same-named method returns: reading a key off the collection was a cast of a numeric
    // tag to String (the beacon's levels) and took the whole `getBlock` call down. Each tag carries
    // its own name, so the key comes from the tag.
    for (Object raw : compound.getTags()) {
      NBTBase tag = (NBTBase) raw;
      out.put(tag.getName(), value(tag));
    }
    return out;
  }

  private static Object value(NBTBase base) {
    if (base == null) {
      return null;
    }
    if (base instanceof net.minecraft.nbt.NBTTagByte) {
      return ((net.minecraft.nbt.NBTTagByte) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagShort) {
      return ((net.minecraft.nbt.NBTTagShort) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagInt) {
      return ((net.minecraft.nbt.NBTTagInt) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagLong) {
      return ((net.minecraft.nbt.NBTTagLong) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagFloat) {
      return ((net.minecraft.nbt.NBTTagFloat) base).data;
    }
    if (base instanceof net.minecraft.nbt.NBTTagDouble) {
      return ((net.minecraft.nbt.NBTTagDouble) base).data;
    }
    if (base instanceof NBTTagByteArray) {
      return numbers(((NBTTagByteArray) base).byteArray);
    }
    if (base instanceof NBTTagString) {
      return ((NBTTagString) base).data;
    }
    if (base instanceof NBTTagList) {
      return listValues((NBTTagList) base);
    }
    if (base instanceof NBTTagCompound) {
      return toMap((NBTTagCompound) base);
    }
    if (base instanceof NBTTagIntArray) {
      return numbers(((NBTTagIntArray) base).intArray);
    }
    return null;
  }

  private static List<Object> listValues(NBTTagList list) {
    List<Object> out = new ArrayList<>();
    for (int i = 0; i < list.tagCount(); i++) {
      out.add(value(elementAt(list, i)));
    }
    return out;
  }

  /** The element at an index of a list tag. */
  private static NBTBase elementAt(NBTTagList list, int index) {
    return list.tagAt(index);
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
