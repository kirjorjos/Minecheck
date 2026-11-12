package net.minecheck.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Boots each packaged loader jar in an installed dedicated server (issue #103).
 *
 * <p>{@code checkProductionJars} only proves a jar carries production member names, and the
 * lifecycle suites boot the <em>development</em> classpath. Neither proves that the jar attached to
 * a release loads in an instance a user installs it into, which is the one thing a user does with
 * it. This does: for every loader the line ships it installs a real dedicated server at the loader
 * version the branch builds against, puts the packaged jar (never its {@code -deobf} twin, which is
 * development names by definition) in {@code mods/}, boots it, waits for the server to finish
 * starting, and requires Minecheck to report itself active. Anything else fails.
 *
 * <p>Every version this needs — the loader, and the Fabric API / QFAPI the jar's metadata makes
 * mandatory — is read from the branch's own build files, so one task serves every Minecraft line.
 * The API jars come from Modrinth because their Maven artifacts are metadata stubs: the jar that
 * actually loads is the bundled distribution, and the pinned version still comes from the build
 * file, so the boot exercises the API version the jar was compiled against.
 */
public abstract class MinecheckProductionBoot extends DefaultTask {

  private static final String FABRIC_INSTALLER = "https://meta.fabricmc.net/v2/versions/installer";
  private static final String FABRIC_LAUNCHER =
      "https://meta.fabricmc.net/v2/versions/loader/%s/%s/%s/server/jar";
  private static final String QUILT_INSTALLER =
      "https://quiltmc.org/api/v1/download-latest-installer/java-universal";
  private static final String FORGE_INSTALLER =
      "https://maven.minecraftforge.net/net/minecraftforge/forge/%s-%s/forge-%s-%s-installer.jar";
  // Built by concatenation, not String.format: the percent-encoded `[` would read as a conversion.
  private static final String MODRINTH_PROJECT = "https://api.modrinth.com/v2/project/";
  private static final String USER_AGENT =
      "Minecheck-release-check (+https://github.com/kirjorjos/Minecheck)";

  /** The server prints this once its world is up, after every mod has loaded. */
  private static final String STARTED = "Done (";

  /** What the mod logs when it is running in a server, so its absence means "not loaded". */
  private static final String ACTIVE = "Minecheck server active";

  private static final List<String> CRASHED =
      List.of("Failed to initialize Minecheck", "Minecraft has crashed", "Mod resolution failed");

  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(60))
          .build();

  private static final Pattern FABRIC_LOADER =
      Pattern.compile("fabric\\s*\\{\\s*loader\\(\"([^\"]+)\"\\)");
  private static final Pattern FABRIC_API = Pattern.compile("fabric-api:fabric-api:([^\"\\s]+)");
  private static final Pattern QUILT_LOADER =
      Pattern.compile("quilt\\s*\\{\\s*loader\\(\"([^\"]+)\"\\)");
  private static final Pattern QFAPI =
      Pattern.compile("quilted-fabric-api:quilted-fabric-api:([^\"\\s]+)");
  private static final Pattern FORGE_LOADER =
      Pattern.compile("minecraftForge\\s*\\{\\s*loader\\(\"([^\"]+)\"\\)");

  /** The Minecraft version this line builds, e.g. 1.18.2. */
  @Input
  public abstract Property<String> getMinecraftVersion();

  /** The mod version, which is part of every release asset's name. */
  @Input
  public abstract Property<String> getModVersion();

  /** {@code .github/metadata.json}: the loader list and the release asset prefix. */
  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract RegularFileProperty getMetadataFile();

  /** The repository root, whose {@code src/<loader>/mod/build.gradle.kts} pins the versions. */
  @Internal
  public abstract DirectoryProperty getProjectRoot();

  /** The staged release jars ({@code collectArtifacts}' {@code build/artifacts}). */
  @Internal
  public abstract DirectoryProperty getArtifactsDir();

  /** The staged jars, declared so a rebuild of them re-runs the boot. */
  @InputFiles
  @PathSensitive(PathSensitivity.NONE)
  public abstract ConfigurableFileCollection getArtifactJars();

  /** Where the servers are installed; one subdirectory per Minecraft version and loader. */
  @Internal
  public abstract DirectoryProperty getWorkRoot();

  /** Comma-separated loader ids to boot; empty boots every loader the line ships. */
  @Input
  @Optional
  public abstract Property<String> getOnlyLoaders();

  /** The {@code java} executable the servers run on; a line's own JDK, not the daemon's. */
  @Input
  @Optional
  public abstract Property<String> getJavaExecutable();

  /** Seconds a server may take to finish starting. */
  @Input
  @Optional
  public abstract Property<Integer> getBootTimeoutSeconds();

  @TaskAction
  public void boot() {
    Path root = getProjectRoot().get().getAsFile().toPath();
    String minecraft = getMinecraftVersion().get();
    String version = getModVersion().get();
    Path artifacts = getArtifactsDir().get().getAsFile().toPath();
    Path workRoot = getWorkRoot().get().getAsFile().toPath().resolve(minecraft + "-" + version);

    JsonObject metadata = readMetadata();
    String prefix = required(release(metadata), "prefix");
    List<String> loaders = selectedLoaders(metadata);

    getLogger().lifecycle("Minecheck: booting {} v{} from {}", minecraft, version, artifacts);
    for (String loader : loaders) {
      bootLoader(
          root,
          loader,
          minecraft,
          version,
          jarFor(artifacts, prefix, loader, minecraft, version),
          workRoot.resolve(loader));
    }
    getLogger()
        .lifecycle(
            "Minecheck: every packaged jar for {} v{} boots: {}",
            minecraft,
            version,
            String.join(", ", loaders));
  }

  private void bootLoader(
      Path root, String loader, String minecraft, String version, Path jar, Path workdir) {
    getLogger().lifecycle("Minecheck: == {} {}: {}", loader, minecraft, jar.getFileName());
    Path mods = workdir.resolve("mods");
    try {
      Files.createDirectories(mods);
      try (Stream<Path> stale = Files.list(mods)) {
        for (Path file : stale.collect(Collectors.toList())) {
          Files.deleteIfExists(file);
        }
      }
      Files.copy(jar, mods.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      writeServerFiles(workdir, minecraft);
    } catch (IOException e) {
      throw failure("could not stage " + jar.getFileName() + " in " + workdir + ": " + e);
    }

    String buildFile = readBuildFile(root, loader);
    String java = getJavaExecutable().get();
    List<String> command;
    switch (loader) {
      case "fabric" -> {
        String loaderVersion = match(FABRIC_LOADER, buildFile, "Fabric loader version", "fabric");
        // The jar's metadata makes Fabric API mandatory, and a production instance has to be given
        // it: the development run gets it from the module classpath instead.
        String api = match(FABRIC_API, buildFile, "Fabric API version", "fabric");
        downloadModrinth("fabric-api", minecraft, api, mods.resolve("fabric-api-" + api + ".jar"));
        getLogger().lifecycle("Minecheck:   loader {}, fabric-api {}", loaderVersion, api);
        command = installFabric(workdir, minecraft, loaderVersion, java);
      }
      case "quilt" -> {
        String loaderVersion = match(QUILT_LOADER, buildFile, "Quilt loader version", "quilt");
        String api = match(QFAPI, buildFile, "QFAPI version", "quilt");
        downloadModrinth("qsl", minecraft, api, mods.resolve("quilted-fabric-api-" + api + ".jar"));
        getLogger().lifecycle("Minecheck:   loader {}, quilted-fabric-api {}", loaderVersion, api);
        command = installQuilt(workdir, minecraft, loaderVersion, java);
      }
      case "forge" -> {
        String loaderVersion = match(FORGE_LOADER, buildFile, "Forge loader version", "forge");
        getLogger().lifecycle("Minecheck:   loader {}", loaderVersion);
        command = installForge(workdir, minecraft, loaderVersion, java);
      }
      default -> throw failure("no production boot is defined for loader '" + loader + "'");
    }

    Path logPath = workdir.resolve("boot.log");
    String text = run(workdir, command, logPath, getBootTimeoutSeconds().get(), true);
    checkLog(text, loader, jar, logPath);
    getLogger()
        .lifecycle(
            "Minecheck:   {} loaded {} and Minecheck is active ({})",
            loader,
            jar.getFileName(),
            logPath);
  }

  private JsonObject readMetadata() {
    Path path = getMetadataFile().get().getAsFile().toPath();
    try {
      JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
      if (!parsed.isJsonObject()) {
        throw failure(path + " is not a JSON object");
      }
      return parsed.getAsJsonObject();
    } catch (IOException e) {
      throw failure(path + " cannot be read: " + e);
    }
  }

  private static JsonObject release(JsonObject metadata) {
    JsonElement release = metadata.get("release");
    return release != null && release.isJsonObject() ? release.getAsJsonObject() : new JsonObject();
  }

  private List<String> selectedLoaders(JsonObject metadata) {
    JsonElement listed = metadata.get("loaders");
    if (listed == null || !listed.isJsonArray() || listed.getAsJsonArray().isEmpty()) {
      throw failure(getMetadataFile().get().getAsFile() + " names no loaders");
    }
    List<String> loaders = new ArrayList<>();
    for (JsonElement entry : listed.getAsJsonArray()) {
      loaders.add(entry.getAsString());
    }
    String only = getOnlyLoaders().getOrElse("").trim();
    if (only.isEmpty()) {
      return loaders;
    }
    List<String> wanted = new ArrayList<>();
    for (String name : only.split(",")) {
      String loader = name.trim();
      if (loader.isEmpty()) {
        continue;
      }
      if (!loaders.contains(loader)) {
        throw failure(
            loader + " is not a loader this line ships (" + String.join(", ", loaders) + ")");
      }
      wanted.add(loader);
    }
    return wanted;
  }

  private static String required(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || value.isJsonNull() || value.getAsString().isEmpty()) {
      throw new GradleException("Minecheck: .github/metadata.json names no release." + key);
    }
    return value.getAsString();
  }

  /**
   * The staged jar for one loader. The release name is preferred; a local build is stamped {@code
   * -dev-<n>} instead, which is the same code under a name that never reaches a release, so a
   * developer's run boots that rather than reporting an asset the release staged fine.
   */
  private static Path jarFor(
      Path artifacts, String prefix, String loader, String minecraft, String version) {
    Path jar = artifacts.resolve(prefix + loader + "-" + minecraft + "-" + version + ".jar");
    if (Files.isRegularFile(jar)) {
      return jar;
    }
    String stem = prefix + loader + "-" + minecraft + "-" + version + "-dev-";
    try (Stream<Path> staged = Files.list(artifacts)) {
      java.util.Optional<Path> dev =
          staged
              .filter(path -> path.getFileName().toString().startsWith(stem))
              .filter(path -> path.getFileName().toString().endsWith(".jar"))
              .max(Comparator.comparingLong(path -> path.toFile().lastModified()));
      if (dev.isPresent()) {
        return dev.get();
      }
    } catch (IOException e) {
      throw failure("could not list " + artifacts + ": " + e);
    }
    throw failure(
        jar
            + " is missing; the "
            + loader
            + " release asset was never staged (run collectArtifacts first).");
  }

  private static String readBuildFile(Path root, String loader) {
    Path path = root.resolve("src").resolve(loader).resolve("mod").resolve("build.gradle.kts");
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new GradleException(
          "Minecheck: "
              + path
              + " is missing; cannot read the "
              + loader
              + " versions this jar was built against");
    }
  }

  private static String match(Pattern pattern, String text, String what, String loader) {
    Matcher found = pattern.matcher(text);
    if (!found.find()) {
      throw new GradleException(
          "Minecheck: the "
              + loader
              + " build file names no "
              + what
              + " (pattern "
              + pattern
              + ")");
    }
    return found.group(1);
  }

  private void writeServerFiles(Path workdir, String minecraft) {
    try {
      Files.createDirectories(workdir);
      Files.writeString(
          workdir.resolve("eula.txt"),
          "# Accepted by bootPackagedJars for the " + minecraft + " release check.\neula=true\n",
          StandardCharsets.UTF_8);
      Map<String, String> properties = new LinkedHashMap<>();
      // The mod stages no world of its own and this run has no players: a small view distance and
      // no command blocks keep the boot to reading the jar rather than generating terrain.
      properties.put("online-mode", "false");
      properties.put("enable-command-block", "false");
      properties.put("spawn-protection", "0");
      properties.put("view-distance", "4");
      properties.put("simulation-distance", "4");
      properties.put("level-seed", "0");
      properties.put("sync-chunk-writes", "false");
      if (minecraft.compareTo("1.19") >= 0) {
        // 1.19 refuses to start a server without a profile key policy decision when this is left at
        // its default; the check signs nobody in, so the policy is irrelevant here.
        properties.put("enforce-secure-profile", "false");
      }
      String body =
          properties.entrySet().stream()
              .map(entry -> entry.getKey() + "=" + entry.getValue())
              .collect(Collectors.joining("\n"));
      Files.writeString(workdir.resolve("server.properties"), body + "\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw failure("could not write the server files in " + workdir + ": " + e);
    }
  }

  private List<String> installFabric(Path workdir, String minecraft, String loader, String java) {
    String installer = fabricInstallerVersion();
    getLogger().lifecycle("Minecheck:   installer {}", installer);
    download(
        String.format(FABRIC_LAUNCHER, minecraft, loader, installer),
        workdir.resolve("fabric-server.jar"));
    return List.of(java, "-Xmx1536M", "-jar", "fabric-server.jar", "nogui");
  }

  private List<String> installQuilt(Path workdir, String minecraft, String loader, String java) {
    Path installer = workdir.resolve("quilt-installer.jar");
    download(QUILT_INSTALLER, installer);
    // The loader version is positional (`install server <mc> [<loader>]`), and options must be
    // `--flag=value`: the installer treats anything else as a usage error and exits 0 having
    // installed nothing, which is the trap this check fell into before.
    run(
        workdir,
        List.of(
            java,
            "-jar",
            installer.getFileName().toString(),
            "install",
            "server",
            minecraft,
            loader,
            "--install-dir=" + workdir.toAbsolutePath(),
            "--download-server",
            "--create-scripts"),
        workdir.resolve("install.log"),
        900,
        false);

    Path launcher = workdir.resolve("quilt-server-launch.jar");
    if (!Files.isRegularFile(launcher)) {
      throw failure("the Quilt installer produced no " + launcher.getFileName() + " in " + workdir);
    }
    // The same workaround the Quilt build applies to its dev runs: QFAPI's rendering-fluids module
    // carries a `quilt_loader.recommends` key that this loader rejects as invalid metadata.
    return List.of(
        java,
        "-Xmx1536M",
        "-Dloader.workaround.disable_strict_parsing=true",
        "-jar",
        launcher.getFileName().toString(),
        "nogui");
  }

  private List<String> installForge(Path workdir, String minecraft, String version, String java) {
    Path installer = workdir.resolve("forge-" + minecraft + "-" + version + "-installer.jar");
    download(String.format(FORGE_INSTALLER, minecraft, version, minecraft, version), installer);
    run(
        workdir,
        List.of(java, "-jar", installer.getFileName().toString(), "--installServer"),
        workdir.resolve("install.log"),
        1200,
        false);
    Path libraries =
        workdir.resolve("libraries/net/minecraftforge/forge/" + minecraft + "-" + version);
    if (!Files.isDirectory(libraries)) {
      throw failure("the Forge installer produced no " + libraries + " in " + workdir);
    }
    try {
      Files.writeString(
          workdir.resolve("user_jvm_args.txt"), "-Xmx1536M\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw failure("could not write user_jvm_args.txt in " + workdir + ": " + e);
    }
    return List.of("bash", "run.sh", "nogui");
  }

  /**
   * Runs a command in {@code workdir}. When {@code waitForStart} is set the command is the server
   * itself: it is stopped with {@code stop} once it reports {@link #STARTED}, or killed when it
   * never does, and the log is returned. Otherwise the caller reads {@code install.log}.
   */
  private String run(
      Path workdir, List<String> command, Path logPath, int timeoutSeconds, boolean waitForStart) {
    getLogger().lifecycle("Minecheck:   $ {}", String.join(" ", command));
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(workdir.toFile()).redirectErrorStream(true);
    try {
      builder.redirectOutput(logPath.toFile());
      Process process = builder.start();
      if (!waitForStart) {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw failure(command.get(0) + " timed out after " + timeoutSeconds + "s in " + workdir);
        }
        if (process.exitValue() != 0) {
          throw failure(
              String.join(" ", command)
                  + " exited "
                  + process.exitValue()
                  + " in "
                  + workdir
                  + ":\n"
                  + tail(readLog(logPath)));
        }
        return "";
      }

      boolean started = false;
      long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
      while (System.currentTimeMillis() < deadline) {
        if (!process.isAlive()) {
          break;
        }
        if (readLog(logPath).contains(STARTED)) {
          started = true;
          break;
        }
        Thread.sleep(3000);
      }
      if (started) {
        try (Writer stop =
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
          stop.write("stop\n");
          stop.flush();
        } catch (IOException e) {
          // The server closed its own console; the wait below reports what happened.
        }
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(60, TimeUnit.SECONDS);
        }
      } else {
        process.destroyForcibly();
        process.waitFor(60, TimeUnit.SECONDS);
        throw failure(
            "the server in "
                + workdir
                + " never finished starting ("
                + timeoutSeconds
                + "s):\n"
                + tail(readLog(logPath)));
      }
      return readLog(logPath);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failure("interrupted while running " + command.get(0) + " in " + workdir);
    } catch (IOException e) {
      throw failure("could not run " + command.get(0) + " in " + workdir + ": " + e);
    }
  }

  private void checkLog(String text, String loader, Path jar, Path logPath) {
    for (String marker : CRASHED) {
      if (text.contains(marker)) {
        throw failure(
            "the "
                + loader
                + " server reported '"
                + marker
                + "' with "
                + jar.getFileName()
                + ":\n"
                + tail(text));
      }
    }
    if (!text.contains(ACTIVE)) {
      throw failure(
          "the "
              + loader
              + " server started but Minecheck never reported itself active with "
              + jar.getFileName()
              + "; it was not loaded. Log: "
              + logPath
              + "\n"
              + tail(text));
    }
  }

  private String fabricInstallerVersion() {
    for (JsonElement entry : fetchJson(FABRIC_INSTALLER).getAsJsonArray()) {
      JsonObject version = entry.getAsJsonObject();
      JsonElement stable = version.get("stable");
      if (stable != null && stable.getAsBoolean()) {
        return version.get("version").getAsString();
      }
    }
    throw failure("the Fabric meta API lists no stable installer version");
  }

  /**
   * Fetches the distribution jar of {@code version} from Modrinth, failing when it is not there: an
   * API nobody can install is a jar nobody can load.
   */
  private void downloadModrinth(String slug, String minecraft, String version, Path dest) {
    String url = MODRINTH_PROJECT + slug + "/version?game_versions=%5B%22" + minecraft + "%22%5D";
    getLogger()
        .lifecycle("Minecheck:   looking up {} {} for {}: {}", slug, version, minecraft, url);
    JsonArray versions = fetchJson(url).getAsJsonArray();
    for (JsonElement element : versions) {
      JsonObject entry = element.getAsJsonObject();
      JsonElement number = entry.get("version_number");
      if (number == null || !version.equals(number.getAsString())) {
        continue;
      }
      JsonElement files = entry.get("files");
      if (files == null || !files.isJsonArray() || files.getAsJsonArray().isEmpty()) {
        break;
      }
      JsonObject primary = null;
      for (JsonElement file : files.getAsJsonArray()) {
        JsonObject candidate = file.getAsJsonObject();
        JsonElement isPrimary = candidate.get("primary");
        if (isPrimary != null && isPrimary.getAsBoolean()) {
          primary = candidate;
          break;
        }
      }
      if (primary == null) {
        primary = files.getAsJsonArray().get(0).getAsJsonObject();
      }
      download(primary.get("url").getAsString(), dest);
      return;
    }
    throw failure(
        "Modrinth has no "
            + slug
            + " "
            + version
            + " for "
            + minecraft
            + "; the jar is built against an API a user cannot install, so the boot could not be"
            + " set up");
  }

  /** Fetches {@code url} to {@code dest}, retrying what a runner's network fails at. */
  private void download(String url, Path dest) {
    try {
      Files.createDirectories(dest.getParent());
      if (Files.isRegularFile(dest) && Files.size(dest) > 0) {
        return;
      }
    } catch (IOException e) {
      throw failure("could not prepare " + dest + ": " + e);
    }
    String last = "";
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        getLogger().lifecycle("Minecheck:   fetching {}", url);
        HttpResponse<InputStream> response =
            HTTP.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
          throw new IOException("HTTP " + response.statusCode());
        }
        try (InputStream body = response.body()) {
          Files.copy(body, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return;
      } catch (IOException e) {
        last = String.valueOf(e.getMessage());
        getLogger().lifecycle("Minecheck:   attempt {}/3 failed: {}", attempt, last);
        pause(5 * attempt);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw failure("interrupted while downloading " + url);
      }
    }
    throw failure("could not download " + url + ": " + last);
  }

  private JsonElement fetchJson(String url) {
    try {
      HttpResponse<String> response =
          HTTP.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        throw failure(url + " answered HTTP " + response.statusCode());
      }
      return JsonParser.parseString(response.body());
    } catch (IOException e) {
      throw failure("could not read " + url + ": " + e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failure("interrupted while reading " + url);
    }
  }

  private static HttpRequest request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .timeout(Duration.ofMinutes(3))
        .GET()
        .build();
  }

  private void pause(int seconds) {
    try {
      Thread.sleep(seconds * 1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failure("interrupted while waiting to retry");
    }
  }

  private static String readLog(Path path) {
    try {
      return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "";
    }
  }

  /** The last lines of {@code text}, which is all a failure message needs and all CI shows. */
  private static String tail(String text) {
    List<String> lines = text.lines().collect(Collectors.toList());
    int from = Math.max(0, lines.size() - 40);
    return String.join("\n", lines.subList(from, lines.size()));
  }

  private static GradleException failure(String message) {
    return new GradleException("Minecheck: " + message);
  }
}
