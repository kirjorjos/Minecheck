import com.diffplug.gradle.spotless.SpotlessExtension
import java.net.URI
import java.util.zip.ZipInputStream
import net.minecheck.gradle.MinecheckLifecyclePlugin
import net.minecheck.gradle.MinecheckLifecycleTest
import org.gradle.api.Project

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

version = "0.1.0"

group = "net.minecheck"

repositories { mavenCentral() }

fun registerLoaderLifecycle(taskName: String, loader: String) {
  val runRoot = "src/$loader/mod"
  tasks.register<MinecheckLifecycleTest>(taskName) {
    group = "minecheck"
    description =
      "Boots the $loader client + dedicated server in parallel and runs the Minecheck Lua lifecycle test"
    dependsOn("spotlessCheck", ":$loader-mod:spotlessCheck")

    loaderTask = ":$loader:runClient"
    coordinator = "coordinator.lua"
    script = "coordinator.lua"
    serverScript = "server-wait-stop.lua"
    serverLoaderTask = ":$loader:runServer"
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
      ":$loader:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader:runServer",
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

    loaderTask = ":$loader:runClient"
    script = "fail-path-client.lua"
    serverScript = "fail-path-server.lua"
    serverLoaderTask = ":$loader:runServer"
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
      ":$loader:runClient",
      "devHelperClientLaunch",
      "client-launch.txt",
      clientLaunchFile,
      this,
    )
    MinecheckLifecyclePlugin.wireLaunchDescriptor(
      project,
      ":$loader:runServer",
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

tasks.named("test") {
  dependsOn(
    "testFabric",
    "testForge",
    "testQuilt",
    "testFabricFail",
    "testForgeFail",
    "testQuiltFail",
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
