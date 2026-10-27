package net.minecheck.platform.api;

import java.util.Map;

/**
 * A single inventory/container slot, as read by {@link ClientPlatform#getSlot(int, String)}.
 *
 * <p>The {@code nbt} map mirrors the stack's NBT compound: keys map to values of {@code String},
 * {@code Number}, {@code Boolean}, nested {@code Map<String, Object>}, or {@code java.util.List}
 * (numbers/strings/nested values). Byte/int/long arrays are exposed as lists of numbers.
 *
 * @param empty true when the slot holds no item
 * @param id the item registry id (e.g. {@code "minecraft:emerald"}), {@code null} when empty
 * @param count stack size (0 when empty)
 * @param nbt the stack's NBT compound, {@code null} when empty or tag-less
 */
public record SlotInfo(boolean empty, String id, int count, Map<String, Object> nbt) {}
