package net.minecheck.common.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.config.JsonConfigManager;
import net.minecheck.common.script.ScriptEngine;
import net.minecheck.platform.api.PlatformServices;

public final class MinecheckBootstrap {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private MinecheckBootstrap() {}

  /**
   * @param services the loader-provided platform services
   * @param onEngineStarted invoked with the running engine so the loader can drive its ticks
   *     (client tick event); may be null
   * @return the started engine, or null if no script is configured / not applicable
   */
  public static ScriptEngine init(
      PlatformServices services, Consumer<ScriptEngine> onEngineStarted) {
    try {
      stageServerRunDir(services);

      JsonConfigManager configManager =
          new JsonConfigManager(services.configDir().getConfigDirectory());
      ConfigData config = configManager.load();

      String scriptName = config.entryScript();
      if (scriptName == null || scriptName.isBlank()) {
        LOG.info(
            "Minecheck "
                + (services.dedicatedServer() ? "server" : "client")
                + " active — no script configured (set \"script\" or \"coordinator\" in "
                + configManager.configFile()
                + ")");
        return null;
      }

      scriptName = System.getProperty("minecheck.script", scriptName);

      Path scriptFile = configManager.scriptDirectory().resolve(scriptName);
      if (!Files.exists(scriptFile)) {
        LOG.severe("Configured script not found: " + scriptFile);
        return null;
      }

      ScriptEngine engine =
          new ScriptEngine(
              services, config, scriptFile, configManager.resultFile(), System::currentTimeMillis);
      if (onEngineStarted != null) {
        onEngineStarted.accept(engine);
      }
      LOG.info(
          "Minecheck "
              + (config.isCoordinator() ? "coordinator" : "script")
              + " configured: "
              + scriptFile);
      return engine;
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.SEVERE, "Failed to initialize Minecheck", e);
      return null;
    }
  }

  private static void stageServerRunDir(PlatformServices services) throws IOException {
    String setup = System.getProperty("dh.serverSetupDir");
    if (setup == null || setup.isBlank() || !services.dedicatedServer()) {
      return;
    }
    Path source = Path.of(setup);
    Path runDir = services.configDir().getConfigDirectory().getParent();
    if (runDir == null || !Files.isDirectory(source)) {
      LOG.warning("Minecheck: dh.serverSetupDir set but unusable: " + source);
      return;
    }
    Files.createDirectories(runDir);
    for (Iterator<Path> it = Files.walk(source).iterator(); it.hasNext(); ) {
      Path p = it.next();
      Path rel = source.relativize(p);
      if (rel.getNameCount() == 0) {
        continue;
      }
      Path target = runDir.resolve(rel.toString());
      if (Files.isDirectory(p)) {
        Files.createDirectories(target);
      } else {
        Files.createDirectories(target.getParent());
        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    LOG.info("Minecheck: staged server run dir from " + source + " into " + runDir);
  }
}
