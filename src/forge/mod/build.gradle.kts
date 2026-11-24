import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import net.minecheck.gradle.LaunchDescriptor
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withGroovyBuilder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

// The 1.4.7 server-side class overlay is rebuilt with ASM (see buildServerMcpClasses): remapping
// class and member names is not something a line of text processing can do safely.
buildscript {
  repositories { mavenCentral() }
  dependencies { classpath("org.ow2.asm:asm-commons:9.7") }
}

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
  // 1.4.7 predates published MCP mapping artefacts: the MCP conf ships inside Forge's own
  // `src.zip` (`forge/fml/conf`, with `joined.srg`, `methods.csv` and `fields.csv`), and
  // `forgeBuiltinMCP` reads that archive. The argument is Forge's own version, because the
  // coordinate it resolves is `<game>-<forge>:src@zip`.
  mappings { forgeBuiltinMCP("6.6.2.534") }
  // 6.6.2.534 is the 1.4.7 recommended build. This is the FG1 era: Forge publishes a merged
  // `universal.zip` and no userdev jar, so `loader` names the build without the game prefix.
  minecraftForge { loader("6.6.2.534") }
  mods { modImplementation {} }
  defaultRemapJar = false
}

// Forge on 1.12.2 is Java 8 and only Java 8, and it predates ModLauncher entirely: it loads mods
// through FML. The repo compiles at 17, so this line's bytecode is downgraded to class file 52
// (root build.gradle.kts, `minecheck.downgradeTo`) and the runs use a pinned JDK 8 build rather
// than whichever one happens to be installed. The line also has no Mixin at all — Mixin only
// reaches Forge with 1.15 — so its client hooks are native Forge events.
val runJavaVersion = providers.gradleProperty("minecheck.runJava").map(String::toInt).orElse(8)

// The runs launch through the toolchain, not through a hand-set `executable`: Gradle rejects a
// task that carries both, and unimined sets the launcher from `javaVersion`. 1.7.10 predates
// ModLauncher, so any Java 8 works here (the pinned build is for the ModLauncher-era lines).
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
  // The legacyfabric branch's natives artefact carries this host's architecture; the nightly one is
  // 32-bit x86 only, which fails to load on an aarch64 machine ("wrong ELF class: ELFCLASS32").
  lwjgl2Natives("org.lwjgl.lwjgl:lwjgl-platform:2.9.4+legacyfabric.8:natives-linux")
}

val lwjgl2NativeDir = layout.buildDirectory.dir("lwjgl2-natives")

