import com.diffplug.gradle.spotless.SpotlessExtension
import java.net.URI
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import net.minecheck.gradle.MinecheckLifecyclePlugin
import net.minecheck.gradle.MinecheckLifecycleTest
import net.minecheck.gradle.MinecheckProductionBoot
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

version = "3.0.1"

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

// Report mode: compare and print, but never fail — the gate is determinism, run as a second pass
// with minecheck.screenshots.compareDir pointing at the first pass's captures and
// minecheck.screenshots.only at the captures that pass found divergent.
val reportScreenshotBaselines =
  providers
    .gradleProperty("minecheck.screenshots.report")
    .map { it.trim().toBoolean() }
    .orElse(false)

val screenshotCompareDir = providers.gradleProperty("minecheck.screenshots.compareDir")

// The mismatch manifest: report mode writes it, the determinism pass reads the classes it records.
val screenshotManifest = providers.gradleProperty("minecheck.screenshots.manifest")

// The captures the determinism pass compares: exactly the ones the first pass found divergent.
val screenshotOnly = providers.gradleProperty("minecheck.screenshots.only")

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
    report.set(reportScreenshotBaselines)
    reportFile.set(layout.buildDirectory.file("minecheck/shots-report/$loader.txt"))
    screenshotCompareDir.orNull?.let { path ->
      compareDir.set(layout.dir(providers.provider { file(path) }))
    }
    screenshotOnly.orNull?.let { path -> only.set(layout.file(providers.provider { file(path) })) }
    screenshotManifest.orNull?.let { path ->
      manifestFile.set(layout.file(providers.provider { file(path) }))
    }
  }
}

registerScreenshotsLifecycle("testFabricShots", "fabric")

registerScreenshotsLifecycle("testForgeShots", "forge")

registerScreenshotsLifecycle("testQuiltShots", "quilt")

/**
 * The server-backed suites, as phases of one session: one client boot and one dedicated-server boot
 * instead of one of each per suite. Their per-suite tasks stay registered for debugging a single
 * scenario locally; the aggregate runs this one.
 */
fun registerServerFeaturesLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client + dedicated server once and runs the server-backed suites as phases of that session (beacon, query, packet, frame tap, spawn)"
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    coordinator = "server-features-client.lua"
    script = "server-features-client.lua"
    serverScript = "server-features-server.lua"
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

/**
 * The client-only suites, as phases of one session: input and live each need a client-created world
 * and share the one `input-test.lua` creates, so they need no disconnect between them.
 */
fun registerClientFeaturesLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client once and runs the client-only suites as phases of that session (input, live)"
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader-mod:runClient"
    coordinator = "client-features-client.lua"
    script = "client-features-client.lua"
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

registerServerFeaturesLifecycle("testFabricServerFeatures", "fabric")

registerServerFeaturesLifecycle("testForgeServerFeatures", "forge")

registerServerFeaturesLifecycle("testQuiltServerFeatures", "quilt")

registerClientFeaturesLifecycle("testFabricClientFeatures", "fabric")

registerClientFeaturesLifecycle("testForgeClientFeatures", "forge")

registerClientFeaturesLifecycle("testQuiltClientFeatures", "quilt")

/**
 * Every lifecycle suite for one loader, as one task. CI runs one of these per loader in its own
 * job, so the workflow never has to enumerate task families — adding a suite means adding it here
 * and to the loader's aggregate.
 */
fun registerLoaderAggregate(loader: String, vararg taskNames: String) {
  tasks.register("test${loader.replaceFirstChar { it.uppercase() }}All") {
    group = "minecheck"
    description = "Runs every lifecycle suite for the $loader loader"
    dependsOn(*taskNames)
  }
}

registerLoaderAggregate(
  "fabric",
  "testFabric",
  "testFabricFail",
  "testFabricServerFeatures",
  "testFabricClientFeatures",
  "testFabricWorldHandoff",
  "testFabricShots",
)

