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

val emiVersion = "0.7.3+1.18.2"

unimined.minecraft {
  version(mcVersion)
  mappings { yarn(4) }
  quilt { loader("0.20.2") }
  mods { modImplementation {} }
  defaultRemapJar = false
}

val fabricMain =
  project(":fabric-mod")
    .extensions
    .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
    .named("main")
    .get()

val fabricClasses = fabricMain.output.classesDirs

repositories {
  maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
}

tasks.processResources {
  inputs.property("minecraftVersion", mcVersion)
  inputs.property("modVersion", project.version)
  filesMatching("quilt.mod.json") {
    expand("version" to project.version.toString(), "minecraft_version" to mcVersion)
  }
}

dependencies {
  implementation(project(":common"))
  implementation(project(":platform-api"))
  implementation(fabricClasses)
  // QFAPI, not plain Fabric API: the entrypoint implements QSL's ModInitializer, and QSL is only
  // published bundled inside QFAPI (there is no standalone QSL artifact for 1.18.2). 1.0.0-beta.28
  // is the last build for this version.
  add(
    "modImplementation",
    "org.quiltmc.quilted-fabric-api:quilted-fabric-api:1.0.0-beta.28+0.67.0-1.18.2",
  )
  add("modImplementation", "maven.modrinth:emi:$emiVersion") { isTransitive = false }
}

tasks.shadowJar {
  dependsOn(project(":fabric-mod").tasks.named("classes"))
  configurations = listOf(project.configurations.runtimeClasspath.get())
  // The deobfuscated view of the shipped jar: attached to the release for reading stack traces
  // and writing mixins, never loaded by a game.
  archiveClassifier.set("deobf")
  exclude("com/mojang/brigadier/**")
  exclude("com/google/gson/**")
  exclude("com/google/errorprone/**")
  exclude("net/fabricmc/fabric/**")
  exclude("org/quiltmc/qsl/**")
  exclude("org/quiltmc/quilted-fabric-api/**")
  exclude("dev/emi/**")
  exclude("assets/emi/**")
  exclude("emi.mixins.json")
  exclude("emi-fabric.mixins.json")
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

tasks.withType<JavaCompile>().configureEach {
  dependsOn(project(":fabric-mod").tasks.named("classes"))
}

sourceSets.main.get().resources.srcDir(project.file("resources"))

// QFAPI 1.0.0-beta.28 bundles Fabric API 0.67, whose fabric-rendering-fluids-v1 module carries a
// `quilt_loader.recommends` key that Quilt Loader 0.20.2 rejects as invalid metadata — the client
// does not start at all without this. It is the workaround the loader itself suggests in that
// error, and it only relaxes how strictly mod metadata is parsed.
tasks.withType<JavaExec>().configureEach {
  jvmArgs("-Dloader.workaround.disable_strict_parsing=true")
}

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

tasks.register("devHelperClientLaunch") {
  dependsOn(tasks.named("classes"), configurations.runtimeClasspath)
  val out = layout.buildDirectory.file("minecheck/client-launch.txt")
  outputs.file(out)
  doLast { writeDescriptor(tasks.named("runClient").get() as JavaExec, out.get().asFile) }
}

tasks.register("devHelperServerLaunch") {
  dependsOn(tasks.named("classes"), configurations.runtimeClasspath)
  val out = layout.buildDirectory.file("minecheck/server-launch.txt")
  outputs.file(out)
  doLast { writeDescriptor(tasks.named("runServer").get() as JavaExec, out.get().asFile) }
}
