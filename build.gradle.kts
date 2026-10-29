import com.diffplug.gradle.spotless.SpotlessExtension
import java.net.URI
import java.util.zip.ZipInputStream
import net.minecheck.gradle.MinecheckLifecyclePlugin
import net.minecheck.gradle.MinecheckLifecycleTest
import net.minecheck.gradle.MinecheckScreenshotBaselines
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
  java
  id("xyz.wagyourtail.unimined") version "1.4.1" apply false
  id("net.minecheck")
  id("com.diffplug.spotless") version "8.10.2"
}

fun configureSpotless(project: Project) {
  project.extensions.configure<SpotlessExtension>("spotless") {
    java {
      googleJavaFormat("1.28.0")
      removeUnusedImports()
      importOrder()
      formatAnnotations()
      replaceRegex(
        "collapseEmptyCatch",
        "(catch\\s*\\([^)]*\\)[ \\t]*)\\{[ \\t]*\\r?\\n[ \\t]*\\}",
        "$1{ }",
      )
      trimTrailingWhitespace()
      endWithNewline()
    }
    kotlinGradle { ktfmt("0.54").googleStyle() }
  }
}

configureSpotless(project)

version = "1.0.0"

group = "net.minecheck"

repositories { mavenCentral() }

fun registerLoaderLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client + dedicated server in parallel and runs the Minecheck Lua lifecycle test"
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    coordinator = "coordinator.lua"
    script = "coordinator.lua"
    serverScript = "server-wait-stop.lua"
    serverLoaderTask = ":$loader-mod:runServer"
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    serverRunDir = layout.projectDirectory.dir("$runRoot/run/server").asFile.absolutePath
    worldName = "minecheck_world"
    serverPort = "25565"
    levelSeed = "0"
    xvfbRun = true

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runServer",
      "devHelperServerLaunch",
      "server-launch.txt",
      serverLaunchFile,
      this,
    )
  }
}

registerLoaderLifecycle("testFabric", "fabric")

registerLoaderLifecycle("testForge", "forge")

registerLoaderLifecycle("testQuilt", "quilt")

fun registerFailPathLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client + dedicated server with deliberately-failing Lua scripts and asserts both fail cleanly"

    // Same gate as the happy-path lifecycle tasks (see registerLoaderLifecycle).
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    script = "fail-path-client.lua"
    serverScript = "fail-path-server.lua"
    serverLoaderTask = ":$loader-mod:runServer"
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    serverRunDir = layout.projectDirectory.dir("$runRoot/run/server").asFile.absolutePath
    worldName = "minecheck_world"
    serverPort = "25565"
    levelSeed = "0"
    xvfbRun = true
    expectFailure = true

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runServer",
      "devHelperServerLaunch",
      "server-launch.txt",
      serverLaunchFile,
      this,
    )
  }
}

registerFailPathLifecycle("testFabricFail", "fabric")

registerFailPathLifecycle("testForgeFail", "forge")

registerFailPathLifecycle("testQuiltFail", "quilt")

fun registerSpawnLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client + dedicated server in parallel and runs the Lua spawn integration test (spawnEntity/spawnBlock/spawnItemDrop/spawnStructure)"
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    script = "spawn-client.lua"
    serverScript = "spawn-test.lua"
    serverLoaderTask = ":$loader-mod:runServer"
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    serverRunDir = layout.projectDirectory.dir("$runRoot/run/server").asFile.absolutePath
    worldName = "minecheck_world"
    serverPort = "25565"
    levelSeed = "0"
    xvfbRun = true

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runServer",
      "devHelperServerLaunch",
      "server-launch.txt",
      serverLaunchFile,
      this,
    )
  }
}

registerSpawnLifecycle("testFabricSpawn", "fabric")

registerSpawnLifecycle("testForgeSpawn", "forge")

registerSpawnLifecycle("testQuiltSpawn", "quilt")

