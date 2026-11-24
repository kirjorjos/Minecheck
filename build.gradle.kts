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
  id("xyz.wagyourtail.jvmdowngrader") version "2.0.1" apply false
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

version = "3.0.7"

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

registerLoaderLifecycle("testLegacyfabric", "legacyfabric")

registerLoaderLifecycle("testForge", "forge")

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

registerFailPathLifecycle("testLegacyfabricFail", "legacyfabric")

registerFailPathLifecycle("testForgeFail", "forge")

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

registerSpawnLifecycle("testLegacyfabricSpawn", "legacyfabric")

registerSpawnLifecycle("testForgeSpawn", "forge")

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

registerQueryLifecycle("testLegacyfabricQuery", "legacyfabric")

registerQueryLifecycle("testForgeQuery", "forge")

fun registerBeaconLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client + dedicated server in parallel and runs the Lua beacon GUI integration test (build pyramid, open the sign editor over it, type and save a sign)"
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

registerBeaconLifecycle("testLegacyfabricBeacon", "legacyfabric")

registerBeaconLifecycle("testForgeBeacon", "forge")

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
    expectedObservations.set(layout.projectDirectory.file("tests/handoff-baseline.json"))

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

registerWorldHandoffLifecycle("testLegacyfabricWorldHandoff", "legacyfabric")

registerWorldHandoffLifecycle("testForgeWorldHandoff", "forge")

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

registerPacketLifecycle("testLegacyfabricPacket", "legacyfabric")

registerPacketLifecycle("testForgePacket", "forge")

// No frame tap on this line: Legacy Fabric ships no Forge to wrap the play connection, so a
// foreign channel is observed at the payload handler itself, which is what `packet-tap` asserts.
registerPacketLifecycle(
  "testLegacyfabricPacketTap",
  "legacyfabric",
  "packet-tap-client.lua",
  "packet-tap-server.lua",
  "Boots the Legacy Fabric client + dedicated server in parallel and verifies foreign-channel observation at the custom-payload handler",
)

registerPacketLifecycle(
  "testForgePacketTap",
  "forge",
  "packet-tap-client.lua",
  "packet-tap-server.lua",
  "Boots the Forge client + dedicated server in parallel and verifies foreign-channel observation at the netty frame tap",
)

registerInputLifecycle("testLegacyfabricInput", "legacyfabric")

registerInputLifecycle("testForgeInput", "forge")

registerLiveLifecycle("testLegacyfabricLive", "legacyfabric")

registerLiveLifecycle("testForgeLive", "forge")

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

