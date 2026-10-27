package net.minecheck.platform.api;

import java.util.Map;

/**
 * A block read from a loaded world, as returned by {@link ClientPlatform#getBlock(BlockPosition)}.
 *
 * <p>The {@code nbt} map mirrors the block entity's NBT compound (the same shape {@link
 * SlotInfo#nbt()} uses for stacks): keys map to values of {@code String}, {@code Number}, {@code
 * Boolean}, nested {@code Map<String, Object>}, or {@code java.util.List} (numbers/strings/nested
 * values). Byte/int/long arrays are exposed as lists of numbers.
 *
 * @param id the block registry id (e.g. {@code "minecraft:beacon"}, {@code "minecraft:air"})
 * @param nbt the block entity's NBT compound, {@code null} when the block has no block entity
 */
public record BlockInfo(String id, Map<String, Object> nbt) {}