val extractLwjgl2Natives by
  tasks.registering(Copy::class) {
    from(lwjgl2Natives.map { zipTree(it) })
    into(lwjgl2NativeDir)
    // A stale directory is worse than a missing one: the old architecture's libraries would stay
    // beside the new ones and the loader would pick whichever it found first.
    doFirst { lwjgl2NativeDir.get().asFile.deleteRecursively() }
    // LWJGL 2 asks the JVM for `liblwjgl64.so` and `libopenal64.so`; the fork publishes one file
    // per architecture under its own name, so this host's build is also copied under the names the
    // loader looks for.
    doLast {
      val arch =
        when (val host = System.getProperty("os.arch")) {
          "aarch64",
          "arm64" -> "aarch64"
          "x86_64",
          "amd64" -> "amd64"
          "arm" -> "armhf"
          "ppc64le" -> "ppc64el"
          "riscv64" -> "riscv64"
          else -> throw GradleException("Minecheck: no LWJGL 2 natives for $host")
        }
      val dir = lwjgl2NativeDir.get().asFile
      fun link(from: String, vararg to: String) {
        val source = File(dir, from)
        if (source.isFile) to.forEach { source.copyTo(File(dir, it), overwrite = true) }
      }
      link("liblwjgl-linux-$arch.so", "liblwjgl.so", "liblwjgl64.so")
      link("libopenal-$arch.so", "libopenal.so", "libopenal64.so")
    }
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

// --- Real 1.4.7 dedicated server classes ------------------------------------------------------
/**
 * FG1's dev jar is a client+server merge in which the client wins every class the two jars share,
 * so the server classes in it are the client's: `MinecraftServer` carries the integrated server's
 * crash GUI, and FML's SideTransformer refuses `GuiScreen` on the server side the moment one of
 * those classes is loaded. A dedicated server therefore cannot run off that jar, however much of
 * the launch is shared.
 *
 * <p>Forge 1.4.7's dedicated server is the Mojang server jar with the universal build merged in,
 * which is what `buildForgeServerJar` assembles. Its classes are obfuscated, while the mod and the
 * dev launch are in MCP names, so `buildServerMcpClasses` translates that jar's Minecraft classes
 * into MCP with the mappings Forge ships (`joined.srg`, `packages.csv`, `fields.csv`,
 * `methods.csv`) and the run puts the result ahead of the dev jar on the classpath. The launch
 * stays unimined's: same tweaker, same agent, same mod classpath.
 */
val forgeServerJar = layout.buildDirectory.file("minecheck/forge/server-forge.jar")

/** The Mojang 1.4.7 server download; the launcher object name is the file's SHA-1. */
val mojangServerUrl =
  "https://launcher.mojang.com/v1/objects/2f0ec8efddd2f2c674c77be9ddb370b727dec676/server.jar"
val mojangServerSha1 = "2f0ec8efddd2f2c674c77be9ddb370b727dec676"

val forgeUniversal by configurations.creating

dependencies { forgeUniversal("net.minecraftforge:forge:$mcVersion-6.6.2.534:universal@zip") }

val buildForgeServerJar by
  tasks.registering {
    group = "minecheck"
    description = "Assembles the 1.4.7 dedicated server jar (Mojang server + Forge universal)"
    // Source for buildServerMcpClasses, and the jar a real install is made from.
    outputs.file(forgeServerJar)
    inputs.files(forgeUniversal)
    doLast {
      val parts = layout.buildDirectory.dir("minecheck/forge/server-parts").get().asFile
      parts.mkdirs()
      val mojang = File(parts, "minecraft_server.$mcVersion.jar")
      if (!mojang.isFile) {
        val connection = URI(mojangServerUrl).toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 600_000
        val bytes = connection.inputStream.use { it.readBytes() }
        val actual =
          MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actual != mojangServerSha1) {
          throw GradleException("Minecheck: Mojang server jar checksum mismatch: got $actual")
        }
        mojang.writeBytes(bytes)
      }
      val staging = layout.buildDirectory.dir("minecheck/forge/server-staging").get().asFile
      staging.deleteRecursively()
      staging.mkdirs()
      copy {
        from(zipTree(mojang))
        into(staging)
      }
      copy {
        from(zipTree(forgeUniversal.singleFile))
        into(staging)
      }
      // FML's own access transformer widens this class (see widenFmlFirstPassAccess), too late for
      // the pass that reads it.
      val sanityCheck = File(staging, "b.class")
      if (sanityCheck.isFile) {
        sanityCheck.writeBytes(
          widenFmlFirstPassAccess(sanityCheck.readBytes(), setOf("<init>(La;)V"))
        )
      }
      val target = forgeServerJar.get().asFile
      target.delete()
      ant.withGroovyBuilder {
        "jar"(
          "destfile" to target,
          "manifest" to File(staging, "META-INF/MANIFEST.MF"),
          "basedir" to staging,
        )
      }
    }
  }

/** Forge's own MCP configuration for this line (`forge/fml/conf` inside the src archive). */
val forgeSrc by configurations.creating

dependencies { forgeSrc("net.minecraftforge:forge:$mcVersion-6.6.2.534:src@zip") }

val serverForgeMcpJar = layout.buildDirectory.file("minecheck/forge/server-forge-mcp.jar")

/**
 * The dedicated server's view of the Forge jar: the dev jar with its Minecraft classes replaced by
 * the real server's, translated into MCP names.
 *
 * <p>Nothing else on a 1.4.7 server is obfuscated: FML, Forge and the mod all speak MCP. Only the
 * Mojang jar (and the universal's patched copies of its classes) needs translating, so the overlay
 * carries Minecraft's classes and nothing else, and the dev jar keeps supplying FML and Forge.
 *
 * <p>The mappings are the ones MCP 7.26 shipped inside Forge's src archive, which is what unimined
 * builds this line's dev jar from: `joined.srg` for the class and member names, `packages.csv` for
 * the package every class belongs to (`joined.srg` still writes `net/minecraft/src/<name>`), and
 * `fields.csv` / `methods.csv` for the readable halves of the member names.
 */