registerLoaderAggregate(
  "forge",
  "testForge",
  "testForgeFail",
  "testForgeServerFeatures",
  "testForgeClientFeatures",
  "testForgeWorldHandoff",
  "testForgeShots",
)

registerLoaderAggregate(
  "quilt",
  "testQuilt",
  "testQuiltFail",
  "testQuiltServerFeatures",
  "testQuiltClientFeatures",
  "testQuiltWorldHandoff",
  "testQuiltShots",
)

val metadataFile = layout.projectDirectory.file(".github/metadata.json")

/**
 * Validates the loader list CI reads. `.loaders` in the metadata file is the single source of truth
 * for which loaders exist, and it decides how many jobs a PR runs, so a typo here must fail locally
 * instead of silently dropping a loader from CI.
 *
 * <p>Only typos are rejected: a module with no entry in the list is allowed, so dropping a loader
 * from CI stays a one-line change rather than a module deletion.
 */
val checkLoaders =
  tasks.register("checkLoaders") {
    group = "minecheck"
    description =
      "Validates .loaders in .github/metadata.json against the modules, aggregate tasks and baselines"
    val file = metadataFile.asFile
    inputs.file(file)
    doLast {
      fun fail(message: String): Nothing =
        throw GradleException("Minecheck: ${file.relativeTo(projectDir)}: $message")

      if (!file.isFile) fail("missing")
      val metadata =
        try {
          groovy.json.JsonSlurper().parseText(file.readText())
        } catch (e: Exception) {
          fail("is not valid JSON: ${e.message}")
        }
      val listed = (metadata as? Map<*, *>)?.get("loaders")
      if (listed !is List<*>) fail("has no \"loaders\" array")
      val ids = mutableListOf<String>()
      listed.forEachIndexed { index, entry ->
        val id = (entry as? String)?.trim()
        if (id.isNullOrEmpty()) fail("loader ${index + 1} is not a non-empty string")
        if (!Regex("[a-z0-9][a-z0-9-]*").matches(id)) {
          fail("loader id \"$id\" must match [a-z0-9][a-z0-9-]*")
        }
        if (ids.contains(id)) fail("loader id \"$id\" is listed twice")
        ids.add(id)
      }
      if (ids.isEmpty()) fail("lists no loaders")
      ids.forEach { id ->
        val aggregate = "test${id.replaceFirstChar { it.uppercase() }}All"
        if (findProject(":$id-mod") == null) fail("loader \"$id\" has no :$id-mod module")
        if (!tasks.names.contains(aggregate)) fail("loader \"$id\" has no $aggregate task")
        if (!file("tests/baselines/$id").isDirectory) {
          fail("loader \"$id\" has no tests/baselines/$id tree")
        }
      }
      logger.lifecycle("Loaders: ${ids.joinToString(", ")}")
    }
  }

