package net.minecheck.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the config file every run reads before it does anything.
 *
 * <p>The interesting cases are the ones a hand-edited file hits: a missing field, an explicit JSON
 * {@code null}, a half-written file. The defaults matter because {@code script} empty means the run
 * does nothing at all, which surfaces elsewhere as the confusing "wrote no run-result.json".
 */
class JsonConfigManagerTest {

  @TempDir Path tempDir;

  private JsonConfigManager manager() {
    return new JsonConfigManager(tempDir);
  }

  private void writeConfig(String json) throws IOException {
    Path file = manager().configFile();
    Files.createDirectories(file.getParent());
    Files.writeString(file, json, StandardCharsets.UTF_8);
  }

  @Test
  void pathsLiveUnderTheMinecheckSubdirectoryOfTheLoaderConfigDir() {
    JsonConfigManager m = manager();

    assertEquals(tempDir.resolve("minecheck"), m.configDirectory());
    assertEquals(tempDir.resolve("minecheck/config.json"), m.configFile());
    assertEquals(tempDir.resolve("minecheck/scripts"), m.scriptDirectory());
    assertEquals(tempDir.resolve("minecheck/run-result.json"), m.resultFile());
  }

  @Test
  void missingFileIsCreatedWithTheDefaults() throws Exception {
    JsonConfigManager m = manager();
    assertFalse(Files.exists(m.configFile()), "precondition: no config yet");

    ConfigData config = m.load();

    assertEquals(ConfigData.defaults(), config);
    assertTrue(Files.exists(m.configFile()), "load() must leave a template config behind");
    // ...and the file it wrote is itself loadable, i.e. the template round-trips.
    assertEquals(ConfigData.defaults(), m.load());
  }

  @Test
  void saveThenLoadRoundTripsEveryField() throws Exception {
    JsonConfigManager m = manager();

    m.save(new ConfigData("phase.lua", "coordinator.lua", "continue", 90));

    ConfigData loaded = m.load();
    assertEquals("phase.lua", loaded.script());
    assertEquals("coordinator.lua", loaded.coordinator());
    assertEquals("continue", loaded.failMode());
    assertEquals(90, loaded.defaultTimeout());
  }

  @Test
  void partialJsonFillsInTheDocumentedDefaults() throws Exception {
    writeConfig("{\"script\": \"only.lua\"}");

    ConfigData config = manager().load();

    assertEquals("only.lua", config.script());
    assertNull(config.coordinator(), "an absent coordinator stays null, not a string");
    assertEquals("exit", config.failMode());
    assertEquals(30, config.defaultTimeout());
    assertFalse(config.isCoordinator());
  }

  @Test
  void explicitJsonNullsAlsoFallBackToTheDefaults() throws Exception {
    writeConfig(
        "{\"script\": null, \"coordinator\": null, \"failMode\": null, \"defaultTimeout\": null}");

    ConfigData config = manager().load();

    assertEquals("", config.script());
    assertNull(config.coordinator());
    assertEquals("exit", config.failMode());
    assertEquals(30, config.defaultTimeout());
  }

  @Test
  void coordinatorWinsOverScriptOnlyWhenItIsSet() throws Exception {
    writeConfig("{\"script\": \"phase.lua\", \"coordinator\": \"coordinator.lua\"}");
    ConfigData both = manager().load();
    assertTrue(both.isCoordinator());
    assertEquals("coordinator.lua", both.entryScript());

    writeConfig("{\"script\": \"phase.lua\", \"coordinator\": \"   \"}");
    ConfigData blank = manager().load();
    assertFalse(blank.isCoordinator(), "a blank coordinator is not a coordinator");
    assertEquals("phase.lua", blank.entryScript(), "a blank coordinator must fall back to script");

    writeConfig("{\"script\": \"phase.lua\", \"coordinator\": null}");
    assertEquals("phase.lua", manager().load().entryScript());
  }

  @Test
  void exitOnFailIsOptOutAndCaseInsensitive() throws Exception {
    writeConfig("{\"failMode\": \"exit\"}");
    assertTrue(manager().load().exitOnFail());

    writeConfig("{\"failMode\": \"continue\"}");
    assertFalse(manager().load().exitOnFail());

    writeConfig("{\"failMode\": \"CONTINUE\"}");
    assertFalse(manager().load().exitOnFail(), "failMode is matched case-insensitively");

    writeConfig("{}");
    assertTrue(manager().load().exitOnFail(), "the default is to stop on failure");
  }

