package net.minecheck.common.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.config.JsonConfigManager;
import net.minecheck.common.script.ScriptEngine;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.ServerPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The composition root: which script a run picks, and the staging that happens before it is picked.
 *
 * <p>Everything here is observed through {@link ScriptEngine#scriptFile()}, so the assertions are
 * about the script that would actually run rather than about the config that was read.
 */
class MinecheckBootstrapTest {

  @TempDir Path tempDir;

  /** The loader's config directory; the manager nests {@code minecheck/} under it. */
  private Path loaderConfigDir() {
    return tempDir.resolve("config");
  }

  @AfterEach
  void clearLaunchArgs() {
    // Process-global: a leftover would silently change what every later test runs.
    System.clearProperty("minecheck.script");
    System.clearProperty("dh.serverSetupDir");
  }

  /** Writes a script into the staged scripts directory and returns it. */
  private Path writeScript(String name) throws Exception {
    Path dir = loaderConfigDir().resolve("minecheck/scripts");
    Files.createDirectories(dir);
    Path file = dir.resolve(name);
    Files.writeString(file, "minecheck.log(\"ran " + name + "\")\n");
    return file;
  }

  private void writeConfig(ConfigData config) throws Exception {
    new JsonConfigManager(loaderConfigDir()).save(config);
  }

  private PlatformServices services(boolean dedicated) {
    return new PlatformServices() {
      @Override
      public String minecraftVersion() {
        return "1.19.2";
      }

      @Override
      public ConfigDirProvider configDir() {
        return MinecheckBootstrapTest.this::loaderConfigDir;
      }

      @Override
      public ClientPlatform client() {
        return null;
      }

      @Override
      public ServerPlatform server() {
        return null;
      }

      @Override
      public boolean dedicatedServer() {
        return dedicated;
      }
    };
  }

  private static String scriptName(ScriptEngine engine) {
    return engine.scriptFile().getFileName().toString();
  }

  /**
   * The documented launch override has to work on a default install, where config.json names no
   * script — that is the case people reach for it on. Resolving it after the "nothing configured"
   * guard made it a silent no-op there.
   */
  @Test
  void launchArgRunsAScriptWhenTheConfigNamesNone() throws Exception {
    writeScript("probe.lua");
    System.setProperty("minecheck.script", "probe.lua");

    ScriptEngine engine = MinecheckBootstrap.init(services(false), null);

    assertNotNull(engine, "-Dminecheck.script must run its script on an otherwise empty config");
    assertEquals("probe.lua", scriptName(engine));
  }

  @Test
  void launchArgOverridesTheConfiguredScript() throws Exception {
    writeConfig(new ConfigData("configured.lua", null, "exit", 30));
    writeScript("configured.lua");
    writeScript("probe.lua");
    System.setProperty("minecheck.script", "probe.lua");

    ScriptEngine engine = MinecheckBootstrap.init(services(false), null);

    assertNotNull(engine);
    assertEquals("probe.lua", scriptName(engine));
  }

  @Test
  void launchArgOverridesTheCoordinator() throws Exception {
    writeConfig(new ConfigData("", "coordinator.lua", "exit", 30));
    writeScript("coordinator.lua");
    writeScript("probe.lua");
    System.setProperty("minecheck.script", "probe.lua");

    ScriptEngine engine = MinecheckBootstrap.init(services(false), null);

    assertNotNull(engine);
    assertEquals("probe.lua", scriptName(engine));
  }

  @Test
  void coordinatorWinsOverScriptWhenNoLaunchArgIsGiven() throws Exception {
    writeConfig(new ConfigData("configured.lua", "coordinator.lua", "exit", 30));
    writeScript("configured.lua");
    writeScript("coordinator.lua");

    ScriptEngine engine = MinecheckBootstrap.init(services(false), null);

    assertNotNull(engine);
    assertEquals("coordinator.lua", scriptName(engine));
  }

  @Test
  void anEmptyConfigWithoutALaunchArgStartsNothing() throws Exception {
    writeScript("probe.lua");

    assertNull(MinecheckBootstrap.init(services(false), null), "empty config means passive mode");
  }

  @Test
  void aLaunchArgNamingAMissingScriptStartsNothing() throws Exception {
    writeScript("probe.lua");
    System.setProperty("minecheck.script", "nope.lua");

    assertNull(MinecheckBootstrap.init(services(false), null));
  }

  @Test
  void aMissingConfigFileIsCreatedWithTheDefaults() throws Exception {
    writeScript("probe.lua");

    MinecheckBootstrap.init(services(false), null);

    assertTrue(
        Files.exists(loaderConfigDir().resolve("minecheck/config.json")),
        "a fresh install gets a template config.json");
  }

  /**
   * The dedicated-server staging path: the plugin hands a directory to {@code -Ddh.serverSetupDir}
   * and the bootstrap copies it into the run dir <em>before</em> the config is loaded, so a staged
   * config and its scripts are the ones the run uses.
   */
  @Test
  void serverSetupDirIsStagedIntoTheRunDirBeforeTheScriptIsResolved() throws Exception {
    Path setup = tempDir.resolve("setup");
    Files.createDirectories(setup.resolve("config/minecheck/scripts"));
    Files.writeString(
        setup.resolve("config/minecheck/config.json"),
        "{ \"script\": \"staged.lua\", \"failMode\": \"continue\" }");
    Files.writeString(
        setup.resolve("config/minecheck/scripts/staged.lua"), "minecheck.log(\"staged\")\n");
    Files.writeString(setup.resolve("server.properties"), "level-seed=42\n");
    System.setProperty("dh.serverSetupDir", setup.toString());

    ScriptEngine engine = MinecheckBootstrap.init(services(true), null);

    assertNotNull(engine, "the staged config is the one that is read");
    assertEquals("staged.lua", scriptName(engine));
    assertTrue(Files.exists(tempDir.resolve("server.properties")), "other setup files land too");
  }

  @Test
  void serverSetupDirIsIgnoredOnTheClient() throws Exception {
    Path setup = tempDir.resolve("setup");
    Files.createDirectories(setup);
    Files.writeString(setup.resolve("server.properties"), "level-seed=42\n");
    writeConfig(new ConfigData("probe.lua", null, "exit", 30));
    writeScript("probe.lua");
    System.setProperty("dh.serverSetupDir", setup.toString());

    ScriptEngine engine = MinecheckBootstrap.init(services(false), null);

    assertNotNull(engine, "the client still runs its own config");
    assertEquals("probe.lua", scriptName(engine));
    assertTrue(Files.notExists(tempDir.resolve("server.properties")), "nothing was staged");
  }
}
