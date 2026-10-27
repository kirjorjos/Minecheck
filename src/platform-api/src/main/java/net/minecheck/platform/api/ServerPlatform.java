package net.minecheck.platform.api;

/**
 * Dedicated-server operations backed by the running loader's Minecraft server.
 *
 * <p>All methods are called on the server thread (the script engine is driven from the server tick
 * event), so command execution needs no marshaling.
 */
public interface ServerPlatform {

  /**
   * Execute a console command exactly as if typed at the server console.
   *
   * @param command the command WITHOUT the leading {@code /}
   * @return the command result count (what {@code executeWithPrefix} returns); 0 for commands that
   *     ran nothing or failed
   */
  int runCommand(String command);

  /** True if a player with the given name is currently online. */
  boolean isPlayerOnline(String name);

  /**
   * Spawn an entity by registry id at a position, optionally applying NBT (SNBT string, may be
   * empty).
   *
   * @return the spawned entity's network id
   * @throws IllegalArgumentException if the entity id is unknown or the NBT is invalid
   */
  int spawnEntity(String id, double x, double y, double z, String nbt);

  /**
   * Set a block by registry id at a position, optionally applying NBT to the block entity (SNBT
   * string, may be empty). When {@code replace} is false the block is only set if the target
   * position is currently air.
   *
   * @return true if the block was set
   * @throws IllegalArgumentException if the block id is unknown or the NBT is invalid
   */
  boolean spawnBlock(String id, int x, int y, int z, String nbt, boolean replace);

  /**
   * Spawn an item entity (item drop) by item registry id with a count and optional stack NBT (SNBT
   * string, may be empty).
   *
   * @return the spawned item entity's network id
   * @throws IllegalArgumentException if the item id is unknown or the NBT is invalid
   */
  int spawnItemDrop(String id, int count, double x, double y, double z, String nbt);

  /**
   * Place a structure template by id (e.g. {@code minecraft:igloo/top}) at a position.
   *
   * @return true if the template was found and placed
   * @throws IllegalArgumentException if the template id is unknown
   */
  boolean spawnStructure(String id, int x, int y, int z);

  /**
   * Apply the deterministic-world gamerules (mob spawning, daylight cycle, weather cycle, insomnia
   * all disabled) to every loaded dimension. Called by the loader right after the server starts,
   * before any script runs, so a test world matches the client-side {@code createWorld}
   * deterministic defaults (spec §9.3).
   */
  void applyDeterministicRules();

  /**
   * Query live entities in the overworld. At most one of {@code type} / {@code tag} is normally
   * given ({@code null} = no filter), but both may be combined (entities matching the type AND
   * carrying the tag). Returns an immutable list of the matching entities' info. An unknown type
   * yields an empty list rather than an error.
   *
   * @param type entity type registry id to filter by (e.g. {@code "minecraft:cow"}), or {@code
   *     null} for any type
   * @param tag scoreboard tag to filter by, or {@code null} for any tag
   */
  java.util.List<EntityInfo> findEntities(String type, String tag);

  /**
   * True once this server's own overworld chunk generation has settled (no pending chunk work).
   * Mirrors {@link ClientPlatform#isWorldGenSettled()} for the dedicated-server case: a server
   * script can wait on this before e.g. asserting worldgen-related state, and it is never "not
   * applicable" since a running server always has its world generated. Called on the server thread;
   * fresh poll each tick.
   */
  default boolean isWorldGenSettled() {
    return true;
  }
}
