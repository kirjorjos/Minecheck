package net.minecheck.gradle;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Minecheck lifecycle test task.
 *
 * <p>Runs the headless Minecraft lifecycle test, driven through the mod's Lua scripts and the
 * {@code run-result.json} verdict each run writes.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li><b>Parallel</b> (when {@code serverScript} is set): the client and the dedicated server are
 *       launched <b>at the same time</b>. The plugin stages a minimal {@code server.properties}
 *       (optionally with a pinned {@code levelSeed} for deterministic worlds — the normal server
 *       jar + installed world-gen mods still generate the world; Minecheck never drives
 *       generation). The server's own script waits for the player to join/leave, asserts, and stops
 *       itself; the client runs {@code script} (or {@code coordinator}) joining the server.
 *   <li><b>Client-only</b>: a single client runs {@code coordinator} (multi-phase, via {@code
 *       minecheck.disconnect()} returning to the title screen) or {@code script}.
 * </ul>
 *
 * Verdict: the client run is the primary pass/fail; a server run is secondary — the task fails
 * unless every relevant {@code run-result.json} is {@code finished && !failed}.
 *
 * <p>Contract with the mod (the only coupling): a {@code minecheck/scripts/*.lua} tree, a {@code
 * config.json}, and {@code run-result.json} under each loader's {@code config/minecheck}. The
 * plugin holds no reference to Minecraft or the mod's game classes — the game runs as plain
 * subprocesses of this build.
 */
public abstract class MinecheckLifecycleTest extends DefaultTask {

  private static final Gson GSON = new Gson();

  /** Loader Gradle task for the client, e.g. {@code :fabric-mod:runClient}. */
  @Input
  public abstract Property<String> getLoaderTask();

  /** Client Lua script (join/run phase). Used when no {@code coordinator} is set. */
  @Input
  public abstract Property<String> getScript();

  /** Optional client coordinator (multi-phase single session; wins over {@code script}). */
  @Input
  @Optional
  public abstract Property<String> getCoordinator();

  /** Optional server Lua script; setting it enables the parallel server phase. */
  @Input
  @Optional
  public abstract Property<String> getServerScript();

  /** Loader Gradle task for the dedicated server, e.g. {@code :fabric-mod:runServer}. */
  @Input
  @Optional
  public abstract Property<String> getServerLoaderTask();

  /** Directory containing the mod's Lua scripts to stage. */
  @Input
  public abstract Property<String> getScriptsDir();

  /** Client run directory (config/minecheck/* is staged/read here). */
  @Input
  public abstract Property<String> getRunDir();

  /** Dedicated-server run directory. */
  @Input
  @Optional
  public abstract Property<String> getServerRunDir();

  /** Level folder name the server uses. */
  @Input
  @Optional
  public abstract Property<String> getWorldName();

  /** Server port the client joins. */
  @Input
  @Optional
  public abstract Property<String> getServerPort();

  /**
   * Pinned {@code level-seed} for the server's world (empty/absent = normal random generation).
   * Minecheck only writes it into {@code server.properties}; the vanilla jar + installed world-gen
   * mods still generate the world normally.
   */
  @Input
  @Optional
  public abstract Property<String> getLevelSeed();

  /** Whether to prepend {@code xvfb-run -a} (headless no-display environments). */
  @Input
  @Optional
  public abstract Property<Boolean> getXvfbRun();

  /**
   * When true, a run verdict that FAILS ({@code finished && failed}) counts as a PASS, and a
   * passing verdict fails the task — for negative tests of the fail path itself. The game processes
   * must STILL exit cleanly (code 0) and write a verdict; a crash or a hung JVM always fails the
   * task.
   */
  @Input
  @Optional
  public abstract Property<Boolean> getExpectFailure();

  /**
   * Client launch descriptor: a {@code key=value} file written by the loader's {@code
   * devHelperClientLaunch} task capturing the classpath, main class, args, JVM args, system
   * properties and working dir for {@code runClient}.
   */
  @InputFile
  @Optional
  public abstract RegularFileProperty getClientLaunchFile();

  /**
   * Server launch descriptor (see {@link #getClientLaunchFile()}); written by the loader's {@code
   * devHelperServerLaunch} task.
   */
  @InputFile
  @Optional
  public abstract RegularFileProperty getServerLaunchFile();

  /** The wrapper script (e.g. {@code ./gradlew}) to invoke. */
  @Input
  @Optional
  public abstract Property<String> getGradleCommand();

  private static Path descriptorPath(RegularFileProperty prop) {
    org.gradle.api.file.RegularFile rf = prop.getOrNull();
    return rf == null ? null : rf.getAsFile().toPath();
  }

  /** Where the client writes its verdict (the run's primary pass/fail). */
  @OutputFile
  public abstract RegularFileProperty getResultFile();

  @TaskAction
  public void runLifecycle() {
    String entry =
        getCoordinator().getOrNull() != null ? getCoordinator().get() : getScript().get();
    getLogger()
        .lifecycle(
            "Minecheck: starting lifecycle test (entry={}, server={})",
            entry,
            getServerScript().getOrNull());

    Path runDir = Path.of(getRunDir().get());
    Path configDir = runDir.resolve("config/minecheck");

    try {
      stageClientOptions(runDir);
      if (getServerScript().isPresent()) {
        runParallel(configDir);
      } else {
        stage(configDir, entry, getCoordinator().isPresent());
        runClientOnce(configDir, "client");
      }
    } catch (IOException e) {
      throw new GradleException("Minecheck lifecycle test failed to orchestrate the game run", e);
    }
  }

  // ---------------------------------------------------------------- client

  /** Runs the client once with the already-staged config and asserts its verdict. */
  private void runClientOnce(Path configDir, String phase) throws IOException {
    Path resultFile = getResultFile().get().getAsFile().toPath();
    Files.deleteIfExists(resultFile);

    getLogger().lifecycle("Minecheck: [{}] launching client {}", phase, getLoaderTask().get());
    Process p =
        start(
            launcherBuilder(
                getLoaderTask().get(),
                List.of(),
                descriptorPath(getClientLaunchFile()),
                getXvfbRun().getOrElse(true)),
            "client-" + phase);
    RunResultDto result = awaitVerdict(resultFile, Duration.ofMinutes(20), p);
    int exit = waitExit(p, Duration.ofSeconds(60));
    getLogger().lifecycle("Minecheck: [{}] client exited (code {})", phase, exit);

    requireCleanExit(exit, "client " + phase);
    requirePassed(result, "client " + phase);
  }

  // ------------------------------------------------------- parallel server

  /** Stages the server, then launches client + server together, waits for both. */
  private void runParallel(Path clientConfigDir) throws IOException {
    Path serverRunDir = Path.of(getServerRunDir().get());
    Path serverResult = serverRunDir.resolve("config/minecheck/run-result.json");
    String serverScript = getServerScript().get();

    try {
      Files.createDirectories(serverRunDir);
      deleteRecursively(serverRunDir.resolve(world()));
      Files.writeString(serverRunDir.resolve("eula.txt"), "eula=true\n");
      Files.writeString(serverRunDir.resolve("server.properties"), serverProperties());
      stage(serverRunDir.resolve("config/minecheck"), serverScript, false);
    } catch (IOException e) {
      throw new GradleException("Minecheck: failed to stage the server run dir", e);
    }

    getLogger()
        .lifecycle(
            "Minecheck: [server] launching {} (script={}) with a pinned deterministic world",
            getServerLoaderTask().get(),
            serverScript);
    Files.deleteIfExists(serverResult);
    Process server =
        start(
            launcherBuilder(
                getServerLoaderTask().get(),
                List.of(),
                descriptorPath(getServerLaunchFile()),
                false),
            "server");

    stage(
        clientConfigDir,
        getCoordinator().getOrNull() != null ? getCoordinator().get() : getScript().get(),
        getCoordinator().isPresent());

    Path clientResult = getResultFile().get().getAsFile().toPath();
    Files.deleteIfExists(clientResult);

    getLogger()
        .lifecycle("Minecheck: [server] launching client in parallel {}", getLoaderTask().get());
    Process client =
        start(
            launcherBuilder(
                getLoaderTask().get(),
                List.of(),
                descriptorPath(getClientLaunchFile()),
                getXvfbRun().getOrElse(true)),
            "client");
    RunResultDto clientVerdict = awaitVerdict(clientResult, Duration.ofMinutes(20), client);
    int clientExit = waitExit(client, Duration.ofSeconds(60));
    getLogger().lifecycle("Minecheck: [server] client exited (code {})", clientExit);

    RunResultDto serverVerdict = awaitVerdict(serverResult, Duration.ofMinutes(5), server);
    int serverExit = waitExit(server, Duration.ofSeconds(60));
    getLogger().lifecycle("Minecheck: [server] server exited (code {})", serverExit);

    requireCleanExit(clientExit, "client");
    requireCleanExit(serverExit, "server");
    requirePassed(clientVerdict, "client");
    if (serverVerdict != null) {
      requirePassed(serverVerdict, "server-script");
    } else {
      getLogger().warn("Minecheck: server wrote no run-result.json; ignoring (secondary run).");
    }
  }

  /**
   * Pins the client's render/simulation distance so the integrated server never re-preps its spawn
   * region.
   *
   * <p>The integrated server starts with view distance 10 (its internal default), and {@code
   * IntegratedServer.tick} compares the client's options every tick; on a mismatch (e.g. client
   * options.txt says 12) it calls {@code setViewDistance(12)}, which re-runs spawn-region prep at
   * the larger radius as player-driven, incremental generation with no completion event. That
   * second prep is what leaves a freshly-created world saved mid-ring and makes the rejoin
   * re-generate it at 0% for tens of seconds on slow CI. Writing the client's options.txt with the
   * server's initial value makes the comparison equal every tick, so the synchronous {@code
   * prepareStartRegion} (radius 10, 21x21 chunks) is the ONLY spawn prep — it finishes before the
   * player spawns, the save is always complete, and the rejoin loads from disk instead of
   * regenerating.
   */
  private void stageClientOptions(Path runDir) throws IOException {
    Files.createDirectories(runDir);
    Files.writeString(runDir.resolve("options.txt"), "renderDistance:10\nsimulationDistance:10\n");
    getLogger()
        .lifecycle(
            "Minecheck: pinned client options.txt render/simulation distance to 10 in {}", runDir);
  }

  /** Builds the {@code server.properties} the plugin hands the server. */
  private String serverProperties() {
    StringBuilder sb = new StringBuilder();
    sb.append("level-name=").append(world()).append('\n');
    sb.append("online-mode=false\n");
    sb.append("server-port=").append(port()).append('\n');
    sb.append("enforce-secure-profile=false\n");
    String seed = getLevelSeed().getOrNull();
    if (seed != null && !seed.isBlank()) {
      sb.append("level-seed=").append(seed).append('\n');
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- verdict

  /**
   * Asserts the run's verdict. Normally a passing verdict passes the task; with {@link
   * #getExpectFailure()} a FAILED verdict is the expected outcome (negative tests of the fail
   * path). In both modes a missing verdict (crashed process) is always a task failure.
   */
  private void requirePassed(RunResultDto result, String what) {
    if (result == null) {
      throw new GradleException(
          "Minecheck lifecycle test FAILED: " + what + " wrote no run-result.json");
    }
    boolean expectFailure = getExpectFailure().getOrElse(false);
    boolean runFailed = !result.finished() || result.failed();
    if (runFailed == expectFailure) {
      if (expectFailure) {
        getLogger()
            .lifecycle(
                "Minecheck: {} FAILED AS EXPECTED ({} expects passed / {} total): {}",
                what,
                result.passed(),
                result.total(),
                result.failureMessage());
      } else {
        getLogger()
            .lifecycle(
                "Minecheck: {} PASSED ({} expects passed / {} total)",
                what,
                result.passed(),
                result.total());
      }
      return;
    }
    String detail =
        expectFailure
            ? "expected the run to fail but it passed ("
                + result.passed()
                + "/"
                + result.total()
                + " expects)"
            : result.failureMessage();
    throw new GradleException("Minecheck lifecycle test FAILED (" + what + "): " + detail);
  }

  /** A game process that didn't exit 0 hung (or crashed) after writing its verdict. */
  private void requireCleanExit(int exit, String what) {
    if (exit != 0) {
      throw new GradleException(
          "Minecheck lifecycle test FAILED: "
              + what
              + " did not exit cleanly (code "
              + exit
              + ") — the JVM hung or crashed after the run");
    }
  }

  /**
   * Polls for the run verdict, giving up early (before the deadline) if the game process is already
   * dead and no verdict file was written — a crashed process will never produce one, so don't sit
   * out the full deadline.
   */
  private RunResultDto awaitVerdict(Path resultFile, Duration giveUp, Process p)
      throws IOException {
    long deadline = System.currentTimeMillis() + giveUp.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (Files.exists(resultFile)) {
        try {
          RunResultDto r =
              GSON.fromJson(
                  Files.readString(resultFile, StandardCharsets.UTF_8), RunResultDto.class);
          if (r != null) {
            return r;
          }
        } catch (IOException ignored) { }
      }
      if (p != null && !p.isAlive()) {
        return null;
      }
      sleep(2000);
    }
    return null;
  }

  private int waitExit(Process p, Duration giveUp) {
    if (p == null) {
      return -1;
    }
    try {
      return p.waitFor(giveUp.toMillis(), TimeUnit.MILLISECONDS) ? p.exitValue() : -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  // ---------------------------------------------------------------- staging

  /**
   * Stages scripts into {@code configDir/scripts} and writes {@code config.json} pointing {@code
   * "coordinator"} when {@code coordinator=true}, else {@code "script"}.
   */
  private void stage(Path configDir, String scriptName, boolean coordinator) throws IOException {
    Files.createDirectories(configDir.resolve("scripts"));
    Path scriptsDir = Path.of(getScriptsDir().get());
    Path from = scriptsDir.resolve(scriptName);
    if (!Files.exists(from)) {
      throw new GradleException("Minecheck: script not found in scripts dir: " + from);
    }
    try (var stream = Files.list(scriptsDir)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(
              f -> {
                try {
                  Files.copy(
                      f,
                      configDir.resolve("scripts").resolve(f.getFileName()),
                      StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                  // best-effort staging
                }
              });
    }

    Files.writeString(
        configDir.resolve("config.json"),
        "{ "
            + (coordinator ? "\"coordinator\"" : "\"script\"")
            + ": \""
            + scriptName
            + "\", \"failMode\": \"exit\", \"defaultTimeout\": 60 }\n");
    getLogger()
        .lifecycle(
            "Minecheck: staged config.json ({}={}) into {}",
            coordinator ? "coordinator" : "script",
            scriptName,
            configDir);
  }

  // ---------------------------------------------------------------- process helpers

  /**
   * Builds the launch command. When the loader produced a launch descriptor ({@code
   * devHelper*Launch} task), the game runs as a plain {@code java} process launched directly by
   * this build — true OS-process parallelism with no child {@code ./gradlew} (so no Gradle
   * project-lock serialization between client and server). Falls back to invoking {@code
   * gradleTask} via the wrapper otherwise.
   *
   * @param systemProps extra {@code -Dkey=value} JVM system properties
   * @param useDisplay wrap the process in {@code xvfb-run} (client only — the dedicated server is
   *     headless and needs no display)
   */
  private ProcessBuilder launcherBuilder(
      String gradleTask, List<String> systemProps, Path descriptorFile, boolean useDisplay) {
    List<String> cmd = new ArrayList<>();
    if (useDisplay) {
      cmd.add(resolveExecutable("xvfb-run"));
      cmd.add("-a");
    }
    if (descriptorFile == null || !Files.isRegularFile(descriptorFile)) {
      getLogger()
          .lifecycle(
              "Minecheck: no launch descriptor at {}, falling back to wrapper task {}",
              descriptorFile,
              gradleTask);
      cmd.add(getGradleCommand().getOrElse("./gradlew"));
      cmd.add("-p");
      cmd.add(".");
      cmd.addAll(systemProps);
      cmd.add(gradleTask);
      cmd.add("--console=plain");
      return new ProcessBuilder(cmd);
    }

    Map<String, String> d = parseDescriptor(descriptorFile);
    String classpath = d.get("classpath");
    String mainClass = d.get("mainClass");
    if (classpath == null || mainClass == null) {
      throw new GradleException(
          "Minecheck: launch descriptor "
              + descriptorFile
              + " is missing classpath/mainClass; is it a devHelper*Launch output?");
    }
    String java = d.get("javaExecutable");
    cmd.add(java != null && !java.isBlank() ? java : javaExecutable());
    cmd.add("-cp");
    cmd.add(classpath);
    for (String p : systemProps) {
      cmd.add(p);
    }
    for (String p : splitList(d.get("jvmArgs"))) {
      cmd.add(p);
    }
    for (String s : decodeProps(splitList(d.get("systemProperties")))) {
      cmd.add("-D" + s);
    }
    cmd.add(mainClass);
    cmd.addAll(splitList(d.get("args")));

    ProcessBuilder pb = new ProcessBuilder(cmd);
    String wd = d.get("workingDir");
    if (wd != null && !wd.isBlank()) {
      pb.directory(new File(wd));
    }

    String env = d.get("environment");
    if (env != null && !env.isBlank()) {
      for (String entry : env.split("\\u0001")) {
        int eq = entry.indexOf('=');
        if (eq > 0) {
          pb.environment().put(entry.substring(0, eq), entry.substring(eq + 1));
        }
      }
    }
    return pb;
  }

  /**
   * The absolute path to the JVM's own {@code java} binary, so launching the game never depends on
   * the Gradle daemon's PATH (which is often minimal when the daemon was started by an IDE's gradle
   * server).
   */
  private static String javaExecutable() {
    String home = System.getProperty("java.home");
    if (home != null) {
      Path p = Path.of(home, "bin", "java");
      if (Files.isExecutable(p)) {
        return p.toString();
      }
    }
    return "java";
  }

  /**
   * Resolves an executable to an absolute path by scanning the daemon's PATH plus common locations,
   * falling back to the bare name (which will then rely on the child's PATH). Prevents ENOENT for
   * tools like {@code xvfb-run} that are on the user's login PATH but not on the Gradle daemon's.
   */
  private static String resolveExecutable(String name) {
    List<String> dirs = new ArrayList<>();
    String path = System.getenv("PATH");
    if (path != null) {
      for (String d : path.split(File.pathSeparator)) {
        if (!d.isBlank()) {
          dirs.add(d);
        }
      }
    }
    dirs.add("/usr/local/bin");
    dirs.add("/usr/bin");
    dirs.add("/bin");
    for (String d : dirs) {
      Path p = Path.of(d, name);
      if (Files.isExecutable(p)) {
        return p.toString();
      }
    }
    return name;
  }

  /** Parses the {@code key=value} descriptor file written by {@code devHelper*Launch}. */
  private static Map<String, String> parseDescriptor(Path f) {
    try {
      Map<String, String> m = new LinkedHashMap<>();
      for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
        int eq = line.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        m.put(line.substring(0, eq), line.substring(eq + 1));
      }
      return m;
    } catch (IOException e) {
      throw new GradleException("Minecheck: could not read launch descriptor " + f, e);
    }
  }

  /** Splits the descriptor's {@code ||}-joined list back into entries. */
  private static List<String> splitList(String s) {
    if (s == null || s.isBlank()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String part : s.split("\\|\\|")) {
      out.add(part.replace("\\||", "||"));
    }
    return out;
  }

  /** Turns {@code k=v} entries into {@code k=v} (just validates/splits on first {@code =}). */
  private static List<String> decodeProps(List<String> kv) {
    return kv; // already k=v from the descriptor
  }

  private Process start(ProcessBuilder pb, String label) throws IOException {
    if (pb.directory() == null) {
      pb.directory(getProject().getRootDir());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String lp = label == null ? "" : label;
    new Thread(
            () -> {
              try {
                InputStream in = p.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                  System.out.write(buf, 0, n);
                }
              } catch (IOException ignored) { }
            },
            "dh-forward-" + lp)
        .start();
    return p;
  }

  /** Recursively deletes a directory tree (no-op if absent). */
  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) { }
              });
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String world() {
    return getWorldName().getOrElse("minecheck_world");
  }

  private String port() {
    return getServerPort().getOrElse("25565");
  }

  /** Holds a FIFO open read-write so a reader on the server's stdin never sees EOF. */
  static final class ViaFifo {
    private RandomAccessFile raf;

    ViaFifo(String path) throws IOException {
      raf = new RandomAccessFile(path, "rw");
    }

    void close() {
      try {
        if (raf != null) {
          raf.close();
        }
      } catch (IOException ignored) { }
    }
  }
}