fun registerQueryLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client + dedicated server in parallel and runs the Lua entity query integration test (findEntities by type/tag/position)"
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    script = "query-client.lua"
    serverScript = "query-test.lua"
    serverLoaderTask = ":$loader-mod:runServer"
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    serverRunDir = layout.projectDirectory.dir("$runRoot/run/server").asFile.absolutePath
    worldName = "minecheck_world"
    serverPort = "25565"
    levelSeed = "0"
    xvfbRun = true

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runServer",
      "devHelperServerLaunch",
      "server-launch.txt",
      serverLaunchFile,
      this,
    )
  }
}

registerQueryLifecycle("testFabricQuery", "fabric")

registerQueryLifecycle("testForgeQuery", "forge")

registerQueryLifecycle("testQuiltQuery", "quilt")

fun registerBeaconLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client + dedicated server in parallel and runs the Lua beacon GUI integration test (build pyramid, open beacon screen, read slots)"
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    script = "beacon-client.lua"
    serverScript = "beacon-test.lua"
    serverLoaderTask = ":$loader-mod:runServer"
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    serverRunDir = layout.projectDirectory.dir("$runRoot/run/server").asFile.absolutePath
    worldName = "minecheck_world"
    serverPort = "25565"
    levelSeed = "0"
    xvfbRun = true

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runServer",
      "devHelperServerLaunch",
      "server-launch.txt",
      serverLaunchFile,
      this,
    )
  }
}

registerBeaconLifecycle("testFabricBeacon", "fabric")

registerBeaconLifecycle("testForgeBeacon", "forge")

registerBeaconLifecycle("testQuiltBeacon", "quilt")

fun registerInputLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client alone and runs the Lua input test (look/break/jump/place) in a creative superflat world"

    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    script = "input-test.lua"
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    worldName = "minecheck_world"
    xvfbRun = true

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
  }
}

fun registerLiveLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client, serves a live Lua session over loopback TCP from the script, and drives it from this task's own client (tests/scripts/live-driver.txt)"

    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    script = "live-test.lua"
    liveDriverFile.set(layout.projectDirectory.file("tests/scripts/live-driver.txt"))
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    worldName = "minecheck_world"
    xvfbRun = true

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
  }
}

fun registerWorldHandoffLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Generates a world in the $loader client, hands that save to a dedicated server through dh.serverSetupDir, and joins it (proves the server can run a client-generated world)"
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    script = "world-handoff-client.lua"
    worldGeneratorScript = "world-handoff-generate-client.lua"
    serverScript = "world-handoff-server.lua"
    serverLoaderTask = ":$loader-mod:runServer"
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    serverRunDir = layout.projectDirectory.dir("$runRoot/run/server").asFile.absolutePath
    worldName = "minecheck_world"
    serverPort = "25565"
    xvfbRun = true

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runServer",
      "devHelperServerLaunch",
      "server-launch.txt",
      serverLaunchFile,
      this,
    )
  }
}

registerWorldHandoffLifecycle("testFabricWorldHandoff", "fabric")

registerWorldHandoffLifecycle("testForgeWorldHandoff", "forge")

registerWorldHandoffLifecycle("testQuiltWorldHandoff", "quilt")

fun registerPacketLifecycle(
  taskName: String,
  loader: String,
  scriptName: String = "packet-client.lua",
  serverScriptName: String = "packet-test.lua",
  taskDescription: String =
    "Boots the $loader client + dedicated server in parallel and runs the Lua packet integration test (sendPacket/waitForPacket/onPacket/reader over the real connection)",
) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description = taskDescription
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    script = scriptName
    serverScript = serverScriptName
    serverLoaderTask = ":$loader-mod:runServer"
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    serverRunDir = layout.projectDirectory.dir("$runRoot/run/server").asFile.absolutePath
    worldName = "minecheck_world"
    serverPort = "25565"
    levelSeed = "0"
    xvfbRun = true

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runServer",
      "devHelperServerLaunch",
      "server-launch.txt",
      serverLaunchFile,
      this,
    )
  }
}