val buildServerMcpClasses by
  tasks.registering {
    group = "minecheck"
    description = "Translates the dedicated server's Minecraft classes into MCP names"
    dependsOn(buildForgeServerJar)
    inputs.file(forgeServerJar)
    inputs.files(forgeSrc)
    outputs.file(serverForgeMcpJar)
    // The dev jar being copied is a cache artefact this task does not declare as an input, so it
    // follows whatever version that artefact is instead of reporting itself up to date.
    outputs.upToDateWhen { false }
    doLast {
      fun conf(name: String): List<String> =
        ZipFile(forgeSrc.singleFile).use { zip ->
          val entry =
            zip.getEntry("forge/fml/conf/$name")
              ?: throw GradleException(
                "Minecheck: $name is missing from ${forgeSrc.singleFile.name}"
              )
          zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readLines()
        }

      val packages = HashMap<String, String>()
      for (line in conf("packages.csv")) {
        val parts = line.trim().split(',')
        if (parts.size >= 2 && parts[0] != "class") packages[parts[0]] = parts[1]
      }
      fun mcp(internal: String): String {
        if (!internal.startsWith("net/minecraft/src/")) return internal
        val simple = internal.substringAfterLast('/')
        return (packages[simple] ?: "net/minecraft/src") + "/" + simple
      }
      fun readable(file: String): Map<String, String> =
        conf(file)
          .drop(1)
          .mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size >= 2) parts[0] to parts[1] else null
          }
          .toMap()
      val fieldNames = readable("fields.csv")
      val methodNames = readable("methods.csv")

      val classes = HashMap<String, String>()
      /** Keyed `owner/name`, the way `joined.srg` writes a field. */
      val fields = HashMap<String, String>()
      /**
       * Keyed `owner/name` plus the obfuscated descriptor, the way `joined.srg` writes a method.
       */
      val methods = HashMap<String, String>()
      // Forge's copy of MCP writes every line obfuscated-first:
      // CL: <obf> <mcp>
      // FD: <obfOwner>/<obfName> <mcpOwner>/<mcpName>
      // MD: <obfOwner>/<obfName> <obfDesc> <mcpOwner>/<mcpName> <mcpDesc>
      for (line in conf("joined.srg")) {
        val parts = line.trim().split(Regex("\\s+"))
        when (parts.firstOrNull()) {
          "CL:" -> if (parts.size >= 3) classes[parts[1]] = mcp(parts[2])
          "FD:" ->
            if (parts.size >= 3) {
              val named = parts[2].substringAfterLast('/')
              fields[parts[1]] = fieldNames[named] ?: named
            }
          "MD:" ->
            if (parts.size >= 5) {
              val named = parts[3].substringAfterLast('/')
              methods[parts[1] + parts[2]] = methodNames[named] ?: named
            }
        }
      }

      // Read the server's classes once: resolving a member reference needs the hierarchy, because
      // javac writes the receiver's static type (often a subclass) while the mapping names the
      // class that declares the member — `StatBasic.h()` is `StatBase`'s method.
      val serverClasses = LinkedHashMap<String, ByteArray>()
      ZipFile(forgeServerJar.get().asFile).use { jar ->
        for (entry in jar.entries()) {
          if (entry.isDirectory || !entry.name.endsWith(".class")) continue
          val internal = entry.name.removeSuffix(".class")
          if (!(classes[internal] ?: internal).startsWith("net/minecraft/")) continue
          serverClasses[internal] = jar.getInputStream(entry).use { it.readBytes() }
        }
      }
      // The hierarchy a member reference is resolved through: its superclass *and* its
      // interfaces. javac writes the static type it saw as the owner, and the declaration that
      // answers is often an interface's — `CommandBase.c()` is `ICommand`'s `getCommandName`.
      // Walking superclasses alone left those references obfuscated, which is a
      // `NoSuchMethodError` the first time one is invoked (it aborted `runCommand("stop")`).
      val parents = HashMap<String, List<String>>()
      for ((internal, bytes) in serverClasses) {
        val reader = ClassReader(bytes)
        val list = ArrayList<String>(1 + (reader.interfaces?.size ?: 0))
        reader.superName?.let { list.add(it) }
        reader.interfaces?.let { list.addAll(it) }
        parents[internal] = list
      }

      fun <T> up(owner: String, resolve: (String) -> T?): T? {
        val queue = ArrayDeque<String>()
        val seen = HashSet<String>()
        queue.addLast(owner)
        while (queue.isNotEmpty()) {
          val current = queue.removeFirst()
          if (!seen.add(current)) continue
          val found = resolve(current)
          if (found != null) return found
          parents[current]?.let { queue.addAll(it) }
        }
        return null
      }

      val remapper =
        object : Remapper() {
          override fun map(internalName: String): String = classes[internalName] ?: internalName

          override fun mapFieldName(owner: String, name: String, descriptor: String): String =
            up(owner) { fields["$it/$name"] } ?: name

          override fun mapMethodName(owner: String, name: String, descriptor: String): String =
            up(owner) { methods["$it/$name$descriptor"] } ?: name
        }

      val target = serverForgeMcpJar.get().asFile
      target.parentFile.mkdirs()
      target.delete()
      var translated = 0
      JarOutputStream(target.outputStream()).use { out ->
        // Everything but Minecraft comes from the dev jar: FML, Forge, the assets and the mod
        // metadata are already in MCP names, and they are the same on both sides. Its
        // `TweakClass` goes, for the reason `stripForgeTweakClass` drops it from the client copy.
        JarFile(forgeJarSource()).use { dev ->
          val manifest = Manifest(dev.manifest)
          manifest.mainAttributes.remove(Attributes.Name("TweakClass"))
          out.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
          manifest.write(out)
          out.closeEntry()
          for (entry in dev.entries()) {
            if (
              entry.isDirectory ||
                entry.name.startsWith("net/minecraft/") ||
                entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)
            ) {
              continue
            }
            out.putNextEntry(ZipEntry(entry.name))
            dev.getInputStream(entry).use { it.copyTo(out) }
            out.closeEntry()
          }
        }
        for ((internal, serverClass) in serverClasses) {
          // FML's access transformer widens this one class (see widenFmlFirstPassAccess), and it
          // has to happen while the member names are still the obfuscated ones.
          val bytes =
            if (internal == "b") widenFmlFirstPassAccess(serverClass, setOf("<init>(La;)V"))
            else serverClass
          val writer = ClassWriter(0)
          ClassReader(bytes).accept(ClassRemapper(writer, remapper), 0)
          val named = remapper.map(internal)
          // Forge's own patch, which the server jar does not carry either (see addInterface).
          val mapped =
            if (named in playerClasses) addInterface(writer.toByteArray(), playerInterface)
            else writer.toByteArray()
          out.putNextEntry(ZipEntry(named + ".class"))
          out.write(mapped)
          out.closeEntry()
          translated++
        }
      }
      if (translated == 0) {
        throw GradleException(
          "Minecheck: no server classes mapped into net/minecraft; mappings did not load"
        )
      }
      logger.lifecycle(
        "Minecheck: translated {} dedicated server class(es) into MCP names",
        translated,
      )
    }
  }