fun registerScreenshotsLifecycle(
  taskName: String,
  loader: String,
  // The UI language the capture profile pins. 1.4.7 names its language file `en_US.lang`, so that
  // line has to ask for the older spelling; the newer lines use the lowercase code.
  language: String = "en_us",
) {
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
    languageName.set(language)

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

registerScreenshotsLifecycle("testLegacyfabricShots", "legacyfabric")

registerScreenshotsLifecycle("testForgeShots", "forge", language = "en_US")

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
    // The camera and the run's view window are part of the client surface these suites cover: the
    // camera is installed in `assist` (a lifecycle run has no human, so Lua drives it), and the
    // view window is on, which exercises the documented non-fatal path under xvfb.
    cameraMode.set("assist")
    viewWindow.set(true)
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

registerServerFeaturesLifecycle("testLegacyfabricServerFeatures", "legacyfabric")

registerServerFeaturesLifecycle("testForgeServerFeatures", "forge")

registerClientFeaturesLifecycle("testLegacyfabricClientFeatures", "legacyfabric")

registerClientFeaturesLifecycle("testForgeClientFeatures", "forge")

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
  "legacyfabric",
  "testLegacyfabric",
  "testLegacyfabricFail",
  "testLegacyfabricServerFeatures",
  "testLegacyfabricClientFeatures",
  "testLegacyfabricWorldHandoff",
  "testLegacyfabricShots",
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

tasks.named("test") { dependsOn(checkLoaders, "testLegacyfabricAll", "spotlessCheck") }

subprojects {
  apply(plugin = "java")
  apply(plugin = "com.diffplug.spotless")

  group = rootProject.group
  version = rootProject.version

  java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

  repositories { mavenCentral() }

  configureSpotless(project)

  tasks.named("test") { dependsOn("spotlessCheck") }

  // Lines whose game runtime predates the Java 17 this repo compiles at (gradle.properties:
  // `minecheck.downgradeTo`) keep compilation at 17 and downgrade afterwards: the module's classes
  // are bundled, rewritten to the target class file version — JvmDowngrader turns records into
  // plain classes, expands pattern `instanceof`, and rewrites JDK calls newer than the target
  // against shaded stubs — and unpacked into a directory that the dev runs and the shadow jar read
  // instead of the raw compile output. The Forge 1.14.4 line does this to reach Java 8.
  val downgradeTarget =
    providers
      .gradleProperty("minecheck.downgradeTo")
      .map { JavaVersion.toVersion(it.trim()) }
      .orNull

  if (
    downgradeTarget != null &&
      name in setOf("common", "platform-api", "legacyfabric-mod", "forge-mod")
  ) {
    apply(plugin = "xyz.wagyourtail.jvmdowngrader")

    val target = downgradeTarget
    val mainSourceSet =
      extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName("main")
    val bundleDir = layout.buildDirectory.dir("minecheck/downgrade")

    // The stubs the downgraded code links against, taken from a jar for the target version: left to
    // itself the plugin generates one for the JVM running Gradle, which on a Java 25 daemon is
    // class file 69 and cannot be shaded down. Upstream publishes 8 and above, so a 7 target uses
    // the 51-level jar vendored in `libs/` instead — `libs/README.md` says how it is produced.
    val jvmdgApi by
      configurations.creating {
        isCanBeConsumed = false
        isCanBeResolved = true
      }
    val vendoredApi =
      rootProject.layout.projectDirectory.file(
        "libs/jvmdowngrader-java-api-2.0.1-downgraded-${target.majorVersion}.jar"
      )
    dependencies.add(
      jvmdgApi.name,
      if (vendoredApi.asFile.isFile) files(vendoredApi)
      else
        "xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1:downgraded-" +
          target.majorVersion,
    )

    val classesBundle by
      tasks.registering(Jar::class) {
        // Classes *and* resources: FML reads a dev mod's mods.toml, mixin config and manifest from
        // the MOD_CLASSES directories, so a classes-only bundle leaves the mod invisible and the
        // client simply sits at the title screen with no script.
        from(mainSourceSet.output.classesDirs)
        from(layout.buildDirectory.dir("resources/main"))
        dependsOn(tasks.named("processResources"))
        destinationDirectory.set(bundleDir)
        archiveClassifier.set("bundle")
      }

    val downgradedBundle by
      tasks.registering(xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar::class) {
        inputFile.set(classesBundle.flatMap { it.archiveFile })
        downgradeTo.set(target)
        apiJar.set(jvmdgApi.files.toList())
        classpath = mainSourceSet.compileClasspath
        destinationDirectory.set(bundleDir)
        archiveClassifier.set("downgraded")
      }

    val shadedBundle by
      tasks.registering(xyz.wagyourtail.jvmdg.gradle.task.ShadeJar::class) {
        inputFile.set(downgradedBundle.flatMap { it.archiveFile })
        shadePath.set("jvmdg/stubs/")
        apiJar.set(jvmdgApi.files.toList())
        destinationDirectory.set(bundleDir)
        archiveClassifier.set("shaded")
      }

    tasks.register<Sync>("downgradedRuntime") {
      group = "minecheck"
      description =
        "Unpacks $name compiled at Java 17 as Java ${target.majorVersion} bytecode for the runs"
      dependsOn(shadedBundle)
      from(shadedBundle.map { zipTree(it.archiveFile) })
      into(layout.buildDirectory.dir("minecheck/java" + target.majorVersion))
    }
  }
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

/**
 * Fetches a build tool, with the timeouts a build needs.
 *
 * <p>A bare `openStream()` has no connect or read timeout, so a release CDN that stops answering
 * hangs until the socket itself gives up and the build dies with "Connection timed out" — a message
 * that names neither the tool nor its URL, and that every job inherits because formatting is a
 * dependency of the suites. The retry is here for the same reason: this is one archive from one
 * host, and a transient failure of it should not fail a run that has nothing to do with it.
 */
fun downloadFile(url: String, dest: File) {
  val attempts = 3
  var last: Exception? = null
  for (attempt in 1..attempts) {
    try {
      dest.parentFile.mkdirs()
      val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
      connection.connectTimeout = 30_000
      connection.readTimeout = 300_000
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("User-Agent", "minecheck-build")
      connection.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
      return
    } catch (e: Exception) {
      last = e
      dest.delete()
      logger.warn("Minecheck: downloading $url failed (attempt $attempt/$attempts): ${e.message}")
      if (attempt < attempts) {
        Thread.sleep(2_000L * attempt)
      }
    }
  }
  throw GradleException("could not download $url: ${last?.message}", last)
}

val styluaDownload =
  tasks.register("styluaDownload") {
    val zip = layout.buildDirectory.file("stylua/$styluaVersion/$styluaTarget.zip")
    outputs.files(zip)
    outputs.dir(styluaDir)
    doLast {
      val dest = zip.get().asFile
      if (!dest.exists()) {
        val url =
          "https://github.com/JohnnyMorganz/StyLua/releases/download/v$styluaVersion/$styluaTarget.zip"
        downloadFile(url, dest)
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
    filesMatching("**/mods.toml") {
      filter { line: String -> line.replace("\${licence}", licence.get()) }
    }
  }
}

// Both loaders this line's metadata names: one that is listed there but missing here is promised to
// a release and absent from it.
val loaderModules = listOf("forge-mod", "legacyfabric-mod")

fun loaderJarDir(module: String): File =
  project.file("src/${module.removeSuffix("-mod")}/mod/build/libs")

tasks.register("buildAll") {
  group = "minecheck"
  description = "Builds this line's distributable jars, one productionJar per loader"
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
 * `-Pboot.only=fabric,forge` boots a subset, `-Pboot.java=<path>` picks the JDK the non-Forge
 * servers run on (default: `java` from `PATH`), `-Pboot.forgeJava=<path>` the one Forge runs on,
 * and `-Pboot.timeout=<seconds>` widens the start deadline.
 */
// Resolved on the project: inside the task block `property` would look for a task property.
val bootMinecraftVersion = property("minecraft_version").toString()

// Only `common`, `platform-api` and `legacyfabric-mod` are downgraded (see the module loop above),
// and the pinned JDK is the one the line's loader starts on. The Fabric jar keeps class file 61 and
// a JAVA_17 mixin config, so handing it the Java 8 runtime would only prove the wrong JVM cannot
// load it.
val pinnedForgeJava: String =
  if (providers.gradleProperty("minecheck.downgradeTo").isPresent) {
    project(":forge-mod")
      .layout
      .buildDirectory
      .dir("jdk/8.0.292/bin/java")
      .get()
      .asFile
      .absolutePath
  } else "java"

val bootJavaOverride = providers.gradleProperty("boot.java").orNull
val bootDefaultJava = bootJavaOverride ?: "java"
val bootForgeJava =
  providers.gradleProperty("boot.forgeJava").orNull ?: bootJavaOverride ?: pinnedForgeJava

tasks.register<MinecheckProductionBoot>("bootPackagedJars") {
  group = "minecheck"
  description = "Boots every packaged loader jar in an installed dedicated server"
  dependsOn("collectArtifacts")
  if (providers.gradleProperty("minecheck.downgradeTo").isPresent) {
    dependsOn(":forge-mod:installPinnedJdk")
  }
  minecraftVersion.set(bootMinecraftVersion)
  modVersion.set(project.version.toString())
  metadataFile.set(layout.projectDirectory.file(".github/metadata.json"))
  projectRoot.set(layout.projectDirectory)
  artifactsDir.set(layout.buildDirectory.dir("artifacts"))
  artifactJars.from(fileTree(layout.buildDirectory.dir("artifacts")) { include("*.jar") })
  workRoot.set(layout.buildDirectory.dir("production-boot"))
  onlyLoaders.set(providers.gradleProperty("boot.only").getOrElse(""))
  // Forge's jar is Java 8 bytecode and only starts on the pinned JDK (the JDK 8 users have is
  // usually too new for ModLauncher 8.0.9), while Fabric's is class 61 and only loads on 17.
  javaExecutable.set(bootDefaultJava)
  forgeJavaExecutable.set(bootForgeJava)
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
// 1.17 and later name their members `m_12345_` / `f_12345_`; the lines before them, 1.16.5 among
// them, use `func_12345_a` / `field_12345_a`. Both spellings are SRG, so both count.
val forgeProductionNames = Regex("(?:[mf]_[0-9]+_|(?:func|field)_[0-9]+_[a-zA-Z])")

// Legacy Fabric's production namespace is intermediary, whose members are `method_1234_a` and
// `field_5678_b` — the same shape upstream Fabric uses, which is why the pattern is
// version-neutral.
val intermediaryProductionNames = Regex("(?:method|field)_[0-9]+")

val productionNamespaces =
  mapOf("forge" to forgeProductionNames, "legacyfabric" to intermediaryProductionNames)

val checkProductionJars =
  tasks.register("checkProductionJars") {
    group = "minecheck"
    description =
      "Asserts each distributable loader jar is remapped into its production namespace (issue #23)"
    dependsOn(loaderModules.map { ":$it:productionJar" })
    doLast {
      val loaders = mapOf("legacyfabric-mod" to "legacyfabric")
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

val loaderArtifactNames = mapOf("forge-mod" to "forge", "legacyfabric-mod" to "legacyfabric")

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
