package net.minecheck.common.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A dedicated server this process starts, watches and stops, from the {@code minecheck.startServer}
 * script surface.
 *
 * <p>The harness stages the launch descriptor and a server template; this class is what turns them
 * into a running JVM. It is deliberately free of game types — a child process, its log and its
 * stdin are plain Java — so a suite's "start the server after the integrated phases passed" costs
 * the same code on every loader.
 *
 * <p><b>Why the client starts it.</b> The parallel shape launches the server and the client
 * together, so the server boots before the client has proved anything, and a client failure leaves
 * a server process behind for the harness to reap. Starting it from the script lets the integrated
 * phases pass first, and the process is this object's to stop, on every path out of the run (a
 * shutdown hook covers the one where the client dies without saying so).
 */
public final class ServerProcess {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** How often the child's log is re-read while it is starting. */
  private static final long POLL_MILLIS = 250L;

  /** How long the child may sit past {@code Stop} before it is killed outright. */
  private static final long KILL_GRACE_MILLIS = 15_000L;

  /** What the script sees. */
  public enum Status {
    STOPPED,
    STARTING,
    READY,
    FAILED;

    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final Path descriptor;
  private final Path runDir;
  private final Path templateDir;
  private final String port;
  private final String world;

  private Process process;
  private Path logFile;
  private Status status = Status.STOPPED;
  private String failure;
  private long lastPoll;

  private ServerProcess(Path descriptor, Path runDir, Path templateDir, String port, String world) {
    this.descriptor = descriptor;
    this.runDir = runDir;
    this.templateDir = templateDir;
    this.port = port;
    this.world = world;
  }

  /**
   * The server this run can start, from the harness's system properties, or {@code null} when the
   * build did not stage one — a client-only run in a repository that has no dedicated server, or a
   * run outside the harness entirely.
   */
  public static ServerProcess fromSystemProperties() {
    Path descriptor = path("minecheck.serverLaunch");
    Path runDir = path("minecheck.serverRunDir");
    Path template = path("minecheck.serverTemplate");
    if (descriptor == null || runDir == null) {
      return null;
    }
    return new ServerProcess(
        descriptor,
        runDir,
        template,
        System.getProperty("minecheck.serverPort", "25565"),
        System.getProperty("minecheck.serverWorld", "minecheck_world"));
  }

  private static Path path(String property) {
    String value = System.getProperty(property);
    return value == null || value.isBlank() ? null : Path.of(value);
  }

  public Status status() {
    if (process == null) {
      return status;
    }
    if (!process.isAlive() && status == Status.STARTING) {
      status = Status.FAILED;
      failure = "the server process exited before it reported Done (see " + logFile + ")";
      return status;
    }
    if (status == Status.STARTING && System.currentTimeMillis() - lastPoll >= POLL_MILLIS) {
      lastPoll = System.currentTimeMillis();
      if (logReportedDone()) {
        status = Status.READY;
      }
    }
    return status;
  }

  public String failure() {
    return failure;
  }

