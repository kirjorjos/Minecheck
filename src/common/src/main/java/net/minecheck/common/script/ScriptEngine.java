package net.minecheck.common.script;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.net.PacketChannel;
import net.minecheck.common.net.PacketInbox;
import net.minecheck.common.result.ExpectRecord;
import net.minecheck.common.result.RunResult;
import net.minecheck.common.result.RunResultWriter;
import net.minecheck.common.tick.TickClock;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.ServerPlatform;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Runs a Lua script cooperatively with the client tick loop.
 *
 * <p>The script executes inside a LuaJ coroutine on the client thread. Java-side actions suspend
 * the coroutine by calling {@code globals.yield(...)}; the engine resumes it once per client tick
 * (driven by the loader's tick event) until the script finishes or errors.
 */
public final class ScriptEngine {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private final PlatformServices services;
  private final ClientPlatform client;
  private final ServerPlatform server;
  private final ConfigData config;
  private final Path scriptFile;

  /** Where the CI result contract is written (config/minecheck/run-result.json). */
  private final Path resultFile;

  private final LongSupplier clock;

  /** The script-facing packet surface; drains the loader-fed inbox once per tick. */
  private final PacketChannel packets;

  /** Third-party actions/wait conditions exposed as {@code minecheck.ext} (empty when none). */
  private final PluginRegistry plugins;

  private Globals globals;
  private LuaThread coroutine;
  private boolean finished;
  private boolean failed;
  private String failureMessage;
  private final java.util.List<ExpectRecord> expects = new java.util.ArrayList<>();

  /** Named values a suite's committed baseline is compared against; empty for ordinary runs. */
  private final java.util.Map<String, String> observations = new java.util.LinkedHashMap<>();

  /**
   * Live listeners and connections the script opened (see {@code minecheck.listen}). They are
   * closed when the run ends, however it ends: a bound port outliving its script would make the
   * next run fail to bind, and a leaked connection would keep a dev program talking to a game that
   * has already moved on.
   */
  private final java.util.List<AutoCloseable> live = new java.util.ArrayList<>();

  public ScriptEngine(PlatformServices services, ConfigData config, Path scriptFile) {
    this(
        services,
        config,
        scriptFile,
        null,
        System::currentTimeMillis,
        new PluginRegistry(services.dedicatedServer()));
  }

  public ScriptEngine(
      PlatformServices services, ConfigData config, Path scriptFile, LongSupplier clock) {
    this(services, config, scriptFile, null, clock, new PluginRegistry(services.dedicatedServer()));
  }

  public ScriptEngine(
      PlatformServices services,
      ConfigData config,
      Path scriptFile,
      Path resultFile,
      LongSupplier clock) {
    this(
        services,
        config,
        scriptFile,
        resultFile,
        clock,
        new PluginRegistry(services.dedicatedServer()));
  }

  /**
   * Full constructor. {@code plugins} carries the third-party actions/wait conditions discovered by
   * the composition root ({@code MinecheckBootstrap}); unit tests pass their own registry.
   */
  public ScriptEngine(
      PlatformServices services,
      ConfigData config,
      Path scriptFile,
      Path resultFile,
      LongSupplier clock,
      PluginRegistry plugins) {
    this.services = services;
    this.client = services.client();
    this.server = services.server();
    this.config = config;
    this.scriptFile = scriptFile;
    this.resultFile = resultFile;
    this.clock = clock;
    this.plugins = plugins == null ? new PluginRegistry(services.dedicatedServer()) : plugins;
    this.packets = new PacketChannel(services.network(), PacketInbox.instance(), this::failScript);
  }

  /**
   * Records one {@code minecheck.expect} result (called from the Lua action).
   *
   * <p>A {@code null} name means the script gave no usable label, and the check is numbered {@code
   * "expect #<n>"} by position — the documented default. The numbering lives here because the count
   * is "how many expects have been recorded", which only this class knows: two unnamed checks used
   * to record the identical name {@code "expect"}, so the CI report could not tell them apart.
   */
  public void recordExpect(String name, boolean ok) {
    expects.add(new ExpectRecord(name != null ? name : "expect #" + (expects.size() + 1), ok));
  }

  /**
   * The position the next recorded expectation will take. A check that numbers itself ({@code
   * minecheck.check}, whose default label is {@code check #<n>} rather than {@code expect #<n>})
   * asks for it so one run keeps one sequence of numbers.
   */
  public int nextExpectNumber() {
    return expects.size() + 1;
  }

  /**
   * Records one named observation (called from the Lua {@code minecheck.observe}).
   *
   * <p>An observation is a <em>value</em>, not a verdict: what the script measured — the surface it
   * read, whether the chunk had arrived, the block it found. The run result carries them beside the
   * expectations so a suite can compare the whole observation against a committed baseline instead
   * of only asking whether the script's own check passed. A later value for the same name wins.
   */
  public void recordObservation(String name, String value) {
    if (name != null && !name.isEmpty() && value != null) {
      observations.put(name, value);
    }
  }

  /**
   * Registers a live listener or connection so the engine closes it when the run ends.
   *
   * <p>Registration is per-handle, not per-script: a script may open several and close them itself
   * as it goes, and close is idempotent on both sides.
   */
  public void registerLive(AutoCloseable handle) {
    if (handle != null) {
      live.add(handle);
    }
  }

  /** Marks the run failed with a message (called from a Lua {@code minecheck.fail}). */
  public void failScript(String message) {
    if (!finished) {
      fail(message);
    }
  }

  public boolean isFailed() {
    return failed;
  }

  public String failureMessage() {
    return failureMessage;
  }

  public boolean isFinished() {
    return finished;
  }

  public Path scriptFile() {
    return scriptFile;
  }

  /** Advance the script one step. Called once per client tick (or directly in tests). */
  public void tick() {
    if (finished) {
      return;
    }
    try {
      if (coroutine == null) {
        prepare();
      }
      // Delivered before the resume so a waiting script sees this tick's packets, and while the
      // coroutine is parked so invoking an onPacket handler cannot race the script's own execution.
      packets.tick();
      if (finished) {
        // A handler or decoder failed the run: its coroutine thread was already reaped, so
        // resuming it would raise OrphanedThread instead of finishing cleanly.
        return;
      }
      Varargs out = coroutine.resume(LuaValue.NONE);
      if (!out.arg1().toboolean()) {
        if (finished) {
          // The run ended in this same tick (minecheck.fail): that parks the coroutine in a yield
          // loop and the reaper interrupts it, so the interrupt surfaces here as LuaJ's "orphaned
          // thread". Reporting that as a fresh SEVERE script error would mask the real failure
          // message the run already recorded.
          LOG.info(
              String.format(
                  "Minecheck: coroutine reaped after the run ended (%s)", out.arg(2).tojstring()));
        } else {
          fail("Script error: " + out.arg(2).tojstring());
        }
      } else if ("dead".equals(coroutine.getStatus())) {
        finish(false);
      }
    } catch (Exception e) {
      fail("Script crashed: " + e.getMessage());
    }
  }

  private void prepare() throws IOException {
    globals = JsePlatform.standardGlobals();
    new LuaMinecheck(globals, services, config, clock, this, packets, plugins).register();
    String source = Files.readString(scriptFile);
    LuaValue chunk = globals.load(source, "@" + scriptFile.getFileName());
    coroutine = (LuaThread) globals.get("coroutine").get("create").call(chunk);
    LOG.info(String.format("Minecheck script started: %s", scriptFile.getFileName()));
  }

  private void finish(boolean failed) {
    if (finished) {
      return;
    }
    this.finished = true;
    this.failed = failed;
    // A script that stopped while it held the tick clock must not leave the server parked, and one
    // that asked for uncapped ticks must not leave it spinning: the engine owns the run's teardown,
    // so both go back even when the script never said so.
    TickClock.get().resume();
    TickClock.get().setUncapped(false);
    LOG.info(
        String.format(
            "Minecheck script %s: %s", failed ? "failed" : "finished", scriptFile.getFileName()));
    closeLive();
    writeResult();
    if (failed && config.exitOnFail()) {
      if (client != null) {
        LOG.severe("failMode=exit — stopping client");
        client.quit();
      } else if (server != null) {
        LOG.severe("failMode=exit — stopping server");
        server.runCommand("stop");
      }
    }
    reapCoroutineThreads();
    if (server != null) {
      scheduleConfigWatcherStop();
    }
  }

  /**
   * Stops NightConfig's config file watcher once this server has stopped.
   *
   * <p>Forge 1.21 never calls {@code FileWatcher.stop()}, and the watcher's scheduled executor is a
   * non-daemon thread: a dedicated server that ran {@code stop} saves every dimension and then
   * keeps the JVM alive, so the run is reported as hung long after the script passed. The watcher
   * only reloads a config edited on disk mid-run, and the run is over. Its class is absent on
   * Fabric and Quilt, where there is nothing to stop.
   *
   * <p>The wait is for the server thread to stop ticking, not a fixed delay: the last save is still
   * running at that point, and stopping the watcher early would only let FML start it again during
   * its own shutdown.
   */
  private void scheduleConfigWatcherStop() {
    Thread watcher =
        new Thread(
            () -> {
              long deadline = System.currentTimeMillis() + 120_000L;
              try {
                while (TickClock.get().running() && System.currentTimeMillis() < deadline) {
                  Thread.sleep(500L);
                }
                // A second and a third pass: FML can start the watcher while it unloads configs.
                for (int pass = 0; pass < 3; pass++) {
                  Thread.sleep(2_000L);
                  stopConfigWatcher();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "Minecheck-config-watcher-stop");
    watcher.setDaemon(true);
    watcher.start();
  }

  private static void stopConfigWatcher() {
    try {
      Class<?> type = Class.forName("com.electronwill.nightconfig.core.file.FileWatcher");
      Object instance = type.getMethod("defaultInstance").invoke(null);
      type.getMethod("stop").invoke(instance);
      LOG.info("Minecheck: stopped the config file watcher");
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      // NightConfig is not on Fabric or Quilt; nothing started a watcher there.
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.log(
          java.util.logging.Level.WARNING, "Minecheck: could not stop the config file watcher", e);
    }
  }

  /**
   * LuaJ runs each Lua coroutine on its own NON-daemon "Coroutine-N" Java thread. A script left
   * suspended when the engine finishes (minecheck.fail inside a pcall'd function, or any yield
   * never resumed again) parks that thread in Object.wait() forever — and a parked non-daemon
   * thread keeps the JVM from exiting after the client/server stops, so a failing run would hang
   * instead of exiting. Interrupt them: LuaJ reaps an interrupted coroutine at its next yield
   * (throws OrphanedThread and the thread dies).
   */
  private void reapCoroutineThreads() {
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.getName().startsWith("Coroutine-")) {
        t.interrupt();
      }
    }
  }

  private void closeLive() {
    for (AutoCloseable handle : live) {
      try {
        handle.close();
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Minecheck: failed to close a live listener", e);
      }
    }
    live.clear();
  }

  private void writeResult() {
    if (resultFile == null) {
      LOG.warning("Minecheck: no result file configured — run-result.json not written");
      return;
    }
    RunResult result =
        new RunResult(
            scriptFile.getFileName().toString(),
            finished,
            failed,
            failureMessage,
            java.util.List.copyOf(expects),
            observations.isEmpty() ? null : java.util.Map.copyOf(observations));
    try {
      RunResultWriter.write(resultFile, result);
      LOG.info(
          String.format(
              "Minecheck: wrote run result (%s/%s expects%s) to %s",
              result.passed(), result.total(), failed ? ", FAILED" : "", resultFile));
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "Minecheck: failed to write run result", e);
    }
  }

  private void fail(String message) {
    if (!finished) {
      this.failureMessage = message;
    }
    LOG.log(Level.SEVERE, message);
    finish(true);
  }
}
