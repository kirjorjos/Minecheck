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

// Forge on 1.7.10 is the FG2 era: `forge-1.7.10-…-userdev.jar` is published and unimined 1.4.1
// carries the FG2 transformer beside its FG3 one, so this module builds like the other lines. The
// line's Forge predates Mixin, so its hooks are native FML events, as on 1.12.2.
include(":forge-mod")

project(":forge-mod").projectDir = file("src/forge/mod")

// No Fabric module here either: Fabric's loader starts at 1.14. Legacy Fabric is the intermediary
// family that covers this line, so it gets a loader module of its own rather than sharing the
// fabric one.
include(":legacyfabric-mod")

project(":legacyfabric-mod").projectDir = file("src/legacyfabric/mod")

includeBuild("src/plugin")
