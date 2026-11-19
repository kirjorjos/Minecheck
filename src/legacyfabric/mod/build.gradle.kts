import java.io.File
import net.minecheck.gradle.LaunchDescriptor

plugins {
  id("xyz.wagyourtail.unimined")
  id("com.gradleup.shadow") version "8.3.0"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

// One branch per Minecraft version; the version comes from gradle.properties (overridable with
// -Pminecraft_version=<mc>). Everything else that differs per version lives beside it.
val mcVersion = property("minecraft_version") as String

// No Fabric module here: Fabric's loader starts at 1.14. Legacy Fabric is the intermediary family
// that covers 1.12.2, so this module is the line's Fabric-shaped loader rather than a reuse of
// `src/fabric`, whose platform classes are written against 1.14+ API.
unimined.minecraft {
  version(mcVersion)
  // Legacy Fabric's intermediary generation 1 and its own yarn fork are the only mappings that
  // cover this line: upstream intermediary starts at 1.14.
  mappings {
    legacyIntermediary()
    // 604 is the newest legacy yarn build for 1.12.2 (meta.legacyfabric.net/v2/versions/yarn).
    legacyYarn(604)
  }
  // 0.20.1 is the newest loader release that still supports 1.12.2.
  legacyFabric { loader("0.20.1") }
  mods { modImplementation {} }
  defaultRemapJar = false
}

repositories {
  maven("https://maven.legacyfabric.net")
  maven("https://maven.fabricmc.net")
  // LWJGL 2's natives are published by Mojang, not on Central.
  maven("https://libraries.minecraft.net/") { content { includeGroup("org.lwjgl.lwjgl") } }
}

// 1.12.2 runs on Java 8: the repo compiles at 17 (root build.gradle.kts, `minecheck.downgradeTo`)
// and the runs use a pinned JDK 8 build rather than whichever one happens to be installed.
val runJavaVersion = providers.gradleProperty("minecheck.runJava").map(String::toInt).orElse(8)

tasks
  .matching { it.name == "runClient" || it.name == "runServer" }
  .configureEach {
    (this as? xyz.wagyourtail.unimined.api.runs.RunConfig)?.javaVersion =
      JavaVersion.toVersion(runJavaVersion.get())
  }

// --- Java 8 wiring (gradle.properties: minecheck.downgradeTo) -----------------------------------
// The mod is compiled at 17 and downgraded to class file 52 in root build.gradle.kts. The dev runs
// and the descriptors the lifecycle tests launch from have to read that downgraded output instead
// of the raw compile output: a Java 8 JVM cannot load class file 61 at all.
val downgradeTo = providers.gradleProperty("minecheck.downgradeTo").map { it.trim().toInt() }.orNull

val downgradedModuleProjects = listOf(project(":common"), project(":platform-api"), project)

fun downgradedRuntimeDir(owner: Project): File =
  owner.layout.buildDirectory.dir("minecheck/java" + downgradeTo!!).get().asFile

/**
 * Maps a runtime-classpath entry onto its downgraded replacement: each project's compiled classes
 * and processed resources become that module's downgraded runtime directory, and the other modules'
 * jars are dropped because those directories carry the same classes. Everything else (Minecraft,
 * the loader, LuaJ) is already loadable on Java 8 and is left alone.
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

// 1.12.2 runs on LWJGL 2, whose native libraries are not on the run classpath by default: the
// client dies with "no lwjgl64 in java.library.path" before it can open a window. Extract them from
// the published natives artefact and point the client's library path at the directory.
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
// `runClient` and never executes it, so `runClient`'s own dependencies never run there.
//
// Both runs need the downgraded classes, not just the client: the dedicated server is the same Java
// 8 runtime, and without the mapping it is handed this module's Java 17 compile output and dies on
// `UnsupportedClassVersionError: ... MinecraftServer ... class file version 61.0` before the world
// loads.
gradle.projectsEvaluated {
  tasks
    .matching { it.name == "runClient" || it.name == "runServer" }
    .configureEach {
      if (this is JavaExec) {
        dependsOn(extractLwjgl2Natives)
        jvmArgs("-Djava.library.path=" + lwjgl2NativeDir.get().asFile.absolutePath)
        if (downgradeTo != null) {
          dependsOn(
            ":common:downgradedRuntime",
            ":platform-api:downgradedRuntime",
            ":legacyfabric-mod:downgradedRuntime",
          )
          classpath = files(classpath.files.mapNotNull { mapToDowngraded(it) })
          // unimined hands the mod to the loader through MOD_CLASSES; the loader reads those
          // paths rather than the classpath, so they point at the downgraded directories too.
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
  // On the runtime classpath directly, not only via the project dependency: the dev launch gives
  // the game's classloader this module's classes and not the libraries they link against, so the
  // script engine's own dependency has to be there by hand.
  implementation("org.luaj:luaj-jse:3.0.1")
  implementation(files(commonOutput))
  implementation(files(platformApiOutput))
}

tasks.processResources {
  // The one declaration of the flat world (also written into the dedicated server's
  // `generator-settings` by the lifecycle plugin), parsed by FlatWorldSettings at world creation.
  from(rootProject.file("tests/flat-world.json"))
  inputs.property("minecraftVersion", mcVersion)
  inputs.property("modVersion", project.version)
  filesMatching("fabric.mod.json") {
    expand(
      "version" to project.version.toString(),
      "minecraft_version" to mcVersion,
      "licence" to providers.gradleProperty("licence").getOrElse(""),
    )
  }
}

// shadow copies `configurations` and asks each for its resolved artifacts, so the downgraded
// inputs are declared as one: the runtime classpath minus the project outputs, plus the
// downgraded directories that replace them.
val downgradedRuntimeInput =
  configurations.create("downgradedRuntimeInput") {
    isCanBeConsumed = false
    isCanBeResolved = true
  }

if (downgradeTo != null) {
  dependencies.add(
    downgradedRuntimeInput.name,
    files(
      downgradedModuleProjects.map { downgradedRuntimeDir(it) } +
        project.configurations.runtimeClasspath.get().files.filter { mapToDowngraded(it) === it }
    ),
  )
}

tasks.shadowJar {
  configurations =
    listOf(
      if (downgradeTo == null) project.configurations.runtimeClasspath.get()
      else downgradedRuntimeInput
    )
  // The deobfuscated view of the shipped jar: attached to the release for reading stack traces and
  // writing mixins, never loaded by a game.
  archiveClassifier.set("deobf")
  exclude("com/mojang/brigadier/**")
  exclude("com/google/gson/**")
  exclude("com/google/errorprone/**")
  exclude("net/minecraft/**")
  exclude("net/legacyfabric/**")
  exclude("net/fabricmc/**")
  exclude("META-INF/jars/**")
}

tasks.named("jar") {
  dependsOn(tasks.named("shadowJar"))
  enabled = false
}

// The published jar has to be in the loader's production namespace, or it dies in a production
// instance. unimined's own remap targets the plain `jar`, which is disabled here, so the remap is
// registered against the shadow jar instead.
val minecraftConfig =
  extensions
    .getByType(xyz.wagyourtail.unimined.api.UniminedExtension::class.java)
    .minecrafts[sourceSets.main.get()]
    ?: error("unimined registered no minecraft config for the main source set")

val productionJar =
  minecraftConfig.remap(tasks.shadowJar.get(), "productionJar") {
    asJar { archiveClassifier.set("") }
  }

tasks.named("assemble") { dependsOn(productionJar) }

sourceSets.main.get().resources.srcDir(project.file("resources"))

private val placeholderRegex = Regex("\\$\\{([^}]+)}")

private fun resolvePlaceholders(s: String, props: Map<String, () -> String>): String =
  placeholderRegex.replace(s) { m -> props[m.groupValues[1]]?.invoke() ?: m.value }

private fun join(c: List<String>, props: Map<String, () -> String>): String =
  LaunchDescriptor.joinList(c.map { resolvePlaceholders(it, props) })

private fun javaExecFields(task: JavaExec): Map<String, String> {
  val props = (task as? xyz.wagyourtail.unimined.api.runs.RunConfig)?.properties ?: emptyMap()
  return linkedMapOf(
    "classpath" to task.classpath.asPath,
    "javaExecutable" to task.javaLauncher.get().executablePath.asFile.absolutePath,
    "mainClass" to task.mainClass.get(),
    "args" to join(task.args.orEmpty(), props),
    "jvmArgs" to join(task.jvmArgs.orEmpty(), props),
    "systemProperties" to
      join(task.systemProperties.map { (k, v) -> "$k=${v?.toString() ?: ""}" }, props),
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

tasks.register("devHelperClientLaunch") {
  dependsOn(
    tasks.named("classes"),
    configurations.runtimeClasspath,
    ":common:downgradedRuntime",
    ":platform-api:downgradedRuntime",
    ":legacyfabric-mod:downgradedRuntime",
  )
  val out = layout.buildDirectory.file("minecheck/client-launch.txt")
  outputs.file(out)
  doLast { writeDescriptor(tasks.named("runClient").get() as JavaExec, out.get().asFile) }
}

tasks.register("devHelperServerLaunch") {
  dependsOn(
    tasks.named("classes"),
    configurations.runtimeClasspath,
    ":common:downgradedRuntime",
    ":platform-api:downgradedRuntime",
    ":legacyfabric-mod:downgradedRuntime",
  )
  val out = layout.buildDirectory.file("minecheck/server-launch.txt")
  outputs.file(out)
  doLast { writeDescriptor(tasks.named("runServer").get() as JavaExec, out.get().asFile) }
}
