import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import net.minecheck.gradle.LaunchDescriptor

plugins {
  id("xyz.wagyourtail.unimined")
  id("com.gradleup.shadow") version "8.3.0"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

// One branch per Minecraft version; the version comes from gradle.properties (overridable with
// -Pminecraft_version=<mc>). Everything else that differs per version lives beside it.
val mcVersion = property("minecraft_version") as String

val emiVersion = "1.1.24+1.21.1+fabric"
val qslBaseVersion = "10.0.0-alpha.5+1.21.1"
val qslBaseJarEntry = "META-INF/jars/qsl_base-$qslBaseVersion.jar"

unimined.minecraft {
  version(mcVersion)
  mappings { yarn(3) }
  quilt { loader("0.30.2") }
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
  maven("https://maven.fabricmc.net/") { content { includeGroup("net.fabricmc.fabric-api") } }
  maven("https://maven.quiltmc.org/repository/release") {
    content { includeGroup("org.quiltmc.qsl.core") }
  }
}

/** QSL base as the mod jar the shipped jar nests, rather than as classes it unpacks. */
val qslBaseMod: FileCollection =
  configurations.detachedConfiguration(
    dependencies.create("org.quiltmc.qsl.core:qsl_base:$qslBaseVersion") { isTransitive = false }
  )

// The nested mod has to exist on the development classpath too: Quilt Loader reads `jars` out of
// quilt.mod.json there, and a listed jar it cannot open is a hard failure. The shipped archive gets
// it from the shadow jar instead, which drops jar files it copies.
tasks.processResources { from(qslBaseMod) { into("META-INF/jars") } }

// quilt.mod.json is templated so its Minecraft bound cannot drift from gradle.properties.
tasks.processResources {
  // The one declaration of the flat world (also written into the dedicated server's
  // `generator-settings` by the lifecycle plugin), parsed by FlatWorldSettings at world creation.
  from(rootProject.file("tests/flat-world.json"))
  inputs.property("minecraftVersion", mcVersion)
  inputs.property("modVersion", project.version)
  filesMatching("quilt.mod.json") {
    expand(
      "version" to project.version.toString(),
      "minecraft_version" to mcVersion,
      "qsl_base_jar" to qslBaseJarEntry,
    )
  }
}

dependencies {
  implementation(project(":common"))
  implementation(project(":platform-api"))
  implementation(fabricClasses)
  // Fabric API, not QFAPI: QFAPI has no 1.21.1 release (issue #288), so a 1.21.1 Quilt instance
  // could not install what a jar built against it demands. Quilt Loader loads Fabric API as-is.
  add("modImplementation", "net.fabricmc.fabric-api:fabric-api:0.116.18+1.21.1")
  // QSL base provides the `init` entrypoint phase this loader's entrypoint is registered in. No
  // QFAPI bundles it on 1.21.1, so the mod carries it as a nested jar and Quilt Loader expands it
  // (see the shadow jar); it is compiled against but never on the runtime classpath, or it would
  // load once as a nested mod and once as a classpath mod.
  compileOnly("org.quiltmc.qsl.core:qsl_base:$qslBaseVersion")
  add("modImplementation", "maven.modrinth:emi:$emiVersion") { isTransitive = false }
}

tasks.shadowJar {
  dependsOn(project(":fabric-mod").tasks.named("classes"))
  configurations = listOf(project.configurations.runtimeClasspath.get())
  inputs.files(qslBaseMod)
  // The deobfuscated view of the shipped jar: attached to the release for reading stack traces
  // and writing mixins, never loaded by a game.
  archiveClassifier.set("deobf")
  exclude("com/mojang/brigadier/**")
  exclude("com/google/gson/**")
  exclude("com/google/errorprone/**")
  exclude("net/fabricmc/fabric/**")
  exclude("org/quiltmc/qsl/**")
  exclude("org/quiltmc/quilted-fabric-api/**")
  // The development classpath carries the nested mod as a plain file; Shadow would unpack it and
  // collide with this mod's own quilt.mod.json. The shipped archive gets the jar from `doLast`.
  exclude("META-INF/jars/**")

  exclude("dev/emi/**")
  exclude("assets/emi/**")
  exclude("emi.mixins.json")
  exclude("emi-fabric.mixins.json")
  // QSL base ships beside the mod as a nested mod jar (Quilt Loader expands those): its `init`
  // entrypoint phase is the only reason this loader's entrypoint is registered there, and no
  // QFAPI build bundles it on 1.21.1. Shadow unpacks, or drops, every jar it copies, so the
  // archive is extended after it is written.
  doLast {
    val jar = archiveFile.get().asFile
    val nested = qslBaseMod.singleFile
    check(nested.name == qslBaseJarEntry.substringAfterLast('/')) {
      "quilt.mod.json names $qslBaseJarEntry but $nested resolved"
    }
    val entryName = qslBaseJarEntry
    val rewritten = File(jar.parentFile, "${jar.name}.tmp")
    ZipInputStream(jar.inputStream().buffered()).use { input ->
      ZipOutputStream(rewritten.outputStream().buffered()).use { output ->
        var entry = input.nextEntry
        while (entry != null) {
          if (entry.name != entryName) {
            output.putNextEntry(ZipEntry(entry.name))
            if (!entry.isDirectory) input.copyTo(output)
            output.closeEntry()
          }
          input.closeEntry()
          entry = input.nextEntry
        }
        output.putNextEntry(ZipEntry(entryName))
        nested.inputStream().use { it.copyTo(output) }
        output.closeEntry()
      }
    }
    jar.delete()
    rewritten.renameTo(jar)
  }
}

tasks.named("jar") {
  dependsOn(tasks.named("shadowJar"))
  enabled = false
}

// The published jar has to be in the loader's *production* namespace (intermediary for Quilt), for
// the same reason as Fabric: a jar built against Yarn names loads in the dev runtime and dies in a
// production instance (issue #23). The remap is registered against the shadow jar because this
// module disables the plain `jar`: shadow -> remap -> the one jar this loader ships.
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

// The mixin config is declared as `quilt_loader.mixin`, which is the key Quilt Loader reads into a
// mod's mixin list — a `mixin` *entrypoint* parses fine but registers nothing. Loader 0.20.2's
// strict metadata parser does not list that key among the ones it accepts, so it refuses the mod
// outright and no test can start. This is the relaxation the loader's own error suggests, and the
// 1.18.2 module already passes it for the same reason (QFAPI ships metadata the parser rejects).
// It only loosens how strictly mod metadata is parsed.
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
