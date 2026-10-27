package net.minecheck.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the file the Gradle plugin's whole verdict rests on.
 *
 * <p>The plugin reads this JSON with its own independent mirror ({@code RunResultDto} in the plugin
 * module, which deliberately does not depend on this one), so the field <em>names</em> and shapes
 * asserted here are the CI contract, not an implementation detail. The write is best-effort by
 * design: it must never throw into the game thread.
 */
class RunResultWriterTest {

  @TempDir Path tempDir;

  private static RunResult passing(String script, ExpectRecord... expects) {
    return new RunResult(script, true, false, null, List.of(expects));
  }

  @Test
  void writesTheVerdictFieldsTheGradlePluginReads() throws Exception {
    Path file = tempDir.resolve("config/minecheck/run-result.json");
    // The nested directory does not exist: the writer must create it.
    RunResultWriter.write(
        file,
        passing(
            "packet-client.lua",
            new ExpectRecord("joined the dedicated server", true),
            new ExpectRecord("punched the vex", false)));

    JsonObject json =
        JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

    assertEquals("packet-client.lua", json.get("script").getAsString());
    assertTrue(json.get("finished").getAsBoolean());
    assertFalse(json.get("failed").getAsBoolean());
    assertFalse(
        json.has("failureMessage"), "a passing run carries no failure message (Gson omits nulls)");

    JsonArray expects = json.getAsJsonArray("expects");
    assertEquals(2, expects.size(), "every recorded expect is reported, in order");
    assertEquals(
        "joined the dedicated server", expects.get(0).getAsJsonObject().get("name").getAsString());
    assertTrue(expects.get(0).getAsJsonObject().get("ok").getAsBoolean());
    assertEquals("punched the vex", expects.get(1).getAsJsonObject().get("name").getAsString());
    assertFalse(
        expects.get(1).getAsJsonObject().get("ok").getAsBoolean(),
        "a failed expect is reported, not dropped — the plugin fails the task on passed != total");
  }

  @Test
  void aFailedRunKeepsItsMessage() throws Exception {
    Path file = tempDir.resolve("run-result.json");
    RunResultWriter.write(
        file,
        new RunResult(
            "fail-path-client.lua",
            false,
            true,
            "minecheck.fail: expected the run to fail",
            List.of(new ExpectRecord("joined the dedicated server", true))));

    JsonObject json =
        JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

    assertFalse(json.get("finished").getAsBoolean());
    assertTrue(json.get("failed").getAsBoolean());
    assertEquals(
        "minecheck.fail: expected the run to fail", json.get("failureMessage").getAsString());
  }

  @Test
  void roundTripsBackIntoTheModsOwnRecord() throws Exception {
    Path file = tempDir.resolve("run-result.json");
    RunResult written =
        new RunResult(
            "script.lua",
            true,
            false,
            null,
            List.of(new ExpectRecord("a", true), new ExpectRecord("b", false)));

    RunResultWriter.write(file, written);
    RunResult read = new Gson().fromJson(Files.readString(file), RunResult.class);

    assertEquals(written, read, "the writer and the reader agree on every field name");
  }

  @Test
  void passedAndTotalCountTheExpects() {
    RunResult mixed =
        new RunResult(
            "s.lua",
            true,
            false,
            null,
            List.of(
                new ExpectRecord("a", true),
                new ExpectRecord("b", false),
                new ExpectRecord("c", true)));
    assertEquals(2, mixed.passed());
    assertEquals(3, mixed.total());

    RunResult empty = passing("s.lua");
    assertEquals(0, empty.passed());
    assertEquals(0, empty.total(), "no expects is 0/0, which the plugin treats as passed");
  }

  @Test
  void aWriteFailureIsSwallowedRatherThanThrown() {
    // A file where the config directory should be: createDirectories throws, and the writer's
    // contract is that a run must not die over the CI handshake.
    Path blocker = tempDir.resolve("blocked");
    Path file = blocker.resolve("run-result.json");

    try {
      Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
      RunResultWriter.write(file, passing("s.lua"));
      assertFalse(Files.exists(file), "nothing can have been written through a file");
      assertTrue(Files.isRegularFile(blocker), "the blocking file is untouched");
    } catch (Exception e) {
      throw new AssertionError("a failed write must be swallowed, not propagate", e);
    }
  }

  @Test
  void anEmptyExpectListSerializesAsAnEmptyArray() throws Exception {
    Path file = tempDir.resolve("run-result.json");
    RunResultWriter.write(file, passing("s.lua"));

    JsonObject json =
        JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

    assertTrue(json.has("expects"));
    assertEquals(0, json.getAsJsonArray("expects").size());
    assertNull(json.get("failureMessage"));
  }

  // ------------------------------------------------- expect names are arbitrary text

  /**
   * A check name is free text a script author writes, so it can contain whatever a label can. The
   * JSON file is the CI contract: a quote, a backslash, a newline or a non-ASCII byte must not be
   * able to break it, and the plugin must read back what the script wrote.
   */
  @Test
  void aNameWithQuotesBackslashesNewlinesAndUnicodeSurvivesJsonIntact() throws Exception {
    Path file = tempDir.resolve("run-result.json");
    String name = "a \"double quote\", a \\, a \n newline, a \t tab, unicode é and ✓";
    RunResultWriter.write(file, passing("s.lua", new ExpectRecord(name, true)));

    // Parsing fails if the name broke the JSON; the plugin parses this exact file.
    JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));

    RunResult read = new Gson().fromJson(Files.readString(file), RunResult.class);
    assertEquals(name, read.expects().get(0).name(), "the name comes back byte-for-byte");
  }

  /** Control characters are escaped by Gson, never dropped or turned into something else. */
  @Test
  void aNameWithControlCharactersIsEscapedAndRoundTrips() throws Exception {
    Path file = tempDir.resolve("run-result.json");
    String name = "before\u0001after\u0000end";
    RunResultWriter.write(file, passing("s.lua", new ExpectRecord(name, true)));

    String json = Files.readString(file, StandardCharsets.UTF_8);
    JsonParser.parseString(json); // valid JSON, i.e. the control chars were escaped

    RunResult read = new Gson().fromJson(json, RunResult.class);
    assertEquals(name, read.expects().get(0).name());
  }

  /**
   * A result with no {@code expects} at all (a hand-written or older file) writes as an empty array
   * rather than crashing the writer or emitting an invalid file — the record normalizes it, so
   * {@code passed() == total() == 0}, which the plugin treats as passed (see {@code RunVerdict}).
   */
  @Test
  void aNullExpectListSerializesAsAnEmptyArray() throws Exception {
    Path file = tempDir.resolve("run-result.json");
    RunResultWriter.write(file, new RunResult("s.lua", true, false, null, null));

    JsonObject json =
        JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

    assertTrue(json.has("expects"), "the field is present");
    assertEquals(0, json.getAsJsonArray("expects").size());
  }
}
