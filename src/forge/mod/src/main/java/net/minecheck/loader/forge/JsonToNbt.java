package net.minecheck.loader.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Reads the JSON-shaped NBT a script writes into a {@link NBTTagCompound}.
 *
 * <p>{@code JsonToNBT} arrives with 1.8, so this line carries the reader itself. The shape is the
 * one the Lua API documents: an object is a compound, an array is a list, booleans are NBT bytes,
 * integral numbers are ints (or longs when they do not fit), fractional numbers are doubles, and
 * everything else is a string.
 */
final class JsonToNbt {

  private JsonToNbt() {}

  static NBTTagCompound parse(String text) {
    JsonElement root = new JsonParser().parse(text);
    if (root == null || !root.isJsonObject()) {
      throw new IllegalArgumentException("NBT must be a JSON object");
    }
    return compound(root.getAsJsonObject());
  }

  private static NBTTagCompound compound(JsonObject object) {
    NBTTagCompound tag = new NBTTagCompound();
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      put(tag, entry.getKey(), entry.getValue());
    }
    return tag;
  }

  private static void put(NBTTagCompound tag, String key, JsonElement value) {
    if (value.isJsonObject()) {
      tag.setCompoundTag(key, compound(value.getAsJsonObject()));
      return;
    }
    if (value.isJsonArray()) {
      tag.setTag(key, list(value.getAsJsonArray()));
      return;
    }
    JsonPrimitive primitive = value.getAsJsonPrimitive();
    if (primitive.isBoolean()) {
      tag.setBoolean(key, primitive.getAsBoolean());
      return;
    }
    if (primitive.isString()) {
      tag.setString(key, primitive.getAsString());
      return;
    }
    String raw = primitive.getAsString();
    if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
      tag.setDouble(key, primitive.getAsDouble());
      return;
    }
    long number = primitive.getAsLong();
    if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
      tag.setInteger(key, (int) number);
    } else {
      tag.setLong(key, number);
    }
  }

  private static NBTTagList list(JsonArray array) {
    NBTTagList list = new NBTTagList();
    for (JsonElement element : array) {
      if (element.isJsonObject()) {
        list.appendTag(compound(element.getAsJsonObject()));
        continue;
      }
      // A list holds tags, not values: build the one tag the element describes and append it.
      NBTTagCompound holder = new NBTTagCompound();
      put(holder, "v", element);
      list.appendTag(holder.getTag("v"));
    }
    return list;
  }
}