/**
 * FML 1.4.7's relauncher fetches its own libraries from files.minecraftforge.net, which no longer
 * serves them, and refuses to start with a file it cannot verify. The server's `lib/` is therefore
 * seeded: the two jars unimined already keeps for this line, plus the two whose published artefacts
 * match the checksums FML expects.
 */
/**
 * FML's `fml_at.cfg` widens the vanilla `CallableMinecraftVersion` before anything else runs:
 * ```
 * public b #CL:CallableMinecraftVersion
 * public b.<init>(La;)V
 * ```
 *
 * <p>The server's relauncher builds that class from `cpw.mods.fml.common.Loader` on its first pass,
 * and the two sit in different packages, so a package-private copy is an `IllegalAccessError`
 * before the relaunched classloader (the one that applies the access transformer) exists. Widening
 * the copy in the merged jar is the same edit FML would have made, just early enough to be legal.
 */
fun widenFmlFirstPassAccess(classFile: ByteArray, members: Set<String>): ByteArray {
  fun u1(at: Int) = classFile[at].toInt() and 0xff
  fun u2(at: Int) = (u1(at) shl 8) or u1(at + 1)
  fun u4(at: Int) = (u2(at) shl 16) or u2(at + 2)
  val widened = classFile.copyOf()
  fun putU2(at: Int, value: Int) {
    widened[at] = (value ushr 8).toByte()
    widened[at + 1] = value.toByte()
  }
  var pos = 8
  val poolCount = u2(pos)
  pos += 2
  val utf8 = arrayOfNulls<String>(poolCount)
  var entry = 1
  while (entry < poolCount) {
    when (val tag = u1(pos)) {
      1 -> {
        val length = u2(pos + 1)
        utf8[entry] = String(classFile, pos + 3, length, Charsets.UTF_8)
        pos += 3 + length
      }
      7,
      8,
      16,
      19,
      20 -> pos += 3
      15 -> pos += 4
      3,
      4,
      9,
      10,
      11,
      12,
      17,
      18 -> pos += 5
      5,
      6 -> {
        pos += 9
        entry++
      }
      else -> throw GradleException("Minecheck: unexpected constant pool tag $tag in b.class")
    }
    entry++
  }
  putU2(pos, u2(pos) or 0x0001)
  pos += 6
  pos += 2 + 2 * u2(pos)
  repeat(2) {
    val count = u2(pos)
    pos += 2
    repeat(count) {
      val at = pos
      val flags = u2(pos)
      val name = utf8[u2(pos + 2)]
      val desc = utf8[u2(pos + 4)]
      val attributes = u2(pos + 6)
      if ("$name$desc" in members) putU2(at, flags or 0x0001)
      pos += 8
      repeat(attributes) { pos += 6 + u4(pos + 2) }
    }
  }
  return widened
}

val fmlLibs by configurations.creating

dependencies {
  fmlLibs("com.google.guava:guava:12.0.1")
  fmlLibs("org.bouncycastle:bcprov-jdk15on:1.47")
}

