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

include(":fabric-mod")

project(":fabric-mod").projectDir = file("src/fabric/mod")

// No Quilt module on 1.19.3 yet: every quilted-fabric-api build published for this version (5.0.0
// alpha.1 through beta.9) fails unimined's intermediary->yarn remap of
// `fabric-lifecycle-events-v1`, so the module cannot configure. Restore it here once a QFAPI build
// remaps, and add the `quilt` task registrations back in the root build file.

include(":forge-mod")

project(":forge-mod").projectDir = file("src/forge/mod")

includeBuild("src/plugin")
