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
    LOG.info(
        String.format(
            "Minecheck script %s: %s", failed ? "failed" : "finished", scriptFile.getFileName()));
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
            java.util.List.copyOf(expects));
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