registerPacketLifecycle("testFabricPacket", "fabric")

registerPacketLifecycle("testForgePacket", "forge")

registerPacketLifecycle("testQuiltPacket", "quilt")

registerPacketLifecycle(
  "testForgePacketTap",
  "forge",
  "packet-tap-client.lua",
  "packet-tap-server.lua",
  "Boots the Forge client + dedicated server in parallel and verifies foreign-channel observation through the play-connection frame tap",
)

registerInputLifecycle("testFabricInput", "fabric")

registerInputLifecycle("testForgeInput", "forge")

registerInputLifecycle("testQuiltInput", "quilt")

registerLiveLifecycle("testFabricLive", "fabric")

registerLiveLifecycle("testForgeLive", "forge")

registerLiveLifecycle("testQuiltLive", "quilt")

val ciBaselines =
  providers
    .gradleProperty("minecheck.baselines")
    .map { it.trim().equals("ci", ignoreCase = true) }
    .orElse(providers.environmentVariable("CI").map { it.trim().toBoolean() })
    .orElse(false)

val updateScreenshotBaselines =
  providers
    .gradleProperty("minecheck.screenshots.update")
    .map { it.trim().toBoolean() }
    .orElse(false)

val profileWidth =
  providers.gradleProperty("minecheck.screenshot.width").map(String::toInt).orElse(854)
val profileHeight =
  providers.gradleProperty("minecheck.screenshot.height").map(String::toInt).orElse(480)
val profileGuiScale =
  providers.gradleProperty("minecheck.screenshot.guiScale").map(String::toInt).orElse(2)
val profileScale =
  providers.gradleProperty("minecheck.screenshot.scale").map(String::toDouble).orElse(1.0)
val profileChat =
  providers.gradleProperty("minecheck.screenshot.chat").map(String::toBoolean).orElse(false)

fun registerScreenshotsLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  val runTask = "run${taskName.removePrefix("test")}"

  tasks.register<MinecheckLifecycleTest>(runTask) {
    group = "minecheck"
    description =
      "Boots the $loader client alone and runs the Lua screenshot script (writes PNG captures into the run directory)"
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    script = "screenshots-client.lua"
    scriptsDir = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
    runDir = layout.projectDirectory.dir("$runRoot/run/client").asFile.absolutePath
    worldName = "minecheck_world"
    xvfbRun = true
    cleanScreenshots = true
    screenshotProfile = true
    screenshotWidth.set(profileWidth)
    screenshotHeight.set(profileHeight)
    screenshotGuiScale.set(profileGuiScale)
    screenshotScale.set(profileScale)
    screenshotChatVisible.set(profileChat)

    resultFile.set(
      layout.projectDirectory.file("$runRoot/run/client/config/minecheck/run-result.json")
    )

    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader-mod:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
  }

  tasks.register<MinecheckScreenshotBaselines>(taskName) {
    group = "minecheck"
    description =
      "Compares the $loader screenshot captures against the $loader baseline tree, byte for byte"
    dependsOn(runTask)

    getLoader().set(loader)
    capturesDir.set(layout.projectDirectory.dir("$runRoot/run/client/screenshots"))
    baselinesDir.set(layout.projectDirectory.dir("tests/baselines"))
    localBaselinesDir.set(layout.projectDirectory.dir("tests/local-baselines"))
    ciMode.set(ciBaselines)
    update.set(updateScreenshotBaselines)
  }
}

registerScreenshotsLifecycle("testFabricShots", "fabric")

registerScreenshotsLifecycle("testForgeShots", "forge")

registerScreenshotsLifecycle("testQuiltShots", "quilt")

