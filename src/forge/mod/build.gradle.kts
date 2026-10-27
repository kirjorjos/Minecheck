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

val emiVersion = "1.1.24+1.19.2+forge"
val jeiVersion = "11.6.0.1024"
val reiVersion = "9.2.784+forge"

// REI's mods.toml makes both mandatory (`architectury` >= 5.7, `cloth_config` >= 7.0).
val clothConfigVersion = "8.3.134+forge"
val architecturyVersion = "6.6.92+forge"

unimined.minecraft {
  version(mcVersion)
  mappings { mojmap() }
  minecraftForge { loader("43.4.0") }
  mods { modImplementation {} }
  defaultRemapJar = false
}

repositories {
  maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
  maven("https://maven.blamejared.com") { content { includeGroup("mezz.jei") } }
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
  implementation(files(commonOutput))
  implementation(files(platformApiOutput))
  add("modImplementation", "maven.modrinth:emi:$emiVersion") { isTransitive = false }
  add("modImplementation", "mezz.jei:jei-1.19.2-forge:$jeiVersion") { isTransitive = false }
  add("modImplementation", "maven.modrinth:rei:$reiVersion") { isTransitive = false }
  add("modImplementation", "maven.modrinth:cloth-config:$clothConfigVersion") {
    isTransitive = false
  }
  add("modImplementation", "maven.modrinth:architectury-api:$architecturyVersion") {
    isTransitive = false
  }
}

tasks.shadowJar {
  configurations = listOf(project.configurations.runtimeClasspath.get())
  archiveClassifier.set("")
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

fun ensureGameLayerRuntimeDeps(runTask: JavaExec) {
  val props = (runTask as? xyz.wagyourtail.unimined.api.runs.RunConfig)?.properties ?: emptyMap()
  val legacy = props["minecraft_classpath_file"]?.invoke() ?: return
  val file = File(legacy)
  if (!file.exists()) return
  val text = file.readText()
  val missing =
    configurations.runtimeClasspath
      .get()
      .files
      .filter { it.name.contains("luaj") }
      .filter { !text.contains(it.absolutePath) }
  if (missing.isNotEmpty()) {
    val sb = StringBuilder(if (text.endsWith("\n")) text else text + "\n")
    for (dep in missing) {
      sb.append(dep.absolutePath).append('\n')
    }
    file.writeText(sb.toString())
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