  @Test
  void aHalfWrittenFileIsAnErrorNamingTheFileAndIsLeftInPlace() throws Exception {
    writeConfig("{\"script\": \"phase.lua\"");
    JsonConfigManager m = manager();

    IOException thrown = assertThrows(IOException.class, m::load);

    assertNotNull(thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains(m.configFile().toString()),
        "the error must name the file to fix, got: " + thrown.getMessage());
    assertEquals(
        "{\"script\": \"phase.lua\"",
        Files.readString(m.configFile()),
        "a broken config is left alone so it can be inspected, not overwritten");
  }

  @Test
  void jsonThatIsNotAnObjectIsRejectedToo() throws Exception {
    for (String json : new String[] {"[1, 2, 3]", "\"just a string\"", "", "null"}) {
      writeConfig(json);
      assertThrows(
          IOException.class, () -> manager().load(), "not a JSON object must be rejected: " + json);
    }
  }

  @Test
  void anEmptyObjectWithUnknownKeysUsesTheDefaults() throws Exception {
    // Unknown keys are ignored rather than rejected: a config written by a newer version, or with a
    // typo'd extra key, must not stop the run.
    writeConfig("{\"somethingElse\": true}");

    assertEquals(ConfigData.defaults(), manager().load());
  }

  @Test
  void wrongTypesAreReportedByFieldName() throws Exception {
    writeConfig("{\"defaultTimeout\": \"sixty\"}");
    IOException timeout = assertThrows(IOException.class, () -> manager().load());
    assertTrue(timeout.getMessage().contains("defaultTimeout"), "got: " + timeout.getMessage());

    writeConfig("{\"script\": 7}");
    IOException script = assertThrows(IOException.class, () -> manager().load());
    assertTrue(script.getMessage().contains("script"), "got: " + script.getMessage());
  }

  // ------------------------------------------------- a value the field cannot hold

  /**
   * A number that does not fit was silently <em>mangled</em> rather than rejected: {@code
   * 2147483648} (2^31) came back as {@code -2147483648}, i.e. a {@code defaultTimeout} that makes
   * every wait expire instantly, and {@code 9223372036854775807} as {@code -1}. A config file is a
   * human editing text, so "your number did not fit" has to be said out loud — the alternative is a
   * run whose waits all fail for no visible reason.
   */
  @Test
  void aTimeoutThatDoesNotFitIsReportedRatherThanWrapped() throws Exception {
    for (String bad : new String[] {"2147483648", "-2147483649", "9223372036854775807", "1e30"}) {
      JsonConfigManager m = manager();
      writeConfig("{\"defaultTimeout\": " + bad + "}");

      IOException thrown = assertThrows(IOException.class, m::load, "must reject: " + bad);

      assertTrue(
          thrown.getMessage().contains("defaultTimeout"),
          "the error must name the field, got: " + thrown.getMessage());
      assertTrue(
          thrown.getMessage().contains(bad),
          "the error must quote the value given, got: " + thrown.getMessage());
      assertEquals(
          "{\"defaultTimeout\": " + bad + "}",
          Files.readString(m.configFile()),
          "the file is left alone so it can be fixed");
    }
  }

  /**
   * A fractional timeout was truncated: {@code 0.5} — half a second — became {@code 0}, which is
   * not a shorter wait but <em>no</em> wait, and {@code 3.9} became {@code 3}. Rejected instead,
   * since neither is what was written.
   */
  @Test
  void aFractionalTimeoutIsReportedRatherThanTruncated() throws Exception {
    for (String bad : new String[] {"0.5", "3.9", "-0.5"}) {
      JsonConfigManager m = manager();
      writeConfig("{\"defaultTimeout\": " + bad + "}");

      IOException thrown = assertThrows(IOException.class, m::load, "must reject: " + bad);
      assertTrue(
          thrown.getMessage().contains("whole number"),
          "the error should say why, got: " + thrown.getMessage());
      assertTrue(thrown.getMessage().contains(bad), "got: " + thrown.getMessage());
    }
  }

  /**
   * The boundaries themselves are fine, and a whole number written with a decimal point is still a
   * whole number. {@code 0} and a negative are accepted deliberately: both are representable, and
   * both mean the same coherent thing (every wait expires at once) rather than being mangled.
   */
  @Test
  void representableTimeoutsAreAcceptedExactly() throws Exception {
    int[][] cases = {
      {2147483647, 0},
      {-2147483648, 0},
      {0, 0},
      {-5, 0},
    };
    for (int[] c : cases) {
      writeConfig("{\"defaultTimeout\": " + c[0] + "}");
      assertEquals(c[0], manager().load().defaultTimeout(), "must be kept as given: " + c[0]);
    }

    writeConfig("{\"defaultTimeout\": 3.0}");
    assertEquals(3, manager().load().defaultTimeout(), "3.0 is a whole number");

    writeConfig("{\"defaultTimeout\": 1e9}");
    assertEquals(1_000_000_000, manager().load().defaultTimeout());
  }
}
