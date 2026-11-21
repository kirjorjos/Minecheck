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

// No Fabric here: Fabric's loader starts at 1.14, and Legacy Fabric's first supported game version
// is 1.9.4 — its loader metadata for 1.9 is empty — so this line is Forge only. Its Forge predates
// Mixin, so its hooks are native FML events.
include(":forge-mod")

project(":forge-mod").projectDir = file("src/forge/mod")

includeBuild("src/plugin")
