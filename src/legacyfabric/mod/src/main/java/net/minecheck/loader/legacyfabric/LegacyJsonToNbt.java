package net.minecheck.loader.legacyfabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/**
 * Reads the JSON-shaped NBT a script writes into a {@link NbtCompound}.
 *
 * <p>This line has no {@code StringNbtReader}, so the reader is carried here. The shape is the one
 * the Lua API documents: an object is a compound, an array is a list, booleans are NBT bytes,
 * integral numbers are ints (or longs when they do not fit), fractional numbers are doubles, and
 * everything else is a string.
 */
final class LegacyJsonToNbt {

  private LegacyJsonToNbt() {}

  /**
   * Parses the command-line tag syntax a {@code /give} carries, which is SNBT, not JSON.
   *
   * <p>The two differ only in that SNBT leaves object keys unquoted, so the keys are quoted first
   * and the JSON reader below does the rest. A quoted string keeps its own escaping, so text inside
   * a value is untouched.
   */
  static NbtCompound parseSnbt(String text) {
    return parse(text.replaceAll("([{,])\\s*([A-Za-z0-9_\\-]+)\\s*:", "$1\"$2\":"));
  }

  static NbtCompound parse(String text) {
    JsonElement root = new JsonParser().parse(text);
    if (root == null || !root.isJsonObject()) {
      throw new IllegalArgumentException("NBT must be a JSON object");
    }
    return compound(root.getAsJsonObject());
  }

  private static NbtCompound compound(JsonObject object) {
    NbtCompound tag = new NbtCompound();
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      put(tag, entry.getKey(), entry.getValue());
    }
    return tag;
  }

  private static void put(NbtCompound tag, String key, JsonElement value) {
    if (value.isJsonObject()) {
      tag.put(key, compound(value.getAsJsonObject()));
      return;
    }
    if (value.isJsonArray()) {
      tag.put(key, list(value.getAsJsonArray()));
      return;
    }
    JsonPrimitive primitive = value.getAsJsonPrimitive();
    if (primitive.isBoolean()) {
      tag.putBoolean(key, primitive.getAsBoolean());
      return;
    }
    if (primitive.isString()) {
      tag.putString(key, primitive.getAsString());
      return;
    }
    String raw = primitive.getAsString();
    if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
      tag.putDouble(key, primitive.getAsDouble());
      return;
    }
    long number = primitive.getAsLong();
    if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
      tag.putInt(key, (int) number);
    } else {
      tag.putLong(key, number);
    }
  }

  private static NbtList list(JsonArray array) {
    NbtList list = new NbtList();
    for (JsonElement element : array) {
      if (element.isJsonObject()) {
        list.addItem(compound(element.getAsJsonObject()));
        continue;
      }
      // A list holds tags, not values: build the one tag the element describes and append it.
      NbtCompound holder = new NbtCompound();
      put(holder, "v", element);
      list.addItem(holder.get("v"));
    }
    return list;
  }
}
