import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import net.minecheck.gradle.LaunchDescriptor

plugins {
  id("xyz.wagyourtail.unimined")
  id("com.gradleup.shadow") version "8.3.0"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

// One branch per Minecraft version; the version comes from gradle.properties (overridable with
// -Pminecraft_version=<mc>). Everything else that differs per version lives beside it.
val mcVersion = property("minecraft_version") as String

// The compatible range the mod metadata declares, from the same single source of truth: "1.18.2"
// accepts 1.18.x, "26.1" accepts 26.1.x.
val mcNext =
  mcVersion.split('.').let { parts ->
    if (parts.size >= 2) "${parts[0]}.${parts[1].toInt() + 1}" else mcVersion
  }

val jeiVersion = "10.2.1.1002"
val emiVersion = "0.7.3+1.18.2"
val reiVersion = "8.4.778+fabric"

// REI does not declare these in its fabric.mod.json, but its own bytecode references them
// (`dev/architectury/**`, `me/shedaniel/clothconfig2/**`), so they must be loaded at runtime.
val clothConfigVersion = "6.5.102"
val architecturyVersion = "4.12.94+fabric"

unimined.minecraft {
  version(mcVersion)
  mappings { yarn(4) }
  fabric { loader("0.15.11") }
  mods { modImplementation {} }
  defaultRemapJar = false
}

repositories {
  maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
}

tasks.processResources {
  inputs.property("minecraftVersion", mcVersion)
  filesMatching("fabric.mod.json") {
    expand("minecraft_version" to mcVersion, "minecraft_next" to mcNext)
  }
}

// Cloth Config for Fabric ships its `me.shedaniel.math` library as a nested jar, and a Fabric
// development run does not expand Jar-in-Jar for classpath mods — while REI links against those
// classes directly (`me/shedaniel/math/Point` in `ClientInternals`). So the dev launch is handed
// the library as a mod of its own. The Forge build of the same Cloth version has the classes
// merged in, so only this loader needs it.
val basicMathVersion = "0.6.1"
val basicMathJar = layout.buildDirectory.file("minecheck/cloth-basic-math-$basicMathVersion.jar")
val extractBasicMath =
  tasks.register("extractBasicMath") {
    val cloth =
      configurations.detachedConfiguration(
        dependencies.create("maven.modrinth:cloth-config:$clothConfigVersion") {
          isTransitive = false
        }
      )
    inputs.files(cloth)
    outputs.file(basicMathJar)
    doLast {
      val out = basicMathJar.get().asFile
      out.parentFile.mkdirs()
      val nested = "META-INF/jars/basic-math-$basicMathVersion.jar"
      ZipFile(cloth.singleFile).use { zip ->
        val entry =
          zip.getEntry(nested)
            ?: throw GradleException("$nested is not in ${cloth.singleFile.name}")
        zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
      }
    }
  }

dependencies {
  implementation(project(":common"))
  implementation(project(":platform-api"))
  add("modImplementation", "net.fabricmc.fabric-api:fabric-api:0.77.0+1.18.2")
  add("modImplementation", "maven.modrinth:jei:$jeiVersion") { isTransitive = false }
  add("modImplementation", "maven.modrinth:emi:$emiVersion") { isTransitive = false }
  add("modImplementation", "maven.modrinth:rei:$reiVersion") { isTransitive = false }
  add("modImplementation", "maven.modrinth:cloth-config:$clothConfigVersion") {
    isTransitive = false
  }
  add("modImplementation", "maven.modrinth:architectury-api:$architecturyVersion") {
    isTransitive = false
  }
  // A plain library (no Minecraft classes), so it goes on the classpath rather than through
  // unimined's mod remapping; the development loader still discovers it by its fabric.mod.json.
  implementation(files(basicMathJar).builtBy(extractBasicMath))
}

tasks.shadowJar {
  configurations = listOf(project.configurations.runtimeClasspath.get())
  archiveClassifier.set("")
  exclude("com/mojang/brigadier/**")
  exclude("com/google/gson/**")
  exclude("com/google/errorprone/**")
  exclude("net/fabricmc/fabric/**")
  exclude("mezz/jei/**")
  exclude("assets/jei/**")
  exclude("jei.mixins.json")
  exclude("jei.refmap.json")
  exclude("jei.accesswidener")
  exclude("dev/emi/**")
  exclude("assets/emi/**")
  exclude("emi.mixins.json")
  exclude("emi-fabric.mixins.json")
  // REI and the two mods it links against (Cloth Config, Architectury API) are dev-only
  // dependencies, exactly like EMI/JEI: never ship a viewer.
  exclude("me/shedaniel/**")
  exclude("assets/roughlyenoughitems/**")
  exclude("assets/cloth-config2/**")
  exclude("dev/architectury/**")
  exclude("rei.mixins.json")
  exclude("RoughlyEnoughItems-fabric-refmap.json")
  exclude("roughlyenoughitems.accessWidener")
  exclude("architectury-common-refmap.json")
  exclude("architectury-common.mixins.json")
  exclude("architectury-fabric-refmap.json")
  exclude("architectury.accessWidener")
  exclude("architectury.mixins.json")
  exclude("META-INF/jars/**")
}

tasks.named("jar") {
  dependsOn(tasks.named("shadowJar"))
  enabled = false
}

tasks.named("assemble") { dependsOn(tasks.named("shadowJar")) }

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
