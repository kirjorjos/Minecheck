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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
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
 * Verdict: the client run is the primary pass/fail and a server run is secondary <em>in role</em>,
 * not in whether its result is checked — a configured server script must still write a {@code
 * run-result.json} that passes. The task fails unless every {@code run-result.json} is {@code
 * finished && !failed} <em>and</em> every recorded {@code minecheck.expect} passed (a run that lost
 * an expectation is not green). The decision itself lives in {@link RunVerdict}, where it is unit
 * tested.
 *
 * <p><b>Up-to-date checks.</b> The launch descriptor only holds a classpath <em>string</em>: Gradle
 * cannot see through it to the code it points at, so without {@link #getModClasspathFiles()} and
 * {@link #getScriptFiles()} a source or script edit would leave the task UP-TO-DATE and silently
 * skip the run (reporting a stale green). Both are declared inputs for exactly that reason.
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

  /**
   * Optional client script that generates the world the server then runs on (the "from the client"
   * handoff). Setting it turns the run into two phases: this script runs first, the client quits,
   * and its save is staged into a setup dir; the server is then launched with {@code
   * -Ddh.serverSetupDir} pointing at it, so it boots the client-generated world instead of
   * generating one. The normal {@code script}/{@code coordinator} client phase follows and joins
   * that server.
   *
   * <p>Two client processes are needed because the handoff has to happen while no client has the
   * world open: a save copied out from under a running integrated server is a torn copy, and there
   * is no in-process way for Lua to say "the world is closed now" (a run only reports at its end).
   */
  @Input
  @Optional
  public abstract Property<String> getWorldGeneratorScript();

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
   * When true, the run boots with the deterministic screenshot profile: a pinned window size and
   * GUI scale, no VSync/frame pacing, no clouds, minimal particles, fixed FOV, chat and subtitles
   * off (unless {@link #getScreenshotChatVisible()}), and the tutorial disabled.
   *
   * <p>Off by default: the other lifecycle suites were written against vanilla defaults, and
   * silently changing what a click test sees would be exactly the kind of drift that makes a suite
   * lie.
   */
  @Input
  @Optional
  public abstract Property<Boolean> getScreenshotProfile();

  /** Captured frame width the profile pins the window to (default {@code 854}). */
  @Input
  @Optional
  public abstract Property<Integer> getScreenshotWidth();

  /** Captured frame height the profile pins the window to (default {@code 480}). */
  @Input
  @Optional
  public abstract Property<Integer> getScreenshotHeight();

  /** GUI scale the profile pins the HUD to (default {@code 2}, never auto). */
  @Input
  @Optional
  public abstract Property<Integer> getScreenshotGuiScale();

  /** Encode-time downscale the captures use (default {@code 1.0}). */
  @Input
  @Optional
  public abstract Property<Double> getScreenshotScale();

  /** Whether chat may appear in a capture (default {@code false}); the profile's escape hatch. */
  @Input
  @Optional
  public abstract Property<Boolean> getScreenshotChatVisible();

  /**
   * When true, the client's {@code screenshots/} directory is emptied before the run, so a capture
   * deleted from the script cannot linger and be compared against a stale baseline.
   */
  @Input
  @Optional
  public abstract Property<Boolean> getCleanScreenshots();

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

  /**
   * The mod code the run actually executes: every launch-classpath entry that lives inside the
   * project (the loader's {@code build/classes} + {@code build/resources}, the {@code :common} and
   * {@code :platform-api} jars, ...).
   *
   * <p>The descriptor holds that classpath as a <em>string</em>, so Gradle cannot see through it:
   * without this input, editing the mod leaves the lifecycle task UP-TO-DATE (compiling the
   * launcher does not invalidate this task) and it reports a stale green. Two classes of entry are
   * skipped: those outside the project (dependency caches, which only change when the dependency
   * does — and a dependency change rewrites the descriptor, itself an input) and anything under a
   * {@code .gradle} directory. The latter matters: unimined regenerates its remapping jars below
   * {@code .gradle/unimined/} on <em>every</em> build, so tracking them would leave this task
   * permanently out of date — the opposite failure. Read at execution-dependency time, i.e. after
   * the descriptor task has written the file.
   */
  @InputFiles
  @Optional
  @PathSensitive(PathSensitivity.RELATIVE)
  public FileCollection getModClasspathFiles() {
    List<File> files = new ArrayList<>();
    files.addAll(projectClasspathEntries(getClientLaunchFile()));
    files.addAll(projectClasspathEntries(getServerLaunchFile()));
    return getProject().files(files);
  }

  /** True when any segment of {@code relative} is a {@code .gradle} directory. */
  private static boolean containsGradleDir(Path relative) {
    for (Path segment : relative) {
      if (segment.toString().equals(".gradle")) {
        return true;
      }
    }
    return false;
  }

  /** The project-local entries of a launch descriptor's {@code classpath=} line, in order. */
  private List<File> projectClasspathEntries(RegularFileProperty prop) {
    Path descriptor = descriptorPath(prop);
    if (descriptor == null || !Files.isRegularFile(descriptor)) {
      return List.of();
    }
    List<File> entries = new ArrayList<>();
    try {
      for (String line : Files.readAllLines(descriptor)) {
        if (!line.startsWith("classpath=")) {
          continue;
        }
        for (String entry : line.substring("classpath=".length()).split(File.pathSeparator)) {
          File file = new File(entry);
          Path path = file.toPath().toAbsolutePath();
          Path root = getProject().getRootProject().getProjectDir().toPath().toAbsolutePath();
          if (path.startsWith(root)
              && !containsGradleDir(root.relativize(path))
              && file.exists()
              && !entries.contains(file)) {
            entries.add(file);
          }
        }
      }
    } catch (IOException e) {
      throw new GradleException("could not read the launch descriptor " + descriptor, e);
    }
    return entries;
  }

  /**
   * The Lua scripts that drive the run (the contents of {@link #getScriptsDir()}): their assertions
   * are the verdict, so editing one must re-run the lifecycle test — see the class javadoc.
   */
  @InputFiles
  @Optional
  @PathSensitive(PathSensitivity.RELATIVE)
  public FileCollection getScriptFiles() {
    String dir = getScriptsDir().getOrNull();
    return dir == null ? getProject().files() : getProject().fileTree(dir);
  }

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
      deleteRecursively(runDir.resolve("saves").resolve(world()));
      if (getCleanScreenshots().getOrElse(false)) {
        deleteRecursively(runDir.resolve("screenshots"));
      }
      stageClientOptions(runDir);
      if (getServerScript().isPresent()) {
        Path setupDir = null;
        if (getWorldGeneratorScript().isPresent()) {
          stage(configDir, getWorldGeneratorScript().get(), false);
          runClientOnce(configDir, "generate");
          setupDir = stageGeneratedWorld(runDir);
        }
        runParallel(configDir, setupDir);
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

  /**
   * Copies the world the generator client saved into a setup dir the server stages at boot.
   *
   * <p>{@code session.lock} is skipped: it is the integrated server's lock file, meaningless (and
   * actively misleading) in the server's run dir. Everything else, {@code level.dat} first among
   * them, is what makes it the same world.
   */
  private Path stageGeneratedWorld(Path runDir) throws IOException {
    Path source = runDir.resolve("saves").resolve(world());
    if (!Files.isRegularFile(source.resolve("level.dat"))) {
      throw new GradleException(
          "Minecheck: the world generator script ran but left no world at "
              + source
              + " — it must create the world named \""
              + world()
              + "\"");
    }
    Path setup =
        getProject()
            .getLayout()
            .getBuildDirectory()
            .dir("minecheck/server-world")
            .get()
            .getAsFile()
            .toPath();
    deleteRecursively(setup);
    // A dedicated server keeps its level at `<run dir>/<level-name>`, not under `saves/` (that is
    // the singleplayer layout), so the setup dir mirrors the server's run dir, not the client's.
    Path target = setup.resolve(world());
    try (var stream = Files.walk(source)) {
      for (Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
        Path p = it.next();
        if (p.getFileName().toString().equals("session.lock")) {
          continue;
        }
        Path dest = target.resolve(source.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(dest);
        } else {
          Files.createDirectories(dest.getParent());
          Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    getLogger().lifecycle("Minecheck: staged the client-generated world {} into {}", source, setup);
    return setup;
  }

  /**
   * Stages the server, then launches client + server together, waits for both.
   *
   * <p>When {@code serverSetupDir} is set the server is launched with {@code -Ddh.serverSetupDir},
   * so it stages that tree into its run dir before Minecheck resolves its script — i.e. it boots
   * the client-generated world instead of generating one.
   */
  private void runParallel(Path clientConfigDir, Path serverSetupDir) throws IOException {
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
            "Minecheck: [server] launching {} (script={}) with {}",
            getServerLoaderTask().get(),
            serverScript,
            serverSetupDir == null
                ? "a pinned deterministic world"
                : "the client-generated world from " + serverSetupDir);
    Files.deleteIfExists(serverResult);
    Process server =
        start(
            launcherBuilder(
                getServerLoaderTask().get(),
                serverSetupDir == null
                    ? List.of()
                    : List.of("-Ddh.serverSetupDir=" + serverSetupDir),
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
    // A configured server script's verdict is required, not optional: "secondary" describes its
    // role in the pairing, not whether its result is checked. A server that exits without writing
    // one has proved nothing, and skipping it here is exactly how a run goes green while half of it
    // was never verified.
    requirePassed(serverVerdict, "server-script");
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
    StringBuilder options = new StringBuilder();
    options.append("renderDistance:10\n").append("simulationDistance:10\n");
    if (screenshotProfile()) {
      // The deterministic capture profile. Everything here either changes what is drawn (graphics,
      // FOV, particles, clouds, GUI scale, window size) or when it is drawn (VSync, FPS cap), and
      // every one of those would make the same script produce a different PNG on a different run.
      // Authored settings that cannot be expressed here (time of day, weather, the world's seed)
      // belong to the script that created the world.
      options.append("overrideWidth:").append(screenshotWidth()).append('\n');
      options.append("overrideHeight:").append(screenshotHeight()).append('\n');
      options.append("guiScale:").append(screenshotGuiScale()).append('\n');
      options.append("forceUnicodeFont:false\n");
      options.append("graphicsMode:1\n"); // fancy, never fabulous
      options.append("ao:true\n");
      options.append("entityShadows:true\n");
      options.append("biomeBlendRadius:0\n");
      options.append("renderClouds:false\n");
      options.append("particles:2\n"); // minimal
      options.append("fov:70\n");
      options.append("fovEffectScale:0.0\n");
      options.append("screenEffectScale:0.0\n");
      options.append("bobView:false\n");
      options.append("gamma:0.5\n");
      options.append("enableVsync:false\n");
      options.append("maxFps:120\n");
      options.append("fullscreen:false\n");
      options.append("showSubtitles:false\n");
      options.append("reducedDebugInfo:false\n");
      options.append("pauseOnLostFocus:false\n");
      // Chat stays *enabled* here even when captures must not contain it: the server refuses every
      // command from a client whose chat visibility is hidden ("chat.disabled.options"), and a run
      // that cannot run commands cannot set up a scene. The capture empties the chat lines itself
      // and reads the first frame drawn afterwards.
      options.append("chatVisibility:0\n");
      // Tutorial toasts and the multiplayer warning are timing-dependent overlays.
      options.append("tutorialStep:none\n");
      options.append("hideBundleTutorial:true\n");
      options.append("skipMultiplayerWarning:true\n");
      options.append("joinedFirstServer:true\n");
      // The system locale would otherwise pick the UI language.
      options.append("lang:en_us\n");
    }
    Files.writeString(runDir.resolve("options.txt"), options.toString());
    getLogger()
        .lifecycle(
            "Minecheck: pinned client options.txt (render/simulation distance 10{}) in {}",
            screenshotProfile()
                ? ", screenshot profile "
                    + screenshotWidth()
                    + "x"
                    + screenshotHeight()
                    + " guiScale "
                    + screenshotGuiScale()
                : "",
            runDir);
  }

  // ---------------------------------------------------------------- screenshot profile

  private boolean screenshotProfile() {
    return getScreenshotProfile().getOrElse(false);
  }

  private int screenshotWidth() {
    return getScreenshotWidth().getOrElse(854);
  }

  private int screenshotHeight() {
    return getScreenshotHeight().getOrElse(480);
  }

  private int screenshotGuiScale() {
    return getScreenshotGuiScale().getOrElse(2);
  }

  private double screenshotScale() {
    return getScreenshotScale().getOrElse(1.0);
  }

  private boolean screenshotChatVisible() {
    return getScreenshotChatVisible().getOrElse(false);
  }

  /**
   * Builds the {@code server.properties} the plugin hands the server.
   *
   * <p>{@code spawn-animals} / {@code spawn-monsters} are deliberately NOT set to false here:
   * vanilla 1.19.2's {@code ServerWorld.shouldCancelSpawn} DISCARDS any animal/monster that
   * entity-ticks while they're false — including entities a server script spawns in the ticking
   * area around the joined player (they'd vanish within a second). Determinism is instead provided
   * by the loader's {@code doMobSpawning=false} gamerule (no dynamic spawns after worldgen) and the
   * pinned {@code level-seed}; worldgen still bakes animals into chunks either way, so scripts must
   * already target spawned entities by id/tag rather than whole-world counts.
   */
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
    RunVerdict verdict = RunVerdict.of(result, getExpectFailure().getOrElse(false));
    if (verdict.isFailure()) {
      throw new GradleException(
          "Minecheck lifecycle test FAILED (" + what + "): " + verdict.detail());
    }
    getLogger().lifecycle("Minecheck: {} {}", what, verdict.summary());
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

    Map<String, Object> config = new LinkedHashMap<>();
    config.put(coordinator ? "coordinator" : "script", scriptName);
    config.put("failMode", "exit");
    config.put("defaultTimeout", 60);
    if (screenshotProfile()) {
      // The same profile the options.txt above pins, told to the mod: it needs these for the
      // capture itself (which frame size to expect, the GUI scale for `unit = "gui"` regions) and
      // writes them into profile.json so a baseline records the settings that produced it.
      config.put("screenshotWidth", screenshotWidth());
      config.put("screenshotHeight", screenshotHeight());
      config.put("screenshotGuiScale", screenshotGuiScale());
      config.put("screenshotScale", screenshotScale());
      config.put("screenshotChatVisible", screenshotChatVisible());
    }
    Files.writeString(configDir.resolve("config.json"), GSON.toJson(config) + "\n");
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
    for (String p : LaunchDescriptor.splitList(d.get("jvmArgs"))) {
      cmd.add(p);
    }
    for (String s : LaunchDescriptor.splitList(d.get("systemProperties"))) {
      cmd.add("-D" + s);
    }
    cmd.add(mainClass);
    cmd.addAll(LaunchDescriptor.splitList(d.get("args")));

    ProcessBuilder pb = new ProcessBuilder(cmd);
    String wd = d.get("workingDir");
    if (wd != null && !wd.isBlank()) {
      pb.directory(new File(wd));
    }

    pb.environment().putAll(LaunchDescriptor.parseEnvironment(d.get("environment")));
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
