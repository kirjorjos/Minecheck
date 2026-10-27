package net.minecheck.gradle;

import org.gradle.api.provider.Property;

/**
 * Configuration surface for the Minecheck lifecycle test.
 *
 * <pre>
 * devHelper {
 *     loaderTask       = ":fabric-mod:runClient"        // client gradle task
 *     script           = "client-join-server.lua"          // client join/run script (used when there
 *                                                         // is no coordinator)
 *     coordinator      = "coordinator.lua"                 // optional: multi-phase single session
 *                                                         // (wins over script when set)
 *     serverScript     = "server-wait-stop.lua"            // optional: enables the parallel server phase
 *     serverLoaderTask = ":fabric-mod:runServer"        // server gradle task
 *     scriptsDir       = layout.projectDirectory.dir("tests/scripts").asFile
 *     runDir           = layout.projectDirectory.dir("src/fabric/mod/run/client").asFile
 *     serverRunDir     = layout.projectDirectory.dir("src/fabric/mod/run/server").asFile
 *     worldName        = "minecheck_world"
 *     serverPort       = "25565"
 *     levelSeed        = "0"                               // pinned level-seed (deterministic); empty = normal
 *     xvfbRun          = true                              // headless display wrapper
 * }
 * </pre>
 */
public abstract class MinecheckExtension {

  /** The loader Gradle task to drive for the client, e.g. {@code :fabric-mod:runClient}. */
  public abstract Property<String> getLoaderTask();

  /** Client Lua script (join/run phase); used when no coordinator is set. */
  public abstract Property<String> getScript();

  /** Optional client coordinator (multi-phase single session; wins over script). */
  public abstract Property<String> getCoordinator();

  /** Optional server Lua script; setting it enables the parallel server phase. */
  public abstract Property<String> getServerScript();

  /** The loader Gradle task to drive for the dedicated server. */
  public abstract Property<String> getServerLoaderTask();

  /** Directory containing the mod's Lua scripts to stage into the run dir. */
  public abstract Property<String> getScriptsDir();

  /** Client run directory whose config/minecheck is staged/read. */
  public abstract Property<String> getRunDir();

  /** Dedicated-server run directory (server phase). */
  public abstract Property<String> getServerRunDir();

  /** Level folder name the server uses. */
  public abstract Property<String> getWorldName();

  /** Server port the client joins in the server phase. */
  public abstract Property<String> getServerPort();

  /** Pinned level-seed for the server's world (deterministic); absent = normal random gen. */
  public abstract Property<String> getLevelSeed();

  /** Prepend {@code xvfb-run -a} (true for headless/no-display environments). */
  public abstract Property<Boolean> getXvfbRun();

  /** Wrapper invocation (default {@code ./gradlew}). */
  public abstract Property<String> getGradleCommand();
}
