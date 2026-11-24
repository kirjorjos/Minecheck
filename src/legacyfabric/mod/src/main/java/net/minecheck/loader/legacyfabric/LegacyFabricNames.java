package net.minecheck.loader.legacyfabric;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * The name side of 1.4.7's numeric block and item tables.
 *
 * <p>This line has no registry objects: a block is a slot in {@link Block#BLOCKS} and an item a
 * slot in {@link Item#ITEMS}, and the name a script uses is the unlocalized one ({@code
 * tile.stone}, {@code item.apple}). The lookups here translate between the two, so the rest of the
 * module can keep speaking the ids the Lua API documents.
 */
final class LegacyFabricNames {

  private LegacyFabricNames() {}

  /** This line's block for a modern id, or {@code null} when it has none. */
  static Block block(String id) {
    String wanted = gameId(LegacyFabricNbt.legacyId(id));
    // Air has no item form, so it has no name to look up in the table below.
    String bare = wanted.contains(":") ? wanted.substring(wanted.indexOf(':') + 1) : wanted;
    if (bare.equals("air")) {
      return Block.BLOCKS[0];
    }
    // This line has one sign block, id 63, and its name is just "Sign": the newer lines' standing
    // and wall forms both resolve to it.
    if (bare.equals("sign") || bare.equals("standing_sign")) {
      return Block.BLOCKS[63];
    }
    for (Block block : Block.BLOCKS) {
      if (block != null && gameId(nameOf(block)).equals(wanted)) {
        return block;
      }
    }
    return null;
  }

  /** This line's item for a modern id, or {@code null} when it has none. */
  static Item item(String id) {
    String wanted = gameId(LegacyFabricNbt.legacyId(id));
    for (Item item : Item.ITEMS) {
      if (item == null) {
        continue;
      }
      // This line names items for people ("Wooden Planks"), so the name is also compared after
      // the legacy alias pass: `wooden_planks` is the old name of `planks`.
      String name = gameId(item.getName());
      if (name.equalsIgnoreCase(wanted)
          || LegacyFabricNbt.legacyId(name).equalsIgnoreCase(wanted)) {
        return item;
      }
    }
    return null;
  }

  /** The modern id of a block, from this line's name for it. */
  static String blockId(Block block) {
    return block == null ? "minecraft:air" : LegacyFabricNbt.modernId(gameId(nameOf(block)));
  }

  /** The modern id of an item stack, from this line's name for its item. */
  static String itemId(ItemStack stack) {
    return LegacyFabricNbt.modernId(gameId(stack.getItem().getName()));
  }

  /**
   * A block's unlocalized name.
   *
   * <p>Blocks carry no name accessor on this line. The block's own item does — it is how the item
   * form answers its name — and every block a script can place has one, so that is read first. A
   * block without an item form falls back to the single String field vanilla keeps beside the id.
   */
  private static String nameOf(Block block) {
    if (block.id >= 0 && block.id < Item.ITEMS.length && Item.ITEMS[block.id] != null) {
      return Item.ITEMS[block.id].getName();
    }
    try {
      for (Field field : Block.class.getDeclaredFields()) {
        if (field.getType() != String.class) {
          continue;
        }
        field.setAccessible(true);
        Object value = field.get(block);
        if (value instanceof String
            && ((String) value).matches("(tile\\.)?[a-z0-9_]+")
            && !((String) value).startsWith("tile.")) {
          return (String) value;
        }
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Fall through to the display name; it is only a last resort, not the id path.
    }
    return block.getTranslatedName();
  }

  /**
   * Places a block with metadata 0 and the notify flags vanilla uses for a scripted placement.
   *
   * <p>This line's world does not expose its placement method under a mapped name, so it is found
   * by signature: one of the two shapes 1.4.7 writes blocks with, both taking the numeric block id.
   * The first boolean answer wins; a void method counts as placed, which is what vanilla's own
   * placement helpers return.
   */
  static boolean setBlock(World world, int x, int y, int z, Block block) {
    // The one write that both stores the block and tells clients about it. The five-int overload
    // beside it only stores it, so a placed block would stay invisible until a later chunk reload.
    return markModified(world, x, z, world.method_3601(x, y, z, block.id, 0, true));
  }

  /**
   * Marks the chunk a write landed in as changed, which is the flag this line saves.
   *
   * <p>Its block write does not set the flag itself and its save pass takes only modified chunks,
   * so a block a script placed was in memory but missing from the save handed to another process.
   */
  static boolean markModified(World world, int x, int z, boolean written) {
    Chunk chunk = world.getChunkProvider().getOrGenerateChunk(x >> 4, z >> 4);
    if (chunk != null) {
      chunk.modified = true;
    }
    return written;
  }

  /** Marks the chunk a write through {@link World#method_3601} landed in. */
  static void markModified(World world, int x, int z) {
    markModified(world, x, z, true);
  }

  private static Method methodWith(Class<?> owner, Class<?>... parameters) {
    for (Method method : owner.getMethods()) {
      if (java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
        return method;
      }
    }
    return null;
  }

  /**
   * This line's unlocalized name as an id: {@code tile.stone}/{@code item.apple} drop the prefix.
   */
  private static String gameId(String name) {
    int dot = name.indexOf('.');
    String bare =
        (dot >= 0 ? name.substring(dot + 1) : name)
            .trim()
            .toLowerCase(java.util.Locale.ROOT)
            .replace(' ', '_');
    // This line calls the block of a material "Block of Emerald"; the modern id puts the material
    // first. It is the one shape the display name does not translate word for word.
    if (bare.startsWith("block_of_")) {
      bare = bare.substring("block_of_".length()) + "_block";
    }
    return bare.indexOf(':') >= 0 ? bare : "minecraft:" + bare;
  }
}
