plugins { java }

repositories { mavenCentral() }

dependencies {
  implementation(project(":platform-api"))
  implementation("com.google.code.gson:gson:2.11.0")
  implementation("org.luaj:luaj-jse:3.0.1")

  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.named<Test>("test") {
  useJUnitPlatform()
  // Some tests run the suite's own Lua scripts (`tests/scripts`) rather than a copy of the logic,
  // so a change to what the suite asserts cannot leave a test asserting the old behaviour.
  systemProperty(
    "minecheck.scriptsDir",
    layout.projectDirectory.dir("../../tests/scripts").asFile.absolutePath,
  )
  // The script is read at run time, so without this the test task stays up-to-date after the very
  // change the guard exists to catch.
  inputs.file(layout.projectDirectory.file("../../tests/scripts/beacon-payment.lua"))
}
