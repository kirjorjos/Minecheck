import java.io.File
import java.io.FileInputStream
import net.minecheck.gradle.LaunchDescriptor

plugins {
  id("xyz.wagyourtail.unimined")
  id("com.gradleup.shadow") version "8.3.0"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

// One branch per Minecraft version; the version comes from gradle.properties (overridable with
// -Pminecraft_version=<mc>). Everything else that differs per version lives beside it.
val mcVersion = property("minecraft_version") as String

// The compatible range the mod metadata declares, from the same single source of truth: "1.18.2"
// accepts 1.18.x, "26.1" accepts 26.1.x.
val mcNext =
  mcVersion.split('.').let { parts ->
    if (parts.size >= 2) "${parts[0]}.${parts[1].toInt() + 1}" else mcVersion
  }

val jeiVersion = "19.8.2.99"
val forgeVersion = "52.1.20"

unimined.minecraft {
  version(mcVersion)
  mappings { mojmap() }
  minecraftForge { loader(forgeVersion) }
  mods { modImplementation {} }
  defaultRemapJar = false
}

repositories {
  maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
  maven("https://maven.blamejared.com") { content { includeGroup("mezz.jei") } }
}

/**
 * The jar unimined hands Forge 52's dev launch: Minecraft and Forge already folded into one jar.
 *
 * <p>Recognised by the FG3 marker in its name; it is the only classpath entry that carries both
 * `net/minecraft/client/main/Main` and `net/minecraftforge/common/MinecraftForge`. It is on the run
 * tasks' classpath, not on the runtime configuration.
 */
fun mergedForgeDevJar(classpath: FileCollection): File =
  classpath.files.firstOrNull { it.isFile && it.name.contains("MinecraftForge-FG3") }
    ?: error("unimined produced no Forge 52 dev jar for $mcVersion")

fun devRunTask(): JavaExec = tasks.named("runServer").get() as JavaExec

/**
 * The jar Forge 52's dev launch expects instead of the merged one: Minecraft alone.
 *
 * <p>FML splits the dev classpath into a `minecraft` module and a `net.minecraftforge.forge` mod,
 * and finds them by name: the jar starting with `forge-<mc>-<forge>` becomes the game, and the jar
 * holding `net/minecraftforge/common/MinecraftForge.class` becomes the mod. Handed one merged jar
 * for both, FML builds the forge module out of Minecraft-only content and the client dies in
 * `ImmediateWindowHandler$DummyProvider.updateModuleReads` with `NoSuchElementException`
 * (issue #287). Stripping the forge packages and naming the result `minecraft` gives FML the two
 * jars it looks for, with the real universal jar on the classpath as the mod.
 */
val forgeDevJarDir = layout.buildDirectory.dir("minecheck/forge-dev")

val stageForgeDevMinecraftJar by
  tasks.registering(Sync::class) {
    group = "minecheck"
    description = "Stages the Minecraft-only dev jar Forge 52's launch expects."
    from({ zipTree(mergedForgeDevJar(devRunTask().classpath)) }) {
      exclude(
        "net/minecraftforge/**",
        "META-INF/mods.toml",
        "META-INF/MANIFEST.MF",
        "META-INF/maven/**",
      )
    }
    into(forgeDevJarDir.map { it.dir("minecraft") })
    doLast {
      val metaInf = forgeDevJarDir.get().dir("minecraft/META-INF").asFile
      metaInf.mkdirs()
      metaInf
        .resolve("MANIFEST.MF")
        .writeText("Manifest-Version: 1.0\nAutomatic-Module-Name: minecraft\n\n")
    }
  }

val forgeDevMinecraftJar by
  tasks.registering(Zip::class) {
    group = "minecheck"
    description = "Packages the Minecraft-only dev jar Forge 52's launch expects."
    from(stageForgeDevMinecraftJar)
    // FML locates the game jar by this prefix, so it cannot be renamed freely.
    archiveFileName.set("forge-$mcVersion-$forgeVersion-minecheck-dev.jar")
    destinationDirectory.set(forgeDevJarDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }

/** The real Forge jar, the mod FML builds `net.minecraftforge.forge` from. */
val forgeUniversal: FileCollection =
  configurations.detachedConfiguration(
    dependencies.create("net.minecraftforge:forge:$mcVersion-$forgeVersion:universal")
  )

/**
 * The classpath a Forge 52 dev run has to start with: the Minecraft-only jar in place of the merged
 * one, and the universal jar after it for FML's mod locator to find.
 */
fun devRunClasspath(task: JavaExec): List<File> {
  val merged = mergedForgeDevJar(task.classpath).absoluteFile
  val minecraftOnly = forgeDevMinecraftJar.get().archiveFile.get().asFile
  return task.classpath.files.map { if (it.absoluteFile == merged) minecraftOnly else it } +
    forgeUniversal.files
}

/**
 * FML's own bootstrap; unimined picks the union relauncher instead, which cannot start Forge 52.
 */
val forgeBootstrapMain = "net.minecraftforge.bootstrap.ForgeBootstrap"

/** The run's JVM arguments without unimined's union relauncher setup. */
fun devJvmArgs(task: JavaExec): List<String> =
  task.jvmArgs.orEmpty().filterNot { it.startsWith("-DunionRelauncher.") }

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
  // Only the class directories, never the sibling projects' jars: unimined hands the dev run all
  // three outputs under one mod id (MOD_CLASSES), so Forge 1.20.2+ builds a single JPMS module from
  // them. Adding the jars too puts the same packages in a second module (`common`, `platform.api`)
  // and ModLauncher dies resolving the layer with a split-package error.
  // Direct, so it is definitely on this module's runtime classpath for the game-layer patch below:
  // unimined's Forge dev launch gives Forge's module classloader this module's classes and not the
  // libraries they link against, so a library needed during mod construction has to be put on the
  // game layer by hand. Without that, every Forge dev client died at construction with
  // `NoClassDefFoundError: org/luaj/vm2/Varargs` (crash reports from 2026-08 onwards).
  implementation("org.luaj:luaj-jse:3.0.1")
  implementation(files(commonOutput))
  implementation(files(platformApiOutput))
  implementation("mezz.jei:jei-1.21-forge:$jeiVersion") { isTransitive = false }
}

tasks.shadowJar {
  configurations = listOf(project.configurations.runtimeClasspath.get())
  // The deobfuscated view of the shipped jar: attached to the release for reading stack traces
  // and writing mixins, never loaded by a game.
  archiveClassifier.set("deobf")
  // Forge's mixin loader is started from this manifest attribute, not from mods.toml: it is what
  // tells FML to apply minecheck.mixins.json before the client constructs its window.
  manifest.attributes["MixinConfigs"] = "minecheck.mixins.json"
  exclude("com/mojang/brigadier/**")
  exclude("com/google/gson/**")
  exclude("com/google/errorprone/**")
  exclude("net/minecraftforge/**")
  exclude("mezz/jei/**")
  exclude("assets/jei/**")
  exclude("jei.mixins.json")
  exclude("jei.refmap.json")
  exclude("META-INF/accesstransformer.cfg")
  exclude("META-INF/services/mezz.jei.**")
  exclude("META-INF/jars/**")
}

tasks.named("jar") {
  dependsOn(tasks.named("shadowJar"))
  enabled = false
}

// The published jar has to be in the loader's *production* namespace. On 1.21 Forge runs on the
// same Mojang names this module compiles against, so unimined reports an empty remap path and the
// task only renames the shadow jar. The root `checkProductionJars` asserts no SRG names slipped in.
// The remap stays registered against the shadow jar (unimined's own remap targets the plain `jar`,
// which this module disables) so shadow -> remap -> the one jar this loader ships keeps working
// unchanged on lines whose production namespace differs.
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

// Forge 1.20.2+ discovers a development mod by scanning one classpath entry for both its
// `mods.toml` and the `@Mod` class that file declares. Gradle's default split (classes under
// `build/classes`, resources under `build/resources`) hands FML a resource-only folder, which it
// rejects with "constructed 0 mods" and "has mods that were not found". Writing the resources
// beside the classes puts them back into the one entry FML scans; the shipped jar is unaffected.
sourceSets.main.get().output.resourcesDir =
  layout.buildDirectory.dir("classes/java/main").get().asFile

// A Forge dev client loads this module as a directory on the classpath, and FML decides whether to
// apply minecheck.mixins.json from the mod's *filesystem* manifest — a classes directory carries
// none unless one of its resources does. With it missing the client starts normally with every
// mixin silently inert: the camera's idle body and Ctrl+scroll were dead that way, and a suite that
// only drives Lua still passed. Fail the build instead of the run.
val mixinConfigName = "minecheck.mixins.json"
val verifyMixinRegistration by
  tasks.registering {
    group = "verification"
    description = "Fails when a Forge dev client would start without $mixinConfigName applied."
    dependsOn(tasks.named("processResources"))
    val resourcesDir = layout.buildDirectory.dir("resources/main")
    val classesDir = layout.buildDirectory.dir("classes/java/main")
    doLast {
      val res = resourcesDir.get().asFile
      val manifest = res.resolve("META-INF/MANIFEST.MF")
      val fix =
        "Add `MixinConfigs: $mixinConfigName` to src/forge/mod/resources/META-INF/MANIFEST.MF " +
          "(the shipped jar declares the same attribute)."
      if (!manifest.isFile) {
        throw GradleException(
          "Forge dev run would load no mixin config: $manifest is missing. $fix"
        )
      }
      val declared =
        manifest
          .readLines()
          .map { it.trim() }
          .firstOrNull { it.startsWith("MixinConfigs:") }
          ?.substringAfter(':')
          ?.split(',')
          ?.map { it.trim() }
          .orEmpty()
      if (mixinConfigName !in declared) {
        throw GradleException(
          "Forge dev run would load no mixin config: $manifest declares MixinConfigs=$declared. $fix"
        )
      }
      val config = res.resolve(mixinConfigName)
      if (!config.isFile) {
        throw GradleException(
          "$mixinConfigName is registered but not in the processed resources: $config"
        )
      }
      @Suppress("UNCHECKED_CAST")
      val json = groovy.json.JsonSlurper().parse(config) as Map<String, Any?>
      val pkg = json["package"] as String
      @Suppress("UNCHECKED_CAST") val listed = json["client"] as List<String>? ?: emptyList()
      for (name in listed) {
        val compiled = classesDir.get().asFile.resolve(pkg.replace('.', '/') + "/$name.class")
        if (!compiled.isFile) {
          throw GradleException(
            "$mixinConfigName lists $name but nothing compiled it: $compiled is missing, so the " +
              "config would fail or silently skip it."
          )
        }
      }
    }
  }

tasks.named("check") { dependsOn(verifyMixinRegistration) }

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
    "classpath" to devRunClasspath(task).joinToString(File.pathSeparator),
    "javaExecutable" to task.javaLauncher.get().executablePath.asFile.absolutePath,
    "mainClass" to forgeBootstrapMain,
    "args" to join(task.args.orEmpty()),
    "jvmArgs" to join(devJvmArgs(task)),
    "systemProperties" to
      join(
        task.systemProperties.map { (k, v) ->
          "${resolvePlaceholders(k, props)}=${resolvePlaceholders(v?.toString() ?: "", props)}"
        }
      ),
    "environment" to
      LaunchDescriptor.encodeEnvironment(
        task.environment.mapValues { (_, v) -> resolvePlaceholders(v?.toString() ?: "", props) }
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

/**
 * Turns off FML's config file watcher in the run's `config/fml.toml`.
 *
 * <p>Forge 1.21 never stops NightConfig's `FileWatcher`: the watcher's scheduled executor is a
 * non-daemon thread, so a dedicated server that ran `stop` saves every dimension and then sits in
 * the JVM for ever while the harness waits for the process to exit. The watcher only reloads a
 * config edited on disk mid-run, which no test does.
 */
fun disableConfigWatcher(runTask: JavaExec) {
  val configDir = (runTask.workingDir as File).resolve("config")
  configDir.mkdirs()
  val file = configDir.resolve("fml.toml")
  val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
  val key = "disableConfigWatcher"
  val index = lines.indexOfFirst { it.trimStart().startsWith(key) }
  if (index >= 0) {
    lines[index] = "$key = true"
  } else {
    lines.add("$key = true")
  }
  file.writeText(lines.joinToString("\n", postfix = "\n"))
  logger.lifecycle("Minecheck: turned off FML's config watcher in {}", file)
}

tasks
  .matching { it.name == "runClient" || it.name == "runServer" }
  .configureEach {
    if (this is JavaExec) {
      val exec = this
      dependsOn(forgeDevMinecraftJar)
      doFirst {
        // The split jar Forge 52 needs (see forgeDevMinecraftJar), started by FML's own bootstrap.
        // unimined's union relauncher is not used: it loads the game into a null-parent
        // classloader, where securemodules cannot see jdk.zipfs and the run dies on
        // `ClassNotFoundException: jdk.nio.zipfs.ZipPath` before Minecraft starts.
        exec.classpath = files(devRunClasspath(exec))
        exec.mainClass.set(forgeBootstrapMain)
        exec.jvmArgs = devJvmArgs(exec)
        patchGameLayerClasspath(exec)
        if (exec.name == "runServer") {
          disableConfigWatcher(exec)
        }
      }
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
    tasks.named("classes"),
    configurations.runtimeClasspath,
    tasks.named("devHelperDownloadAssets"),
    forgeDevMinecraftJar,
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
  dependsOn(tasks.named("classes"), configurations.runtimeClasspath, forgeDevMinecraftJar)
  val out = layout.buildDirectory.file("minecheck/server-launch.txt")
  outputs.file(out)
  doLast {
    val server = tasks.named("runServer").get() as JavaExec
    writeDescriptor(server, out.get().asFile)
    ensureGameLayerRuntimeDeps(server)
    // The lifecycle harness launches from this descriptor rather than through `runServer`, so the
    // config watcher has to be turned off here too: the server that hangs on `stop` is the one the
    // descriptor starts.
    disableConfigWatcher(server)
  }
}

// mods.toml declares the mod version; expand it from the project version so the metadata
// tracks the release rather than a hand-maintained literal.
tasks.processResources {
  // The one declaration of the flat world (also written into the dedicated server's
  // `generator-settings` by the lifecycle plugin), parsed by FlatWorldSettings at world creation.
  from(rootProject.file("tests/flat-world.json"))
  inputs.property("modVersion", project.version)
  filesMatching("META-INF/mods.toml") {
    expand(
      "version" to project.version.toString(),
      "minecraft_version" to mcVersion,
      "minecraft_next" to mcNext,
    )
  }
}
