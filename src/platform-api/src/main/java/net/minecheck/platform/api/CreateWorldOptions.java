package net.minecheck.platform.api;

/**
 * Options for {@link ClientPlatform#createWorld(CreateWorldOptions)}.
 *
 * <p>Deterministic defaults are applied at the Lua layer (superflat, no structures, no mob
 * spawning); only explicit overrides reach the platform.
 *
 * @param name world / save folder name
 * @param seed world seed
 * @param gamemode "survival" | "creative" | "adventure" | "spectator"
 * @param difficulty "peaceful" | "easy" | "normal" | "hard"
 * @param structures generate structures (default false — deterministic)
 * @param mobSpawning allow natural mob spawning (default false — deterministic)
 * @param allowCheats allow commands / cheats (default true — dev helper)
 */
public record CreateWorldOptions(
    String name,
    long seed,
    String gamemode,
    String difficulty,
    boolean structures,
    boolean mobSpawning,
    boolean allowCheats) {}
