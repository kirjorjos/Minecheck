import java.io.File
import java.io.FileInputStream
import net.minecheck.gradle.LaunchDescriptor

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

val jeiVersion = "10.2.1.1002"
val reiVersion = "8.4.778+forge"

// REI's mods.toml makes both mandatory (`architectury`, `cloth_config`), left implicit on 1.18.2.
// Both are taken from their authors' own mavens rather than Modrinth: a Modrinth version there
// carries a Fabric and a Forge file, and the primary file — the one a bare coordinate resolves to —
// is the Fabric jar, which FML then rejects as "cloth_config not installed".
val clothConfigVersion = "6.5.102"
val architecturyVersion = "4.12.94"

unimined.minecraft {
  version(mcVersion)
  mappings { mojmap() }
  minecraftForge { loader("40.3.12") }
  mods { modImplementation {} }
  defaultRemapJar = false
}

repositories {
  maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
  maven("https://maven.blamejared.com") { content { includeGroup("mezz.jei") } }
  maven("https://maven.shedaniel.me/") { content { includeGroup("me.shedaniel.cloth") } }
  maven("https://maven.architectury.dev/") { content { includeGroup("dev.architectury") } }
}

tasks.processResources {
  inputs.property("minecraftVersion", mcVersion)
  inputs.property("modVersion", project.version)
  filesMatching("META-INF/mods.toml") {
    expand(
      "version" to project.version.toString(),
      "minecraft_version" to mcVersion,
      "minecraft_next" to mcNext,
    )
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
  // EMI has no Forge artifact on 1.18.2 (Modrinth lists it for fabric/quilt only), so the Forge
  // module is bridged against JEI/REI alone.
  add("modImplementation", "mezz.jei:jei-1.18.2-forge:$jeiVersion") { isTransitive = false }
  add("modImplementation", "maven.modrinth:rei:$reiVersion") { isTransitive = false }
  add("modImplementation", "me.shedaniel.cloth:cloth-config-forge:$clothConfigVersion") {
    isTransitive = false
  }
  add("modImplementation", "dev.architectury:architectury-forge:$architecturyVersion") {
    isTransitive = false
  }
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
    "classpath" to task.classpath.asPath,
    "javaExecutable" to task.javaLauncher.get().executablePath.asFile.absolutePath,
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
    tasks.named("classes"),
    configurations.runtimeClasspath,
    tasks.named("devHelperDownloadAssets"),
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
  dependsOn(tasks.named("classes"), configurations.runtimeClasspath)
  val out = layout.buildDirectory.file("minecheck/server-launch.txt")
  outputs.file(out)
  doLast {
    val server = tasks.named("runServer").get() as JavaExec
    writeDescriptor(server, out.get().asFile)
    ensureGameLayerRuntimeDeps(server)
  }
}