  /**
   * Stages and launches the server.
   *
   * @param scriptsDir the directory the running client's scripts came from
   * @param script the script the server runs, relative to {@code scriptsDir}
   * @param setupDir a world tree to boot instead of generating one, or {@code null}
   * @param world the level name to boot (the server's own folder)
   */
  public boolean start(Path scriptsDir, String script, Path setupDir, String world) {
    if (process != null && process.isAlive()) {
      return true;
    }
    failure = null;
    try {
      Map<String, String> launch = parse(descriptor);
      String classpath = launch.get("classpath");
      String mainClass = launch.get("mainClass");
      if (classpath == null || mainClass == null) {
        failure = "the launch descriptor " + descriptor + " has no classpath/mainClass";
        status = Status.FAILED;
        return false;
      }
      stage(scriptsDir, script, setupDir, world);

      List<String> command = new ArrayList<>();
      String java = launch.get("javaExecutable");
      command.add(java == null || java.isBlank() ? "java" : java);
      command.add("-cp");
      command.add(classpath);
      command.addAll(split(launch.get("jvmArgs")));
      for (String property : split(launch.get("systemProperties"))) {
        command.add("-D" + property);
      }
      command.add(mainClass);
      command.addAll(split(launch.get("args")));

      ProcessBuilder builder = new ProcessBuilder(command);
      String workingDir = launch.get("workingDir");
      if (workingDir != null && !workingDir.isBlank()) {
        builder.directory(Path.of(workingDir).toFile());
      }
      builder.environment().putAll(environment(launch.get("environment")));
      Path logs = runDir.resolve("logs");
      Files.createDirectories(logs);
      logFile = logs.resolve("minecheck-server.log");
      builder.redirectErrorStream(true);
      builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

      process = builder.start();
      status = Status.STARTING;
      lastPoll = 0;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    Process p = process;
                    if (p != null && p.isAlive()) {
                      p.destroyForcibly();
                    }
                  },
                  "minecheck-server-reaper"));
      LOG.info(
          String.format(
              "Minecheck: started the dedicated server (pid %d, port %s, script %s) -> %s",
              process.pid(), port, script, logFile));
      return true;
    } catch (IOException | RuntimeException e) {
      failure = e.getMessage();
      status = Status.FAILED;
      LOG.log(Level.SEVERE, "Minecheck: could not start the dedicated server", e);
      return false;
    }
  }

  /** Waits (in real time) until the server reports Done, exits, or the budget runs out. */
  public boolean waitFor(int timeoutSeconds) {
    long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
    while (System.currentTimeMillis() < deadline) {
      Status now = status();
      if (now == Status.READY) {
        return true;
      }
      if (now == Status.FAILED) {
        return false;
      }
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    failure = "the server did not report Done within " + timeoutSeconds + "s";
    return false;
  }

  /** True while the child JVM is up. */
  public boolean running() {
    return process != null && process.isAlive();
  }

  /**
   * Writes {@code stop} to the child's console and returns at once.
   *
   * <p>Separate from {@link #stop(int)} because this is called from the client thread, inside a Lua
   * action: blocking there for the seconds a server needs to save would freeze the game. A polling
   * caller yields a tick at a time and watches {@link #running()}. Once the child is gone its state
   * is {@link Status#STOPPED}, so the next {@link #status()} answers that.
   */
  public boolean requestStop() {
    if (process == null || !process.isAlive()) {
      status = Status.STOPPED;
      return false;
    }
    try {
      process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().flush();
      return true;
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Minecheck: could not ask the dedicated server to stop", e);
      return false;
    }
  }

  /** Kills the child outright, after a polite stop was ignored. */
  public void kill() {
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
    }
    status = Status.STOPPED;
  }

  /** Asks the child to stop and waits for it; false when it had to be killed. */
  public boolean stop(int timeoutSeconds) {
    if (!requestStop()) {
      status = Status.STOPPED;
      return true;
    }
    long waitMillis = Math.max(1, timeoutSeconds) * 1000L;
    try {
      if (process.waitFor(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        status = Status.STOPPED;
        return true;
      }
      LOG.warning(
          "Minecheck: the dedicated server did not stop in " + timeoutSeconds + "s — killing it");
      process.destroy();
      if (process.waitFor(KILL_GRACE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        status = Status.STOPPED;
        return false;
      }
      process.destroyForcibly();
      process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
      status = Status.STOPPED;
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      status = Status.STOPPED;
      return false;
    }
  }

  // ---------------------------------------------------------------- staging

  private void stage(Path scriptsDir, String script, Path setupDir, String levelName)
      throws IOException {
    Files.createDirectories(runDir);
    if (templateDir != null && Files.isDirectory(templateDir)) {
      try (var stream = Files.list(templateDir)) {
        for (Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
          Path file = it.next();
          if (Files.isRegularFile(file)) {
            Files.copy(
                file, runDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
    // eula.txt in case the template predates it or the build staged none.
    Files.writeString(runDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);

    Path configDir = runDir.resolve("config/minecheck");
    Path stagedScripts = configDir.resolve("scripts");
    Files.createDirectories(stagedScripts);
    Path source = scriptsDir.resolve(script);
    if (!Files.isRegularFile(source)) {
      throw new IOException("no script named " + script + " in " + scriptsDir);
    }
    try (var stream = Files.list(scriptsDir)) {
      for (Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
        Path file = it.next();
        if (Files.isRegularFile(file)) {
          Files.copy(
              file, stagedScripts.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("script", script);
    config.put("failMode", "exit");
    config.put("defaultTimeout", 300);
    Files.writeString(
        configDir.resolve("config.json"),
        new com.google.gson.Gson().toJson(config) + "\n",
        StandardCharsets.UTF_8);

    if (setupDir != null) {
      Path level = runDir.resolve(levelName);
      deleteTree(level);
      copyTree(setupDir, level);
    }
  }

  private static void copyTree(Path source, Path target) throws IOException {
    try (var stream = Files.walk(source)) {
      for (Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
        Path p = it.next();
        if (p.getFileName() != null && p.getFileName().toString().equals("session.lock")) {
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
  }

  private static void deleteTree(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  // ---------------------------------------------------------------- descriptor

  /** The child's log says it is up: the same line the harness and a person look for. */
  private boolean logReportedDone() {
    if (logFile == null || !Files.isRegularFile(logFile)) {
      return false;
    }
    try {
      return Files.readString(logFile, StandardCharsets.UTF_8).contains("Done (");
    } catch (IOException e) {
      return false;
    }
  }

  /** {@code key=value} lines, exactly as the harness's launch descriptor task writes them. */
  static Map<String, String> parse(Path descriptor) throws IOException {
    Map<String, String> fields = new LinkedHashMap<>();
    for (String line : Files.readAllLines(descriptor, StandardCharsets.UTF_8)) {
      int eq = line.indexOf('=');
      if (eq > 0) {
        fields.put(line.substring(0, eq), line.substring(eq + 1));
      }
    }
    return fields;
  }

  /** A list field, joined with NUL by the writer; empty when absent. */
  static List<String> split(String value) {
    if (value == null || value.isEmpty()) {
      return List.of();
    }
    List<String> entries = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) == '\u0000') {
        entries.add(value.substring(start, i));
        start = i + 1;
      }
    }
    if (start < value.length()) {
      entries.add(value.substring(start));
    }
    return entries;
  }

  /** The environment field: {@code KEY=VALUE} entries joined with \u0001. */
  private static Map<String, String> environment(String value) {
    Map<String, String> env = new LinkedHashMap<>();
    if (value == null || value.isEmpty()) {
      return env;
    }
    for (String entry : value.split("\u0001", -1)) {
      int eq = entry.indexOf('=');
      if (eq > 0) {
        env.put(entry.substring(0, eq), entry.substring(eq + 1));
      }
    }
    return env;
  }
}
