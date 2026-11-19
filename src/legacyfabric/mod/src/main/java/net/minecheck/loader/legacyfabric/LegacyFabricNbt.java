package net.minecheck.loader.legacyfabric;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import net.minecraft.nbt.NbtLongArray;
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
    LEGACY_IDS.put("minecraft:oak_sign", "minecraft:standing_sign");
    LEGACY_IDS.put("minecraft:oak_wall_sign", "minecraft:wall_sign");
    LEGACY_IDS.put("minecraft:oak_planks", "minecraft:planks");
    MODERN_IDS.put("minecraft:standing_sign", "minecraft:oak_sign");
    MODERN_IDS.put("minecraft:wall_sign", "minecraft:oak_wall_sign");
    MODERN_IDS.put("minecraft:planks", "minecraft:oak_planks");
  }

  private LegacyFabricNbt() {}

  /** The id 1.12.2 knows a renamed block or item by. */
  static String legacyId(String id) {
    return LEGACY_IDS.getOrDefault(id, id);
  }

  /** The name the newer lines use, so one fixture can name a block and read it back. */
  static String modernId(String id) {
    return MODERN_IDS.getOrDefault(id, id);
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
    for (String key : compound.getKeys()) {
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
    if (element instanceof NbtByteArray
        || element instanceof NbtIntArray
        || element instanceof NbtLongArray) {
      return arrayValue(element);
    }
    if (element instanceof NbtList) {
      NbtList list = (NbtList) element;
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < list.size(); i++) {
        out.add(value(list.get(i)));
      }
      return out;
    }
    return null;
  }

  /** A numeric tag's value at its own width, so a byte reads back as a byte. */
  private static Object numericValue(NbtElement element) {
    String accessor;
    if (element instanceof NbtByte) {
      accessor = "byteValue";
    } else if (element instanceof NbtShort) {
      accessor = "shortValue";
    } else if (element instanceof NbtInt) {
      accessor = "intValue";
    } else if (element instanceof NbtLong) {
      accessor = "longValue";
    } else if (element instanceof NbtFloat) {
      accessor = "floatValue";
    } else if (element instanceof NbtDouble) {
      accessor = "doubleValue";
    } else {
      return null;
    }
    try {
      Method method = element.getClass().getMethod(accessor);
      method.setAccessible(true);
      return method.invoke(element);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
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
