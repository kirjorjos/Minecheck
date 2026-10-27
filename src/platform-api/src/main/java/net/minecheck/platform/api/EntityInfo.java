package net.minecheck.platform.api;

import java.util.Set;

/**
 * A live entity on the server, as reported by {@link ServerPlatform#findEntities(String, String)}.
 *
 * @param id the entity's network id
 * @param type the entity type registry id (e.g. {@code "minecraft:cow"})
 * @param x feet X
 * @param y feet Y
 * @param z feet Z
 * @param tags the entity's scoreboard tags (may be empty)
 */
public record EntityInfo(int id, String type, double x, double y, double z, Set<String> tags) {}