val seedForgeServerLibs by
  tasks.registering {
    group = "minecheck"
    description = "Seeds the 1.4.7 server's lib/ with the jars FML's relauncher verifies"
    inputs.files(fmlLibs)
    outputs.upToDateWhen { false }
    doLast {
      val lib = file("run/server/lib")
      lib.mkdirs()
      file(".gradle/unimined/local/fmllibs").listFiles()?.forEach { jar ->
        if (jar.isFile && jar.extension == "jar") {
          jar.copyTo(File(lib, jar.name), overwrite = true)
          // The client relaunches off its own lib/, against the same checksum table.
          val client = file("run/client/lib/" + jar.name)
          if (client.isFile) jar.copyTo(client, overwrite = true)
        }
      }
      for (jar in fmlLibs.files) {
        // FML asks for this one by the name it published under in 2013.
        val name =
          if (jar.name.startsWith("bcprov-jdk15on-")) "bcprov-jdk15on-147.jar" else jar.name
        jar.copyTo(File(lib, name), overwrite = true)
      }
    }
  }

repositories {
  maven("https://maven.minecraftforge.net")
  // LWJGL 2's natives are published by Mojang, not on Central.
  maven("https://libraries.minecraft.net/") { content { includeGroup("org.lwjgl.lwjgl") } }
  // The LWJGL 2 build Legacy Fabric ships carries this host's architecture; Mojang's nightly
  // natives are 32-bit x86 only, which cannot load on an aarch64 machine.
  maven("https://maven.legacyfabric.net") { content { includeGroup("org.lwjgl.lwjgl") } }
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

// --- Forge jar manifest --------------------------------------------------------------------
/**
 * The merged Forge jar keeps the `TweakClass` attribute of the published distribution's manifest,
 * and FML adds every jar's declared tweak class to the launch on top of the one unimined passes. On
 * a dedicated server that builds FMLTweaker twice — FMLServerTweaker is a subclass, so its
 * constructor already installed the FML security manager — and the second install is refused with
 * "Cannot replace the FML security manager". The attribute is dropped from a copy of the jar, and
 * the runs use that copy in its place.
 */
val strippedForgeJar = layout.buildDirectory.file("minecheck/forge/forge-run.jar")

/**
 * The Forge jar whose manifest is copied. Taken from unimined's cache: task configuration order
 * decides whether a run task's classpath already holds the jar by the time this runs, and the cache
 * lookup keeps the copy independent of that order. The run classpaths are the fallback, for a cache
 * layout that moved.
 */
/**
 * True for this line's merged Forge jar. FG1 writes {@code …+fixForge+fixInners-forgeMCP-at.jar},
 * where the newer lines write {@code …-mcp-at.jar}, so the name is matched by the version it
 * carries and the suffix both layouts share rather than by the newer spelling alone.
 */
fun isForgeJarName(name: String): Boolean =
  name.endsWith("-at.jar") && name.contains("forge-$mcVersion")

fun forgeJarSource(): File {
  fileTree(gradle.gradleUserHomeDir.resolve("caches/unimined")) { include("**/*-at.jar") }
    .files
    .filter { isForgeJarName(it.name) }
    .maxByOrNull { it.lastModified() }
    ?.let {
      return it
    }
  tasks
    .matching { it.name == "runClient" || it.name == "runServer" }
    .filterIsInstance<JavaExec>()
    .forEach { run ->
      run.classpath.files
        .firstOrNull { isForgeJarName(it.name) }
        ?.let {
          return it
        }
    }
  throw GradleException("Minecheck: no Forge jar to copy the TweakClass out of")
}

/**
 * Swaps the Forge jar for the copy without `TweakClass`, so the launch keeps one Forge classpath
 * entry and one tweak class.
 */
fun useStrippedForgeJar(run: JavaExec) {
  val entries = run.classpath.files
  if (entries.none { isForgeJarName(it.name) }) return
  val stripped = strippedForgeJar.get().asFile
  run.classpath =
    files(entries.map { entry -> if (isForgeJarName(entry.name)) stripped else entry })
}

/**
 * Drops one method from a class file.
 *
 * <p>1.4.7's merged client jar carries the dedicated server's `MinecraftServer`, whose `main`
 * builds a `DedicatedServer` on a `ThreadDedicatedServer` — a class FML's `SideTransformer` refuses
 * on the client. The JVM verifies `main` when the class is linked, so the first integrated server
 * (any world creation) fails with `NoClassDefFoundError: ThreadDedicatedServer`. The client never
 * runs that entry point, so the copy the client launches drops it.
 */
fun dropMethod(classFile: ByteArray, name: String, descriptor: String): ByteArray {
  val writer = ClassWriter(0)
  ClassReader(classFile)
    .accept(
      object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitMethod(
          access: Int,
          methodName: String,
          methodDescriptor: String,
          signature: String?,
          exceptions: Array<String>?,
        ): MethodVisitor? =
          if (methodName == name && methodDescriptor == descriptor) null
          else super.visitMethod(access, methodName, methodDescriptor, signature, exceptions)
      },
      0,
    )
  return writer.toByteArray()
}

