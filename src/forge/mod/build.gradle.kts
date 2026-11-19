import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest
import net.minecheck.gradle.LaunchDescriptor
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withGroovyBuilder

plugins {
  id("xyz.wagyourtail.unimined")
  id("com.gradleup.shadow") version "8.3.0"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

// One branch per Minecraft version; the version comes from gradle.properties (overridable with
// -Pminecraft_version=<mc>). Everything else that differs per version lives beside it.
val mcVersion = property("minecraft_version") as String

// The compatible range `mods.toml` declares, from the same single source of truth: "1.18.2"
// accepts 1.18.x, "26.1" accepts 26.1.x.
val mcNext =
  mcVersion.split('.').let { parts ->
    if (parts.size >= 2) "${parts[0]}.${parts[1].toInt() + 1}" else mcVersion
  }

unimined.minecraft {
  version(mcVersion)
  // No Mojang mappings this far back: 1.12.2 is mapped by MCP, and stable_39 is the last stable
  // release for it. FG 2.x suffixes the mapping version with the game version, so the artifact is
  // `mcp_stable:39-1.12`; without the suffix nothing publishes that coordinate. Forge resolves its
  // production members by SRG name, so the remapped jar is still checked by `checkProductionJars`.
  mappings { mcp("stable", "39-1.12") }
  // 14.23.5.2860 is the last 1.12.2 Forge build, and the one a user on this version installs.
  minecraftForge { loader("14.23.5.2860") }
  mods { modImplementation {} }
  defaultRemapJar = false
}

// Forge on 1.12.2 is Java 8 and only Java 8, and it predates ModLauncher entirely: it loads mods
// through FML. The repo compiles at 17, so this line's bytecode is downgraded to class file 52
// (root build.gradle.kts, `minecheck.downgradeTo`) and the runs use a pinned JDK 8 build rather
// than whichever one happens to be installed. The line also has no Mixin at all — Mixin only
// reaches Forge with 1.15 — so its client hooks are native Forge events.
val runJavaVersion = providers.gradleProperty("minecheck.runJava").map(String::toInt).orElse(8)

tasks
  .matching { it.name == "runClient" || it.name == "runServer" }
  .configureEach {
    (this as? xyz.wagyourtail.unimined.api.runs.RunConfig)?.javaVersion =
      JavaVersion.toVersion(runJavaVersion.get())
  }

// 1.12.2 runs on LWJGL 2, whose native libraries are not put on the run classpath by default: the
// client died with "no lwjgl64 in java.library.path" before it could open a window. Extract them
// from the published natives artefact and point the client's library path at the directory.
val lwjgl2Natives by configurations.creating

dependencies {
  lwjgl2Natives("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-linux")
}

val lwjgl2NativeDir = layout.buildDirectory.dir("lwjgl2-natives")

val extractLwjgl2Natives by
  tasks.registering(Copy::class) {
    from(lwjgl2Natives.map { zipTree(it) })
    into(lwjgl2NativeDir)
  }

// The lifecycle tests launch from a descriptor written by `devHelperClientLaunch`, which reads
// `runClient` and never executes it, so `runClient`'s own dependencies never run there. Wiring the
// extraction here as well means the directory exists in both launch paths.
gradle.projectsEvaluated {
  tasks
    .matching { it.name == "runClient" }
    .configureEach {
      if (this is JavaExec) {
        dependsOn(extractLwjgl2Natives)
        jvmArgs("-Djava.library.path=" + lwjgl2NativeDir.get().asFile.absolutePath)
      }
    }
}

repositories {
  // LWJGL 2's natives are published by Mojang, not on Central.
  maven("https://libraries.minecraft.net/") { content { includeGroup("org.lwjgl.lwjgl") } }
  maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
  maven("https://maven.blamejared.com") { content { includeGroup("mezz.jei") } }
  maven("https://maven.shedaniel.me/") { content { includeGroup("me.shedaniel.cloth") } }
  maven("https://maven.architectury.dev/") { content { includeGroup("dev.architectury") } }
}

tasks.processResources {
  // The one declaration of the flat world (also written into the dedicated server's
  // `generator-settings` by the lifecycle plugin), parsed by FlatWorldSettings at world creation.
  from(rootProject.file("tests/flat-world.json"))
  inputs.property("minecraftVersion", mcVersion)
  inputs.property("modVersion", project.version)
  // 1.12.2's FML reads mcmod.info, not mods.toml; the version and game version are stamped in
  // from the same single source (`gradle.properties` and the project version).
  filesMatching("mcmod.info") {
    expand("version" to project.version.toString(), "minecraft_version" to mcVersion)
  }
}

val commonOutput =
  project(":common")
    .extensions
    .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
    .named("main")
    .get()
    .output
val platformApiOutput =
  project(":platform-api")
    .extensions
    .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
    .named("main")
    .get()
    .output

dependencies {
  implementation(project(":common"))
  implementation(project(":platform-api"))
  // Direct, so it is definitely on this module's runtime classpath for the game-layer patch below:
  // unimined's Forge dev launch gives Forge's module classloader this module's classes and not the
  // libraries they link against, so a library needed during mod construction has to be put on the
  // game layer by hand. Without that, every Forge dev client died at construction with
  // `NoClassDefFoundError: org/luaj/vm2/Varargs` (crash reports from 2026-08 onwards).
  implementation("org.luaj:luaj-jse:3.0.1")
  implementation(files(commonOutput))
  implementation(files(platformApiOutput))
}

tasks.shadowJar {
  configurations = listOf(project.configurations.runtimeClasspath.get())
  // The deobfuscated view of the shipped jar: attached to the release for reading stack traces
  // and writing mixins, never loaded by a game.
  archiveClassifier.set("deobf")
  exclude("com/mojang/brigadier/**")
  exclude("com/google/gson/**")
  exclude("com/google/errorprone/**")
  exclude("net/minecraftforge/**")
  exclude("dev/emi/**")
  exclude("assets/emi/**")
  exclude("emi.mixins.json")
  exclude("emi-forge.mixins.json")
  exclude("mezz/jei/**")
  exclude("assets/jei/**")
  exclude("jei.mixins.json")
  exclude("jei.refmap.json")
  exclude("META-INF/accesstransformer.cfg")
  exclude("META-INF/services/mezz.jei.**")
  // REI and the two mods it requires (Cloth Config, Architectury API) are dev-only dependencies.
  exclude("me/shedaniel/**")
  exclude("assets/roughlyenoughitems/**")
  exclude("assets/cloth-config2/**")
  exclude("dev/architectury/**")
  exclude("rei.mixins.json")
  exclude("rei.refmap.json")
  exclude("roughlyenoughitems.accessWidener")
  exclude("architectury-common-refmap.json")
  exclude("architectury-common.mixins.json")
  exclude("architectury-forge-refmap.json")
  exclude("architectury.accessWidener")
  exclude("architectury.mixins.json")
  exclude("META-INF/services/dev.architectury.**")
  exclude("META-INF/jars/**")
}

tasks.named("jar") {
  dependsOn(tasks.named("shadowJar"))
  enabled = false
}

// The published jar has to be in the loader's *production* namespace, or it dies in a production
// instance (issue #23). unimined's own remap targets the plain `jar`, which this module disables
// because the shadowJar is the mod, so the remap is registered against the shadow jar instead:
// shadow (-deobf) -> remap -> the jar a launcher gets.
val minecraftConfig =
  extensions
    .getByType(xyz.wagyourtail.unimined.api.UniminedExtension::class.java)
    .minecrafts[sourceSets.main.get()]
    ?: error("unimined registered no minecraft config for the main source set")

// The jar a standard instance is handed: unclassified in CI, `-dev-<n>` in a local build. The tag
// comes from the root project, which takes the next free number across every loader, so one build
// pairs its three jars under one tag and the next build never overwrites them.
val localDevTag = rootProject.extra["localDevTag"] as Int

val productionJar =
  minecraftConfig.remap(tasks.shadowJar.get(), "productionJar") {
    asJar { archiveClassifier.set(if (localDevTag == 0) "" else "dev-$localDevTag") }
  }

tasks.named("assemble") { dependsOn(productionJar) }

sourceSets.main.get().resources.srcDir(project.file("resources"))

tasks.register("printServerClasspath") {
  doLast { println(configurations.runtimeClasspath.get().asPath) }
}

val dhServerInput = System.getProperty("dh.serverInput")

if (dhServerInput != null) {
  tasks
    .matching { it.name == "runServer" }
    .configureEach {
      if (this is JavaExec) {
        standardInput = FileInputStream(File(dhServerInput))
      }
    }
}

val dhServerSetupDir = System.getProperty("dh.serverSetupDir")

if (dhServerSetupDir != null) {
  tasks
    .matching { it.name == "runServer" }
    .configureEach {
      doFirst {
        val setupDir = file(dhServerSetupDir)
        val target: File =
          when (this) {
            is JavaExec ->
              when (val wd = workingDir) {
                is File -> wd
                else -> file(wd.toString())
              }
            else -> throw IllegalStateException("runServer is not a JavaExec")
          }
        target.mkdirs()
        copy {
          from(setupDir) { include("**") }
          into(target)
        }
      }
    }
}

val downgradeTo = providers.gradleProperty("minecheck.downgradeTo").map { it.trim().toInt() }.orNull

val downgradedModuleProjects = listOf(project(":common"), project(":platform-api"), project)

fun downgradedRuntimeDir(owner: Project): File =
  owner.layout.buildDirectory.dir("minecheck/java" + downgradeTo!!).get().asFile

/**
 * The jar that replaces a downgraded class directory on a launch classpath, or null for an entry
 * that is not one of those directories.
 *
 * <p>FML 1.12.2 walks the launch classpath looking for coremods and opens every entry with {@code
 * new ZipFile}; handed a directory it throws {@code FileNotFoundException: ... (Is a directory)} on
 * every launch. The directories stay on MOD_CLASSES — Forge loads the mod from them — but the
 * classpath carries a jar of the same classes, which FML can read.
 *
 * <p>The jar holds the classes because the launch classloader must be able to see them (mixins and
 * coremod hooks resolve mod types from it), which is also why a stale jar is worse than useless: it
 * shadows the directories. The task that writes it therefore always reruns.
 */
fun downgradedJarFor(entry: File): File? {
  if (downgradeTo == null) return null
  val owner =
    downgradedModuleProjects.firstOrNull { downgradedRuntimeDir(it) == entry } ?: return null
  return layout.buildDirectory.file("minecheck/java8-jars/${owner.name}-java8.jar").get().asFile
}

/**
 * Maps a runtime-classpath entry onto its downgraded replacement: each project's compiled classes
 * and processed resources become that module's downgraded runtime directory, and the two project
 * jars are dropped because that directory replaces them. Everything else (Minecraft, Forge, LuaJ)
 * is already loadable on Java 8 and is left alone. Returns null for an entry to drop.
 */
fun mapToDowngraded(file: File): File? {
  if (downgradeTo == null) return file
  for (owner in downgradedModuleProjects) {
    val build = owner.layout.buildDirectory.get().asFile
    if (file == File(build, "classes/java/main") || file == File(build, "resources/main")) {
      return downgradedRuntimeDir(owner)
    }
    if (owner != project && file.parentFile == File(build, "libs")) return null
  }
  return file
}

/**
 * Points unimined's MOD_CLASSES at the downgraded directories. It is rewritten when the descriptor
 * is written, not at configuration time, because unimined only puts MOD_CLASSES on the run task
 * later; Forge loads the mod from these paths rather than from the classpath.
 */
fun rewriteModClasses(key: String, value: String): String {
  if (key != "MOD_CLASSES" || downgradeTo == null) return value
  return value.split(File.pathSeparator).joinToString(File.pathSeparator) { entry ->
    val parts = entry.split("%%", limit = 2)
    if (parts.size != 2) entry
    else "${parts[0]}%%${mapToDowngraded(File(parts[1]))?.absolutePath ?: parts[1]}"
  }
}

// The run JDK is pinned rather than taken from whichever Java 8 the machine or CI happens to have:
// Forge 35 ships ModLauncher 8.0.9, which calls sun.security.util.ManifestEntryVerifier(Manifest);
// later JDK 8 builds no longer have that constructor (8u512, 11 and 17 all fail the same way). Each
// archive below is the same Zulu build, so every platform runs the same runtime.
data class PinnedJdkArchive(val url: String, val sha256: String)

val pinnedJdkVersion = "8.0.292"

val pinnedJdkArchives =
  mapOf(
    ("linux" to "x86_64") to
      PinnedJdkArchive(
        "https://cdn.azul.com/zulu/bin/zulu8.54.0.21-ca-jdk8.0.292-linux_x64.tar.gz",
        "1a15b84a4d9dfb44a22c0335b36e4c1d9c860a0fa3aaa92f023543deaca25c10",
      ),
    ("macos" to "x86_64") to
      PinnedJdkArchive(
        "https://cdn.azul.com/zulu/bin/zulu8.54.0.21-ca-jdk8.0.292-macosx_x64.tar.gz",
        "369e0d0d76d73cb161978ff07d7020820bb3cd2465f6f91219bb6492e302f4dd",
      ),
    ("macos" to "aarch64") to
      PinnedJdkArchive(
        "https://cdn.azul.com/zulu/bin/zulu8.54.0.21-ca-jdk8.0.292-macosx_aarch64.tar.gz",
        "2cf0af3118dd4b6244724308b190587804a76ad7919b6da5c377392a1d4f8817",
      ),
    ("windows" to "x86_64") to
      PinnedJdkArchive(
        "https://cdn.azul.com/zulu/bin/zulu8.54.0.21-ca-jdk8.0.292-win_x64.zip",
        "35fc03994a65f62afa9510ff0044f52228208fddc7bbfa0cfd8e332b6e52c384",
      ),
  )

fun pinnedJdkOs(): String =
  when {
    org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "macos"
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "windows"
    else -> throw GradleException("Minecheck: unknown platform for the pinned JDK")
  }

fun pinnedJdkArch(): String =
  when (val arch = System.getProperty("os.arch")) {
    "aarch64",
    "arm64" -> "aarch64"
    "x86_64",
    "amd64" -> "x86_64"
    else -> throw GradleException("Minecheck: no pinned JDK $pinnedJdkVersion for $arch")
  }

val pinnedJdkArchive: PinnedJdkArchive =
  pinnedJdkArchives[pinnedJdkOs() to pinnedJdkArch()]
    ?: throw GradleException(
      "Minecheck: no pinned JDK $pinnedJdkVersion archive for ${pinnedJdkOs()}/${pinnedJdkArch()}; " +
        "add one to pinnedJdkArchives."
    )

val pinnedJdkDir = layout.buildDirectory.dir("jdk/$pinnedJdkVersion")

val pinnedJdkJavaFile =
  pinnedJdkDir
    .get()
    .file(
      if (org.gradle.internal.os.OperatingSystem.current().isWindows) "bin/java.exe" else "bin/java"
    )
    .asFile

private val placeholderRegex = Regex("\\$\\{([^}]+)}")

private fun resolvePlaceholders(s: String, props: Map<String, () -> String>): String {
  return placeholderRegex.replace(s) { m ->
    val key = m.groupValues[1]
    props[key]?.invoke() ?: m.value
  }
}

private fun javaExecFields(task: JavaExec): Map<String, String> {
  val props = (task as? xyz.wagyourtail.unimined.api.runs.RunConfig)?.properties ?: emptyMap()
  fun join(c: List<String>): String =
    LaunchDescriptor.joinList(c.map { resolvePlaceholders(it, props) })
  return linkedMapOf(
    "classpath" to
      task.classpath.files.joinToString(File.pathSeparator) { entry ->
        (downgradedJarFor(entry)?.takeIf { it.isFile } ?: entry).absolutePath
      },
    "javaExecutable" to
      (pinnedJdkJavaFile.takeIf { it.isFile }?.absolutePath
        ?: task.javaLauncher.get().executablePath.asFile.absolutePath),
    "mainClass" to task.mainClass.get(),
    "args" to join(task.args.orEmpty()),
    "jvmArgs" to join(task.jvmArgs.orEmpty()),
    "systemProperties" to
      join(
        task.systemProperties.map { (k, v) ->
          "${resolvePlaceholders(k, props)}=${resolvePlaceholders(v?.toString() ?: "", props)}"
        }
      ),
    "environment" to
      LaunchDescriptor.encodeEnvironment(
        task.environment.mapValues { (k, v) ->
          rewriteModClasses(k, resolvePlaceholders(v?.toString() ?: "", props))
        }
      ),
    "workingDir" to (task.workingDir as File).absolutePath,
  )
}

fun writeDescriptor(task: JavaExec, out: File) {
  out.parentFile.mkdirs()
  val sb = StringBuilder()
  javaExecFields(task).forEach { (k, v) -> sb.append(k).append('=').append(v).append('\n') }
  out.writeText(sb.toString())
}

/**
 * The classpath file Forge's game module layer is built from.
 *
 * <p>Found through the run's own `minecraft_classpath_file` property when unimined exposes one, and
 * otherwise at unimined's documented location. The fallback matters: the property is not always
 * present on the run task this early, and the old code simply returned in that case — silently, so
 * the client died at mod construction instead of at build time.
 */
fun legacyClasspathFile(runTask: JavaExec?): File {
  val props =
    (runTask as? xyz.wagyourtail.unimined.api.runs.RunConfig)?.properties
      ?: emptyMap<String, () -> String>()
  val configured = props["minecraft_classpath_file"]?.invoke()
  if (configured != null) {
    return File(configured)
  }
  return layout.projectDirectory.file(".gradle/unimined/local/legacy_classpath.txt").asFile
}

/**
 * Puts this module's plain libraries (currently LuaJ) on the game layer.
 *
 * <p>Unimined only merges the mod's own classes into the module it launches; a library the mod
 * needs at construction time is on the Gradle runtime classpath but invisible to Forge's {@code
 * ModuleClassLoader}, which is why the mod could never construct in a dev client. The file below is
 * regenerated on every configuration of this project — even a {@code gradlew help} — so a patch
 * made by an earlier, separate invocation is always gone by launch time; the hooks at the bottom of
 * this file re-apply it in the build that actually runs the game.
 */
fun patchGameLayerClasspath(runTask: JavaExec? = null) {
  val file = legacyClasspathFile(runTask)
  if (!file.isFile) {
    logger.warn(
      "Minecheck: no Forge legacy classpath file at {} — LuaJ will not be on the game layer",
      file,
    )
    return
  }
  val text = file.readText()
  val missing =
    configurations.runtimeClasspath
      .get()
      .files
      .filter { !it.isDirectory && it.name.contains("luaj") }
      .filter { !text.contains(it.absolutePath) }
  if (missing.isEmpty()) {
    return
  }
  val sb = StringBuilder(if (text.endsWith("\n")) text else text + "\n")
  for (dep in missing) {
    sb.append(dep.absolutePath).append('\n')
  }
  file.writeText(sb.toString())
  logger.lifecycle("Minecheck: added {} to the Forge game layer", missing.joinToString { it.name })
}

fun ensureGameLayerRuntimeDeps(runTask: JavaExec) {
  patchGameLayerClasspath(runTask)
}

// After every project has configured (i.e. after unimined wrote the file), and again immediately
// before a dev run starts, so the patch belongs to the invocation that launches the game.
gradle.projectsEvaluated { patchGameLayerClasspath() }

tasks
  .matching { it.name == "runClient" || it.name == "runServer" }
  .configureEach {
    if (this is JavaExec) {
      val exec = this
      doFirst { patchGameLayerClasspath(exec) }
    }
  }

tasks.register("devHelperDownloadAssets") {
  doLast {
    val ext =
      project.extensions.getByType(xyz.wagyourtail.unimined.api.UniminedExtension::class.java)
        as xyz.wagyourtail.unimined.UniminedExtensionImpl
    val mainSourceSet =
      project.extensions
        .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
        .named("main")
        .get()
    val mc = ext.minecrafts[mainSourceSet] ?: return@doLast
    val data =
      mc.minecraftData as? xyz.wagyourtail.unimined.internal.minecraft.resolver.MinecraftDownloader
        ?: return@doLast
    val assetIndex = data.metadata.assetIndex ?: return@doLast
    xyz.wagyourtail.unimined.internal.minecraft.resolver.AssetsDownloader.downloadAssets(
      project,
      assetIndex,
    )
  }
}

tasks.register("devHelperClientLaunch") {
  dependsOn(
    installPinnedJdk,
    ":common:downgradedRuntime",
    ":platform-api:downgradedRuntime",
    ":forge-mod:downgradedRuntime",
    tasks.named("classes"),
    configurations.runtimeClasspath,
    tasks.named("devHelperDownloadAssets"),
    extractLwjgl2Natives,
  )
  val out = layout.buildDirectory.file("minecheck/client-launch.txt")
  outputs.file(out)
  doLast {
    val client = tasks.named("runClient").get() as JavaExec
    writeDescriptor(client, out.get().asFile)
    ensureGameLayerRuntimeDeps(client)
  }
}

tasks.register("devHelperServerLaunch") {
  dependsOn(
    installPinnedJdk,
    ":common:downgradedRuntime",
    ":platform-api:downgradedRuntime",
    ":forge-mod:downgradedRuntime",
    tasks.named("classes"),
    configurations.runtimeClasspath,
  )
  val out = layout.buildDirectory.file("minecheck/server-launch.txt")
  outputs.file(out)
  doLast {
    val server = tasks.named("runServer").get() as JavaExec
    writeDescriptor(server, out.get().asFile)
    ensureGameLayerRuntimeDeps(server)
  }
}

// --- Java 8 runtime wiring (gradle.properties: minecheck.downgradeTo) -------------------------
// The mod is compiled at 17 and downgraded to class file 52 in root build.gradle.kts. Both the dev
// runs and the shadow jar have to read that downgraded output instead of the raw compile output: a
// Java 8 JVM cannot load class file 61 at all.
if (downgradeTo != null) {
  // The shipped jar is the shadow jar, so its input is the downgraded output rather than the 17
  // classes: `assemble`, the release assets and the remap then all carry Java 8 bytecode without a
  // second pass over a finished jar.
  // shadow copies `configurations` and asks each for its resolved artifacts, so the downgraded
  // inputs are declared as one: the runtime classpath minus the project outputs, plus the
  // downgraded directories that replace them.
  val downgradedRuntimeInput =
    configurations.create("downgradedRuntimeInput") {
      isCanBeConsumed = false
      isCanBeResolved = true
    }
  dependencies.add(
    downgradedRuntimeInput.name,
    files(
      downgradedModuleProjects.map { downgradedRuntimeDir(it) } +
        project.configurations.runtimeClasspath.get().files.filter { mapToDowngraded(it) === it }
    ),
  )

  tasks.shadowJar {
    dependsOn(
      ":common:downgradedRuntime",
      ":platform-api:downgradedRuntime",
      ":forge-mod:downgradedRuntime",
    )
    configurations = listOf(downgradedRuntimeInput)
  }

  val downgradedJarDir = layout.buildDirectory.dir("minecheck/java8-jars")

  val packageDowngradedRuntime by
    tasks.registering {
      group = "minecheck"
      description = "Packages the downgraded class directories so FML can scan them as jars"
      dependsOn(
        ":common:downgradedRuntime",
        ":platform-api:downgradedRuntime",
        ":forge-mod:downgradedRuntime",
      )
      outputs.dir(downgradedJarDir)
      // Always repackage. The task declares no inputs (its content comes from other projects'
      // outputs), so Gradle judged it up to date while the directories underneath had been rebuilt
      // — and the stale jars sat on the launch classpath, where parent-first delegation made the
      // game load yesterday's mod classes instead of the directories on MOD_CLASSES.
      outputs.upToDateWhen { false }
      doLast {
        val out = downgradedJarDir.get().asFile
        out.mkdirs()
        for (owner in downgradedModuleProjects) {
          val dir = downgradedRuntimeDir(owner)
          val jar = File(out, owner.name + "-java8.jar")
          jar.delete()
          ant.withGroovyBuilder { "zip"("destfile" to jar, "basedir" to dir) }
        }
      }
    }

  // The descriptors are written from the run tasks without executing them, so the jar packaging
  // has to be a dependency of the descriptor tasks too.
  tasks
    .matching { it.name == "devHelperClientLaunch" || it.name == "devHelperServerLaunch" }
    .configureEach { dependsOn(packageDowngradedRuntime) }

  // The dev runs (and the descriptors the lifecycle tests launch from) get the same treatment:
  // their classpath and unimined's MOD_CLASSES both point at the downgraded directories.
  gradle.projectsEvaluated {
    tasks
      .matching { it.name == "runClient" || it.name == "runServer" }
      .configureEach {
        if (this is JavaExec) {
          dependsOn(
            packageDowngradedRuntime,
            ":common:downgradedRuntime",
            ":platform-api:downgradedRuntime",
            ":forge-mod:downgradedRuntime",
          )
          classpath = files(classpath.files.mapNotNull { mapToDowngraded(it) })
          val modClasses = environment["MOD_CLASSES"]
          if (modClasses != null) {
            environment["MOD_CLASSES"] =
              modClasses.toString().split(File.pathSeparator).joinToString(File.pathSeparator) {
                entry ->
                val parts = entry.split("%%", limit = 2)
                if (parts.size != 2) entry
                else "${parts[0]}%%${mapToDowngraded(File(parts[1]))?.absolutePath ?: parts[1]}"
              }
          }
        }
      }
  }
}

// The run JDK is pinned rather than taken from whichever Java 8 the machine or CI happens to have.
// Forge 35 ships ModLauncher 8.0.9, which calls sun.security.util.ManifestEntryVerifier(Manifest);
// later JDK 8 builds no longer have that constructor (8u512, 11 and 17 all fail the same way, which
// is what the --add-opens block removed from this file was chasing). One checksum-verified
// download, reused by every later run.
val installPinnedJdk by
  tasks.registering {
    group = "minecheck"
    description = "Downloads and verifies the JDK $pinnedJdkVersion the Forge runs launch with"
    outputs.dir(pinnedJdkDir)
    doLast {
      val dir = pinnedJdkDir.get().asFile
      if (pinnedJdkJavaFile.isFile) return@doLast
      val archive =
        layout.buildDirectory
          .file("jdk/${pinnedJdkArchive.url.substringAfterLast('/')}")
          .get()
          .asFile
      archive.parentFile.mkdirs()
      if (!archive.isFile) {
        URI(pinnedJdkArchive.url).toURL().openStream().use { input ->
          archive.outputStream().use { input.copyTo(it) }
        }
      }
      val actual =
        MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") {
          "%02x".format(it)
        }
      if (actual != pinnedJdkArchive.sha256) {
        archive.delete()
        throw GradleException("Minecheck: pinned JDK checksum mismatch: got $actual")
      }
      dir.mkdirs()
      if (archive.name.endsWith(".zip")) {
        // The Windows archive carries a single top-level directory; the others are tarred with
        // --strip-components=1 and land straight in dir.
        val staging = layout.buildDirectory.dir("jdk/.extract-$pinnedJdkVersion").get().asFile
        staging.deleteRecursively()
        staging.mkdirs()
        copy {
          from(zipTree(archive))
          into(staging)
        }
        val root =
          staging.listFiles()?.singleOrNull()
            ?: throw GradleException("Minecheck: unexpected layout in $archive")
        copy {
          from(root)
          into(dir)
        }
        staging.deleteRecursively()
      } else {
        val tar =
          ProcessBuilder(
              "tar",
              "xzf",
              archive.absolutePath,
              "-C",
              dir.absolutePath,
              "--strip-components=1",
            )
            .inheritIO()
            .start()
        tar.waitFor()
        if (tar.exitValue() != 0) {
          throw GradleException(
            "Minecheck: extracting the pinned JDK failed (tar ${tar.exitValue()})"
          )
        }
      }
      if (!pinnedJdkJavaFile.isFile) {
        throw GradleException(
          "Minecheck: pinned JDK has no ${pinnedJdkJavaFile.name} at $pinnedJdkJavaFile"
        )
      }
    }
  }

gradle.projectsEvaluated {
  tasks
    .matching { it.name == "runClient" || it.name == "runServer" }
    .configureEach {
      if (this is JavaExec) {
        dependsOn(installPinnedJdk)
        doFirst { setExecutable(pinnedJdkJavaFile.absolutePath) }
      }
    }
}