tasks.named("test") {
  dependsOn(
    "testFabric",
    "testForge",
    "testQuilt",
    "testFabricFail",
    "testForgeFail",
    "testQuiltFail",
    "testFabricInput",
    "testForgeInput",
    "testQuiltInput",
    "testFabricLive",
    "testForgeLive",
    "testQuiltLive",
    "testFabricSpawn",
    "testForgeSpawn",
    "testQuiltSpawn",
    "testFabricQuery",
    "testForgeQuery",
    "testQuiltQuery",
    "testFabricBeacon",
    "testForgeBeacon",
    "testQuiltBeacon",
    "testFabricPacket",
    "testForgePacket",
    "testForgePacketTap",
    "testQuiltPacket",
    "testFabricWorldHandoff",
    "testForgeWorldHandoff",
    "testQuiltWorldHandoff",
    "testFabricShots",
    "testForgeShots",
    "testQuiltShots",
    "spotlessCheck",
  )
}

subprojects {
  apply(plugin = "java")
  apply(plugin = "com.diffplug.spotless")

  group = rootProject.group
  version = rootProject.version

  java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

  repositories { mavenCentral() }

  configureSpotless(project)

  tasks.named("test") { dependsOn("spotlessCheck") }
}

val pluginBuild = gradle.includedBuild("plugin")

tasks.named("spotlessApply") { dependsOn(pluginBuild.task(":spotlessApply")) }

tasks.named("spotlessCheck") { dependsOn(pluginBuild.task(":spotlessCheck")) }

tasks.named("test") { dependsOn(pluginBuild.task(":test")) }

val styluaVersion = "2.5.2"
val styluaOs =
  when {
    org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "macos"
    else -> "windows"
  }
val styluaArch =
  when (System.getProperty("os.arch")) {
    "aarch64",
    "arm64" -> "aarch64"
    else -> "x86_64"
  }
val styluaTarget = "stylua-$styluaOs-$styluaArch"
val styluaDir = layout.buildDirectory.dir("stylua/$styluaVersion")
val styluaExeName = if (styluaOs == "windows") "stylua.exe" else "stylua"
val styluaExe = styluaDir.map { it.file(styluaExeName).asFile }
val styluaConfig = layout.projectDirectory.file("stylua.toml")
val luaFiles = fileTree("tests/scripts") { include("*.lua") }

val styluaDownload =
  tasks.register("styluaDownload") {
    val zip = layout.buildDirectory.file("stylua/$styluaVersion/$styluaTarget.zip")
    outputs.files(zip)
    outputs.dir(styluaDir)
    doLast {
      val dest = zip.get().asFile
      if (!dest.exists()) {
        dest.parentFile.mkdirs()
        val url =
          "https://github.com/JohnnyMorganz/StyLua/releases/download/v$styluaVersion/$styluaTarget.zip"
        URI(url).toURL().openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
      }
      val exe = styluaExe.get()
      if (!exe.exists()) {
        ZipInputStream(dest.inputStream()).use { zis ->
          var entry = zis.nextEntry
          while (entry != null) {
            if (!entry.isDirectory) {
              val out = styluaDir.get().file(entry.name).asFile
              out.parentFile.mkdirs()
              out.outputStream().use { zis.copyTo(it) }
            }
            entry = zis.nextEntry
          }
        }
        exe.setExecutable(true)
      }
    }
  }

fun styluaArgs(check: Boolean): List<String> {
  val base =
    listOf(styluaExe.get().absolutePath, "--config-path", styluaConfig.asFile.absolutePath) +
      (if (check) listOf("--check") else emptyList())
  return base + luaFiles.files.map { it.absolutePath }
}

val styluaApply =
  tasks.register("styluaApply") {
    dependsOn(styluaDownload)
    inputs.files(luaFiles)
    inputs.file(styluaConfig)
    doLast { project.exec { commandLine(styluaArgs(false)) } }
  }

val styluaCheck =
  tasks.register("styluaCheck") {
    dependsOn(styluaDownload)
    inputs.files(luaFiles)
    inputs.file(styluaConfig)
    doLast { project.exec { commandLine(styluaArgs(true)) } }
  }