/**
 * `cpw.mods.fml.common.network.Player`: a marker interface in 1.4.7, so implementing it is an
 * interface-list edit and nothing else.
 */
val playerInterface = "cpw/mods/fml/common/network/Player"

/**
 * The classes FML's `NetworkRegistry` casts a packet's sender to `Player`: `playerLoggedIn` and the
 * REGISTER, UNREGISTER and payload branches of `handleCustomPacket` all `checkcast` it
 * unconditionally.
 */
val playerClasses =
  setOf(
    "net/minecraft/entity/player/EntityPlayerMP",
    "net/minecraft/client/entity/EntityClientPlayerMP",
  )

/**
 * Adds an interface to a class file.
 *
 * <p>Forge's 1.4.7 patch has both player classes implement `Player`, and the patch is not in the
 * merged jar this line launches from. FML does not guard the cast, so the first custom payload is a
 * `ClassCastException` that takes the connection down — and the first payload is FML's own channel
 * REGISTER traffic while the world loads, before Minecheck's own channel even matters. Without it
 * the server logs `EntityPlayerMP cannot be cast to ... Player`, then `The ForgeModLoader state
 * engine is invalid`, and never sees the client connect.
 */
fun addInterface(classFile: ByteArray, internalName: String): ByteArray {
  if (ClassReader(classFile).interfaces.contains(internalName)) return classFile
  val writer = ClassWriter(0)
  ClassReader(classFile)
    .accept(
      object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visit(
          version: Int,
          access: Int,
          name: String,
          signature: String?,
          superName: String?,
          interfaces: Array<String>,
        ) {
          super.visit(version, access, name, signature, superName, interfaces + internalName)
        }
      },
      0,
    )
  return writer.toByteArray()
}

