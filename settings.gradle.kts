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

// No Fabric here: Fabric's loader starts at 1.14. Legacy Fabric's first supported game version is
// 1.9.4, so this is the first line in the family that carries it; its module is the intermediate
// mapping family's loader, not a reuse of `src/fabric`, whose platform classes are 1.14+ API. The
// Forge here predates Mixin, so its hooks are native FML events.
include(":forge-mod")

project(":forge-mod").projectDir = file("src/forge/mod")

include(":legacyfabric-mod")

project(":legacyfabric-mod").projectDir = file("src/legacyfabric/mod")

includeBuild("src/plugin")
