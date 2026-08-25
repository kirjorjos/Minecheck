plugins {
  `java-gradle-plugin`
  id("com.diffplug.spotless") version "8.10.2"
}

group = "net.minecheck"

version = "0.1.0"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

repositories { mavenCentral() }

dependencies {
  api("com.google.code.gson:gson:2.11.0")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

gradlePlugin {
  plugins {
    create("minecheckTest") {
      id = "net.minecheck"
      implementationClass = "net.minecheck.gradle.MinecheckLifecyclePlugin"
      displayName = "Minecheck lifecycle test"
      description =
        "Runs the headless Minecraft lifecycle test (creating/joining worlds and servers via Lua scripts) as part of the Gradle build"
    }
  }
}

spotless {
  java {
    googleJavaFormat("1.28.0")
    removeUnusedImports()
    importOrder()
    formatAnnotations()
    replaceRegex(
      "collapseEmptyCatch",
      "(catch\\s*\\([^)]*\\)[ \\t]*)\\{[ \\t]*\\r?\\n[ \\t]*\\}",
      "$1{ }",
    )
    trimTrailingWhitespace()
    endWithNewline()
  }
  kotlinGradle { ktfmt("0.54").googleStyle() }
}

tasks.named<Test>("test") { useJUnitPlatform() }
