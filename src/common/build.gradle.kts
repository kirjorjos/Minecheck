plugins { java }

repositories { mavenCentral() }

dependencies {
  implementation(project(":platform-api"))
  implementation("com.google.code.gson:gson:2.11.0")
  implementation("org.luaj:luaj-jse:3.0.1")

  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.named<Test>("test") { useJUnitPlatform() }
