package net.minecheck.platform.api;

/**
 * A block position in world coordinates, as returned by {@link
 * ClientPlatform#getTargetBlock(String)}.
 */
public record BlockPosition(int x, int y, int z) {}