tasks.named("test") {
  dependsOn(checkLoaders, "testFabricAll", "testForgeAll", "testQuiltAll", "spotlessCheck")
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

// Formatting and the plugin's own tests, once per run instead of once per loader job.
tasks.register("checkModule") {
  group = "minecheck"
  description = "Runs formatting and the Gradle plugin's tests"
  dependsOn("spotlessCheck", pluginBuild.task(":test"))
}

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

// The licence identifier is declared once, in gradle.properties, and substituted into the three
// loader metadata files here. Literal copies in each file are what drift: Forge ended up saying
// `GPLv3` while the other two said nothing at all.
subprojects {
  tasks.withType<ProcessResources>().configureEach {
    val licence = providers.gradleProperty("licence")
    inputs.property("licence", licence)
    filesMatching(listOf("fabric.mod.json", "quilt.mod.json", "**/mods.toml")) {
      filter { line: String -> line.replace("\${licence}", licence.get()) }
    }
  }
}

val loaderModules = listOf("fabric-mod", "quilt-mod", "forge-mod")

fun loaderJarDir(module: String): File =
  project.file("src/${module.removeSuffix("-mod")}/mod/build/libs")

tasks.register("buildAll") {
  group = "minecheck"
  description =
    "Builds every loader's distributable jar (:fabric-mod:productionJar, :quilt-mod:productionJar, :forge-mod:productionJar)"
  dependsOn(loaderModules.map { ":$it:productionJar" })
}

/** The `<n>` of a `-dev-<n>` jar name built from [version], or null for anything else. */
fun devTagOf(name: String, version: String): Int? =
  Regex("-${Regex.escape(version)}-dev-(\\d+)\\.jar$").find(name)?.groupValues?.get(1)?.toInt()

// `-dev-<n>` is local-only and auto-incrementing: CI builds unclassified so a release asset name
// stays stable, while a local build takes the next free number for this version, so bumping major,
// minor or patch resets the counter on its own.
val localDevTag: Int =
  if (providers.environmentVariable("CI").orNull?.isNotBlank() == true) 0
  else
    (loaderModules
      .map(::loaderJarDir)
      .filter { it.isDirectory }
      .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
      .mapNotNull { devTagOf(it.name, project.version.toString()) }
      .maxOrNull() ?: 0) + 1

extra["localDevTag"] = localDevTag

// Resolved on the project, not inside the Copy task (where `property` would look for a task
// property and fail): the release attaches exactly these files.
val artifactsVersion = "${property("minecraft_version")}-${project.version}"

tasks.register<Copy>("collectArtifacts") {
  group = "minecheck"
  description = "Builds every loader jar and copies them into build/artifacts"
  dependsOn("buildAll")

  loaderModules.forEach { module ->
    val name = "MineCheck-${module.removeSuffix("-mod")}-$artifactsVersion"
    from(loaderJarDir(module)) {
      // The loadable jar and its deobfuscated twin, and nothing else: sources and javadoc are
      // Maven-only, and an older build sitting in `build/libs` never belongs in a release.
      include("$name.jar", "$name-deobf.jar", "$name-dev-*.jar")
    }
  }

  val artifactsDir = layout.buildDirectory.dir("artifacts").get().asFile
  into(artifactsDir)

  // A `Copy` only ever adds files: whatever an earlier build left in here — another Minecraft
  // version, an older project version, a stale SHA256SUMS.txt — stays put and would be attached to
  // the release. Starting from an empty directory makes the staged tree describe exactly what this
  // invocation collected, which is what the release's asset check relies on (#52).
  doFirst { delete(artifactsDir) }

  doLast {
    logger.lifecycle(
      "Minecheck: collected {} jar(s) into {}",
      artifactsDir.listFiles()?.size ?: 0,
      artifactsDir,
    )
  }
}

/**
 * Boots each packaged loader jar in an installed dedicated server (issue #103). The lifecycle
 * suites run the *development* classpath and `checkProductionJars` only reads the jar's bytecode,
 * so a jar that breaks after remapping - or one whose metadata names an API nobody can install -
 * passed both. This does what a user does with the release asset: installs the loader at the
 * version this line builds against, drops the jar in `mods/`, and requires Minecheck to report
 * itself active.
 *
 * `-Pboot.only=fabric,forge` boots a subset, `-Pboot.java=<path>` picks the JDK the servers run on
 * (default: `java` from `PATH`), and `-Pboot.timeout=<seconds>` widens the start deadline.
 */
// Resolved on the project: inside the task block `property` would look for a task property.
val bootMinecraftVersion = property("minecraft_version").toString()

tasks.register<MinecheckProductionBoot>("bootPackagedJars") {
  group = "minecheck"
  description = "Boots every packaged loader jar in an installed dedicated server"
  dependsOn("collectArtifacts")
  minecraftVersion.set(bootMinecraftVersion)
  modVersion.set(project.version.toString())
  metadataFile.set(layout.projectDirectory.file(".github/metadata.json"))
  projectRoot.set(layout.projectDirectory)
  artifactsDir.set(layout.buildDirectory.dir("artifacts"))
  artifactJars.from(fileTree(layout.buildDirectory.dir("artifacts")) { include("*.jar") })
  workRoot.set(layout.buildDirectory.dir("production-boot"))
  onlyLoaders.set(providers.gradleProperty("boot.only").getOrElse(""))
  javaExecutable.set(providers.gradleProperty("boot.java").getOrElse("java"))
  bootTimeoutSeconds.set(providers.gradleProperty("boot.timeout").map { it.toInt() }.getOrElse(600))
}

/**
 * The regression test for issue #23: a distributable jar must carry its loader's *production*
 * member names. Building against development names (Mojang for Forge, Yarn for Fabric/Quilt) is
 * invisible in the Gradle dev runtime and fatal in a production instance — the released Forge jar
 * died on a real client's first tick with `NoSuchMethodError: 'Minecraft Minecraft.getInstance()'`
 * because production Forge resolves members by SRG name.
 *
 * The check is a namespace probe rather than a bytecode reference walk: the production namespaces
 * are exactly the machine-generated ones (`method_1234` / `field_5678` for intermediary, `m_12345_`
 * / `f_12345_` for SRG), so a remapped jar contains those tokens and a dev jar contains none. No
 * member id is hardcoded, so it holds across Minecraft versions.
 */
val productionNamespaces =
  mapOf(
    "fabric" to Regex("(?:method|field)_[0-9]+"),
    "quilt" to Regex("(?:method|field)_[0-9]+"),
    "forge" to Regex("[mf]_[0-9]+_"),
  )

val checkProductionJars =
  tasks.register("checkProductionJars") {
    group = "minecheck"
    description =
      "Asserts each distributable loader jar is remapped into its production namespace (issue #23)"
    dependsOn(loaderModules.map { ":$it:productionJar" })
    doLast {
      val loaders = mapOf("fabric-mod" to "fabric", "quilt-mod" to "quilt", "forge-mod" to "forge")
      loaders.forEach { (module, loader) ->
        // The task's own output, not a directory scan: `build/libs` also holds jars from earlier
        // versions of the same loader, and "the newest file there" is exactly the guess that lets a
        // stale artifact pass for the one being shipped.
        val jar = project(":$module").tasks.named("productionJar").get().outputs.files.singleFile
        val pattern = productionNamespaces.getValue(loader)
        val classes =
          ZipFile(jar).use { zip ->
            zip
              .entries()
              .asSequence()
              .filter { it.name.endsWith(".class") }
              .joinToString("\n") { entry ->
                zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
              }
          }
        if (!pattern.containsMatchIn(classes)) {
          throw GradleException(
            "Minecheck: ${jar.name} is not remapped into the $loader production namespace (no " +
              "${pattern.pattern} member name in its classes). A jar built against development " +
              "names loads in the dev runtime and dies in a production instance — see issue #23."
          )
        }
        logger.lifecycle("Minecheck: {} carries {} production names", jar.name, loader)
        // A release attaches the pair: the loadable jar and the same code in development names.
        // A missing deobf jar is a silent half release, so it is checked here too.
        val deobf = project(":$module").tasks.named("shadowJar").get().outputs.files.singleFile
        if (!deobf.isFile || !deobf.name.contains("-deobf")) {
          throw GradleException(
            "Minecheck: expected a deobfuscated jar beside ${jar.name}, found ${deobf.name}; " +
              "the release attaches both the remapped and the -deobf jar."
          )
        }
        logger.lifecycle("Minecheck: {} ships beside {}", jar.name, deobf.name)
      }
    }
  }

tasks.named("checkModule") { dependsOn(checkProductionJars) }

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

  // Applies to productionJar, shadowJar, sourcesJar, javadocJar and the (disabled) plain jar, so
  // the file name and the Maven coordinate always agree.
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
          // The remapped shadow jar is the mod: the plain jar is empty, and this one carries the
          // loader's production names.
          artifact(tasks.named("productionJar")) { classifier = "" }
          // Its deobfuscated twin, published under the `deobf` classifier so the Maven coordinate
          // matches the release asset: same code in development names, for reading stack traces
          // and writing mixins against.
          artifact(tasks.named("shadowJar")) { classifier = "deobf" }
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