val stripForgeTweakClass by
  tasks.registering {
    group = "minecheck"
    description = "Drops the Forge jar's TweakClass attribute so FML builds one tweaker"
    // Always repack: the jar is a copy of a cache artefact, and the task has to follow whatever
    // version that artefact is. A stale copy is a launch that silently loads the unpatched
    // manifest again.
    outputs.file(strippedForgeJar)
    outputs.upToDateWhen { false }
    doLast {
      val from = forgeJarSource()
      val target = strippedForgeJar.get().asFile
      target.parentFile.mkdirs()
      JarFile(from).use { jar ->
        val manifest = Manifest(jar.manifest)
        manifest.mainAttributes.remove(Attributes.Name("TweakClass"))
        ZipOutputStream(target.outputStream()).use { out ->
          out.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
          manifest.write(out)
          out.closeEntry()
          for (entry in jar.entries()) {
            if (entry.isDirectory || entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
              continue
            }
            out.putNextEntry(ZipEntry(entry.name))
            val patched =
              when (entry.name.removeSuffix(".class")) {
                "net/minecraft/server/MinecraftServer" ->
                  // The two dedicated-server entry points the merged jar keeps in this class. A
                  // client never runs either, but the verifier links both, and each names classes
                  // FML refuses on the client side.
                  dropMethod(
                    dropMethod(
                      jar.getInputStream(entry).use { it.readBytes() },
                      "main",
                      "([Ljava/lang/String;)V",
                    ),
                    "fmlReentry",
                    "(Lcpw/mods/fml/relauncher/ArgsWrapper;)V",
                  )
                in playerClasses ->
                  addInterface(jar.getInputStream(entry).use { it.readBytes() }, playerInterface)
                else -> null
              }
            if (patched != null) out.write(patched)
            else jar.getInputStream(entry).use { it.copyTo(out) }
            out.closeEntry()
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
  // Direct, so it is definitely on this module's runtime classpath for the game-layer patch below:
  // unimined's Forge dev launch gives Forge's module classloader this module's classes and not the
  // libraries they link against, so a library needed during mod construction has to be put on the
  // game layer by hand. Without that, every Forge dev client died at construction with
  // `NoClassDefFoundError: org/luaj/vm2/Varargs` (crash reports from 2026-08 onwards).
  implementation("org.luaj:luaj-jse:3.0.1")
  // 1.4.7's version metadata does not put gson on the compile classpath, while the flat-world
  // declaration is read with it (and JsonToNbt needs it at runtime). The game ships gson 2.2.2.
  implementation("com.google.code.gson:gson:2.2.2")
  implementation(files(commonOutput))
  implementation(files(platformApiOutput))
}

tasks.shadowJar {
  configurations = listOf(project.configurations.runtimeClasspath.get())
  // The deobfuscated view of the shipped jar: attached to the release for reading stack traces
  // and writing mixins, never loaded by a game.
  archiveClassifier.set("deobf")
  exclude("com/mojang/brigadier/**")
  // jvmdg's downgrade stubs are compile-time scaffolding for the Java 17 build: they are not
  // loadable classes, and FML's 1.4.7 mod scan aborts the whole jar when it cannot parse one
  // ("probably a corrupt zip").
  exclude("jvmdg/**")
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
 * True for a multi-release jar — one carrying classes under {@code META-INF/versions/} that only a
 * newer JVM reads.
 */
fun hasVersionedClasses(jar: File): Boolean {
  if (!jar.isFile) return false
  ZipFile(jar).use { zip ->
    return zip.entries().asSequence().any { it.name.startsWith("META-INF/versions/") }
  }
}

/**
 * A copy of {@code jar} without its {@code META-INF/versions/} tree.
 *
 * <p>1.4.7's FML parses every class on the launch classpath with ASM 4.0 and aborts the whole mod
 * load on the first class file newer than 51; gson, error_prone_annotations and the JetBrains
 * annotations all ship a {@code META-INF/versions/9/module-info.class} at 53. A Java 8 launch never
 * reads that directory, so the copy is exactly the jar this JVM uses.
 */
fun multiReleaseFreeCopy(jar: File): File {
  val target = layout.buildDirectory.file("minecheck/launch-jars/" + jar.name).get().asFile
  if (target.isFile && target.lastModified() >= jar.lastModified()) return target
  target.parentFile.mkdirs()
  val partial = File(target.parentFile, target.name + ".part")
  val manifest =
    ZipFile(jar).use { zip ->
      val entry = zip.getEntry("META-INF/MANIFEST.MF")
      if (entry == null) Manifest() else zip.getInputStream(entry).use { Manifest(it) }
    }
  JarOutputStream(partial.outputStream(), manifest).use { out ->
    ZipFile(jar).use { zip ->
      for (entry in zip.entries()) {
        val name = entry.name
        if (entry.isDirectory) continue
        if (name == "META-INF/MANIFEST.MF" || name.startsWith("META-INF/versions/")) continue
        out.putNextEntry(ZipEntry(name))
        zip.getInputStream(entry).use { it.copyTo(out) }
        out.closeEntry()
      }
    }
  }
  Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
  return target
}

/**
 * The classpath a 1.4.7 launch gets: the downgraded entries, minus what FML cannot parse.
 *
 * <p>Everything on this classpath is opened by FML's mod scan, which runs on ASM 4.0 and is fatal
 * on a class file above 51, so the mod itself is downgraded to 51 rather than the scanner patched.
 * The agent jar is only needed as {@code -javaagent}, where the JVM loads it without putting it on
 * the searched path, so it is dropped; the multi-release jars are replaced by copies without the
 * versioned {@code module-info} — see {@link #multiReleaseFreeCopy} — and the two compile-time
 * annotation jars are dropped outright.
 */
fun launchClasspath(entries: Set<File>): List<File> =
  entries.mapNotNull { entry ->
    val mapped = downgradedJarFor(entry)?.takeIf { it.isFile } ?: entry
    if (!mcVersion.startsWith("1.4.")) mapped
    else
      when {
        mapped.name.startsWith("jarmod-agent") -> null
        // Compile-time annotations only: nothing loads them at runtime, and at 52/53 they are
        // above the ceiling of the ASM 4.0 FML scans with.
        mapped.name.startsWith("error_prone_annotations") -> null
        mapped.name.startsWith("annotations-") -> null
        hasVersionedClasses(mapped) -> multiReleaseFreeCopy(mapped)
        else -> mapped
      }
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

/**
 * Points the dev agent's priority classpath at the downgraded directories. The agent loads the mod
 * from these paths before the classpath, and FML's relaunch classloader does not run the agent, so
 * a raw `classes/java/main` entry reaches Java 8 as class 61 and fails to load.
 */
fun rewritePriorityClasspath(key: String, value: String): String {
  if (key != "jma.priorityClasspath" || downgradeTo == null) return value
  return value.split(File.pathSeparator).joinToString(File.pathSeparator) { entry ->
    mapToDowngraded(File(entry))?.absolutePath ?: entry
  }
}

/** The same rewrite for the `-Djma.priorityClasspath=` argument unimined passes as a JVM arg. */
fun rewritePriorityClasspathArg(arg: String): String {
  val prefix = "-Djma.priorityClasspath="
  if (!arg.startsWith(prefix)) return arg
  return prefix + rewritePriorityClasspath("jma.priorityClasspath", arg.removePrefix(prefix))
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
    // Azul published no 8.0.292 build for linux/aarch64, so this platform takes the nearest later
    // Zulu 8. 1.4.7 predates ModLauncher, so any Java 8 runs its dev runs and its boot.
    ("linux" to "aarch64") to
      PinnedJdkArchive(
        "https://cdn.azul.com/zulu/bin/zulu8.86.0.25-ca-jdk8.0.452-linux_aarch64.tar.gz",
        "0abb44ad23b0bcc312a50bff9bf1cfbfd1a08caecd2bc6d7c7fa34471e4d207c",
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
      launchClasspath(task.classpath.files).joinToString(File.pathSeparator) { it.absolutePath },
    "javaExecutable" to
      (pinnedJdkJavaFile.takeIf { it.isFile }?.absolutePath
        ?: task.javaLauncher.get().executablePath.asFile.absolutePath),
    "mainClass" to task.mainClass.get(),
    "args" to join(task.args.orEmpty()),
    "jvmArgs" to join(task.jvmArgs.orEmpty().map { rewritePriorityClasspathArg(it) }),
    "systemProperties" to
      join(
        task.systemProperties.map { (k, v) ->
          val key = resolvePlaceholders(k, props)
          val value = resolvePlaceholders(v?.toString() ?: "", props)
          "$key=${rewritePriorityClasspath(key, value)}"
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
    stripForgeTweakClass,
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
    stripForgeTweakClass,
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
    .configureEach {
      dependsOn(packageDowngradedRuntime)
      // The descriptors are written from the run tasks without executing them, so the files those
      // launches put on the classpath have to be built by the descriptor task itself; otherwise a
      // clean build directory leaves the launch pointing at a server jar that does not exist yet.
      dependsOn(installPinnedJdk, buildServerMcpClasses, seedForgeServerLibs)
    }

  // The dev runs (and the descriptors the lifecycle tests launch from) get the same treatment:
  // their classpath and unimined's MOD_CLASSES both point at the downgraded directories.
  gradle.projectsEvaluated {
    // The lifecycle tests replay the launch descriptors themselves, so the files those launches
    // read — the merged server jar and the seeded lib/ — have to be built without going through the
    // dev helper tasks. A stale lib/ is fatal here: the relauncher deletes an asm-all whose SHA-1
    // does not match and then cannot obtain a replacement.
    rootProject.tasks
      .matching { it.name.startsWith("testForge") }
      .configureEach { dependsOn(seedForgeServerLibs) }
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
          // Unimined hands the mod to the agent through this argument rather than MOD_CLASSES, and
          // the agent loads from it ahead of the classpath: a raw `classes/java/main` entry reaches
          // the JVM as class file 61 and cannot be loaded at all.
          jvmArgs = jvmArgs.orEmpty().map { rewritePriorityClasspathArg(it) }
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
        // Timeouts and one retry, for the reason the Stylua download has them (root build): a bare
        // openStream() hangs until the socket gives up, and the build then reports a timeout that
        // names neither the archive nor its URL.
        var attempt = 0
        while (true) {
          attempt++
          try {
            val connection =
              URI(pinnedJdkArchive.url).toURL().openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 600_000
            connection.instanceFollowRedirects = true
            connection.inputStream.use { input -> archive.outputStream().use { input.copyTo(it) } }
            break
          } catch (e: Exception) {
            archive.delete()
            if (attempt >= 3) {
              throw GradleException(
                "Minecheck: could not download ${pinnedJdkArchive.url}: ${e.message}",
                e,
              )
            }
            logger.warn(
              "Minecheck: downloading ${pinnedJdkArchive.url} failed (attempt $attempt/3): ${e.message}"
            )
            Thread.sleep(2_000L * attempt)
          }
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
        // Read here rather than while configuring: unimined fills the run classpath later in the
        // build, and this action runs when the task is realised, by which point it is complete.
        if (name == "runServer") {
          dependsOn(installPinnedJdk, buildServerMcpClasses, seedForgeServerLibs)
          // The Forge jar a server sees carries its own Minecraft classes, so it takes the place
          // of the dev jar's: the launch keeps one classpath entry and no duplicate classes.
          // The jar-mod agent goes with it: its merge substitutes the dev jar's (client-side)
          // Minecraft classes for classes already in the overlay — the client's `DedicatedServer`
          // among them, which cannot run without `GuiScreen`. The overlay is the merge the server
          // needs, so the agent and its argument are dropped.
          classpath =
            files(
              classpath.files
                .filterNot { it.name.contains("jarmod-agent") }
                .map { entry ->
                  if (isForgeJarName(entry.name)) serverForgeMcpJar.get().asFile else entry
                }
            )
          jvmArgs = jvmArgs.orEmpty().filterNot { it.contains("jarmod-agent") }
        } else {
          dependsOn(installPinnedJdk, stripForgeTweakClass)
          // The stripped copy takes the place of the jar it was made from, so the launch keeps one
          // Forge classpath entry and one tweak class.
          useStrippedForgeJar(this)
        }
      }
    }
}