tasks.named("spotlessApply") { dependsOn(styluaApply) }

tasks.named("spotlessCheck") { dependsOn(styluaCheck) }

val loaderModules = listOf("fabric-mod", "quilt-mod", "forge-mod")

fun loaderJarDir(module: String): File =
  project.file("src/${module.removeSuffix("-mod")}/mod/build/libs")

tasks.register("buildAll") {
  group = "minecheck"
  description =
    "Builds every loader's distributable jar (:fabric-mod:shadowJar, :quilt-mod:shadowJar, :forge-mod:shadowJar)"
  dependsOn(loaderModules.map { ":$it:shadowJar" })
}

tasks.register<Copy>("collectArtifacts") {
  group = "minecheck"
  description = "Builds every loader jar and copies them into build/artifacts"
  dependsOn("buildAll")

  loaderModules.forEach { module ->
    from(loaderJarDir(module)) {
      include("*.jar")
      // Sources/javadoc ship to Maven, not to the GitHub Release; the Forge reobf jar is never
      // distributed at all (spec decision 25).
      exclude("*-sources.jar", "*-javadoc.jar", "*-production.jar")
    }
  }

  val artifactsDir = layout.buildDirectory.dir("artifacts").get().asFile
  into(artifactsDir)

  doLast {
    logger.lifecycle(
      "Minecheck: collected {} jar(s) into {}",
      artifactsDir.listFiles()?.size ?: 0,
      artifactsDir,
    )
  }
}

// ---------------------------------------------------------------------------------------------
// Publishing (spec §8): MineCheck-<loader>-<mc>-<project version>.jar, published to GitHub
// Packages as net.minecheck:minecheck-<loader>-<mc>:<project version>. Driven by
// `./gradlew publish` (GitHub Actions supplies GITHUB_ACTOR/GITHUB_TOKEN).
// ---------------------------------------------------------------------------------------------

val mcVersion: String = property("minecraft_version") as String

val loaderArtifactNames =
  mapOf("fabric-mod" to "fabric", "quilt-mod" to "quilt", "forge-mod" to "forge")

subprojects {
  val loader = loaderArtifactNames[name] ?: return@subprojects

  apply(plugin = "maven-publish")

  extensions.configure<JavaPluginExtension> {
    withSourcesJar()
    withJavadocJar()
  }

  // Applies to shadowJar, sourcesJar, javadocJar and the (disabled) plain jar, so the file name
  // and the Maven coordinate always agree.
  tasks.withType<Jar>().configureEach {
    archiveBaseName.set("MineCheck-$loader")
    archiveVersion.set("$mcVersion-${project.version}")
  }

  // Minecraft classes leak into the mod sources; doclint would otherwise fail the build on them.
  tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    isFailOnError = false
  }

  afterEvaluate {
    extensions.configure<PublishingExtension> {
      publications {
        create<MavenPublication>("minecheck") {
          groupId = "net.minecheck"
          artifactId = "minecheck-$loader-$mcVersion"
          version = project.version.toString()
          // The shadow jar is the mod; the plain jar is empty and the reobf jar never ships.
          artifact(tasks.named("shadowJar")) { classifier = "" }
          artifact(tasks.named("sourcesJar")) { classifier = "sources" }
          artifact(tasks.named("javadocJar")) { classifier = "javadoc" }
          pom {
            name.set("MineCheck $loader for Minecraft $mcVersion")
            description.set("Minecheck: scripted Minecraft testing harness (Lua-driven)")
          }
        }
      }
      repositories {
        maven {
          name = "GitHubPackages"
          url = uri("https://maven.pkg.github.com/kirjorjos/Minecheck")
          credentials {
            username = System.getenv("GITHUB_ACTOR") ?: "github-actions"
            password = System.getenv("GITHUB_TOKEN")
          }
        }
      }
    }
  }
}
