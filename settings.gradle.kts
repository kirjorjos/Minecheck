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

include(":quilt-mod")

project(":quilt-mod").projectDir = file("src/quilt/mod")

include(":forge-mod")

project(":forge-mod").projectDir = file("src/forge/mod")

includeBuild("src/plugin")
