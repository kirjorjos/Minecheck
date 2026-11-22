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

// No Fabric here: Fabric's loader starts at 1.14. Legacy Fabric does not cover 1.11 either — it
// publishes no 1.11 intermediary or yarn builds, so its 1.11 support starts at 1.11.2 — which is
// why this line is Forge only, like 1.9. The Forge here predates Mixin, so its hooks are native FML
// events.
include(":forge-mod")

project(":forge-mod").projectDir = file("src/forge/mod")

includeBuild("src/plugin")
