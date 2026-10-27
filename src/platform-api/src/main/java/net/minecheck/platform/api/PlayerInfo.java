package net.minecheck.platform.api;

/**
 * The local player's identity and position, as reported by {@link ClientPlatform#getPlayer()}.
 *
 * @param name the player's display name
 * @param x feet block X
 * @param y feet block Y
 * @param z feet block Z
 * @param dimension dimension id (e.g. {@code "minecraft:overworld"})
 */
public record PlayerInfo(String name, double x, double y, double z, String dimension) {}
