pluginManagement {
  repositories {
    maven("https://maven.wagyourtail.xyz/releases")
    maven("https://maven.wagyourtail.xyz/snapshots")
    mavenCentral()
    gradlePluginPortal { content { excludeGroup("org.apache.logging.log4j") } }
  }
}

plugins {
  id("xyz.wagyourtail.unimined") version "1.4.1" apply false
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "Minecheck"

include(":common")

project(":common").projectDir = file("src/common")

include(":platform-api")

project(":platform-api").projectDir = file("src/platform-api")

// No Forge on this line: 1.7.10 predates the userdev jar that unimined is built around (there is no
// `forge-1.7.10-…-userdev.jar` to download, and the era's toolchain was ForgeGradle 1.2, which
// needs
// a Gradle this repo does not run) and 1.7.10's Forge predates Mixin entirely. Legacy Fabric covers
// 1.7.10 with an intermediary and a yarn fork, so it is the one loader module here.

// No Fabric module here either: Fabric's loader starts at 1.14. Legacy Fabric is the intermediary
// family that covers this line, so it gets a loader module of its own rather than sharing the
// fabric one.
include(":legacyfabric-mod")

project(":legacyfabric-mod").projectDir = file("src/legacyfabric/mod")

includeBuild("src/plugin")
