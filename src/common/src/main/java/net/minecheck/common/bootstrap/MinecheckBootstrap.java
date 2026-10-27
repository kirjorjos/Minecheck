package net.minecheck.common.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.config.JsonConfigManager;
import net.minecheck.common.script.PluginRegistry;
import net.minecheck.common.script.ScriptEngine;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.plugin.MinecheckPlugin;

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

      // Install the packet receiver before the script starts so packets that arrive while the
      // engine is still being set up are queued rather than dropped.
      net.minecheck.platform.api.net.NetworkPlatform network = services.network();
      if (network != null) {
        network.setReceiver(net.minecheck.common.net.PacketInbox.instance()::deliver);
      }

      JsonConfigManager configManager =
          new JsonConfigManager(services.configDir().getConfigDirectory());
      ConfigData config = configManager.load();

      // Resolved before the "nothing configured" guard on purpose: -Dminecheck.script is itself a
      // configuration, so it selects a script even when config.json names none (which otherwise
      // means passive mode). Reading it after the guard would make the documented launch-time
      // override a no-op on exactly the setup people reach for it on — a default config.json.
      boolean launchArgOverride = System.getProperty("minecheck.script") != null;
      String scriptName = System.getProperty("minecheck.script", config.entryScript());
      if (scriptName == null || scriptName.isBlank()) {
        LOG.info(
            String.format(
                "Minecheck %s active — no script configured (set \"script\" or \"coordinator\" in %s)",
                services.dedicatedServer() ? "server" : "client", configManager.configFile()));
        return null;
      }

      Path scriptFile = configManager.scriptDirectory().resolve(scriptName);
      if (!Files.exists(scriptFile)) {
        LOG.severe(String.format("Configured script not found: %s", scriptFile));
        return null;
      }

      // Discover other mods' script actions/wait conditions once per engine (see MinecheckPlugin).
      // Logged even when the count is 0: that line is what tells a plugin author whether their
      // ServiceLoader provider file was seen at all.
      PluginRegistry plugins = new PluginRegistry(services.dedicatedServer());
      List<MinecheckPlugin> discovered = PluginRegistry.discover();
      for (MinecheckPlugin plugin : discovered) {
        plugins.register(plugin);
      }
      LOG.info(
          String.format(
              "Minecheck: %s plugin(s) discovered, %s action/condition(s) registered",
              discovered.size(), plugins.actions().size() + plugins.waits().size()));

      ScriptEngine engine =
          new ScriptEngine(
              services,
              config,
              scriptFile,
              configManager.resultFile(),
              System::currentTimeMillis,
              plugins);
      if (onEngineStarted != null) {
        onEngineStarted.accept(engine);
      }
      LOG.info(
          String.format(
              "Minecheck %s configured: %s",
              launchArgOverride
                  ? "script (-Dminecheck.script)"
                  : config.isCoordinator() ? "coordinator" : "script",
              scriptFile));
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
      LOG.warning(String.format("Minecheck: dh.serverSetupDir set but unusable: %s", source));
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
    LOG.info(String.format("Minecheck: staged server run dir from %s into %s", source, runDir));
  }
}
