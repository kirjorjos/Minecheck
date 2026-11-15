package net.minecheck.gradle;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the handoff baseline comparison.
 *
 * <p>These are the shapes a chunk-arrival regression takes in the result files: a key that was
 * never observed (the client stopped before reading the surface) and a key that was observed with
 * the wrong value (the marker landed somewhere else). Both must name the observation rather than
 * let a later assertion speak for them.
 */
class HandoffBaselineTest {

  @TempDir Path tempDir;

  private Path baseline(Map<String, String> values) throws IOException {
    Path file = tempDir.resolve("handoff.json");
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
    }
    json.append('}');
    Files.writeString(file, json.toString());
    return file;
  }

  private static RunResultDto result(Map<String, String> observations) {
    return new RunResultDto("world-handoff-client.lua", true, false, null, List.of(), observations);
  }

  @Test
  void matchingObservationsPass() throws IOException {
    Path baseline = baseline(Map.of("surfaceY", "-60", "markerY", "100"));
    assertNull(
        HandoffBaseline.mismatch(
            baseline, List.of(result(Map.of("surfaceY", "-60", "markerY", "100")))));
  }

  @Test
  void aKeySplitAcrossTheTwoHandoffProcessesStillMatches() throws IOException {
    Path baseline = baseline(Map.of("surfaceY", "-60", "markerChunkLoaded", "true"));
    assertNull(
        HandoffBaseline.mismatch(
            baseline,
            List.of(
                result(Map.of("surfaceY", "-60")), result(Map.of("markerChunkLoaded", "true")))));
  }

  @Test
  void aMissingObservationNamesTheChunkNotALaterAssertion() throws IOException {
    Path baseline = baseline(Map.of("surfaceY", "-60"));
    String mismatch = HandoffBaseline.mismatch(baseline, List.of(result(Map.of())));
    assertTrue(mismatch != null && mismatch.contains("never reported surfaceY"), mismatch);
  }

  @Test
  void aWrongValueReportsBothNumbers() throws IOException {
    Path baseline = baseline(Map.of("markerY", "100"));
    String mismatch = HandoffBaseline.mismatch(baseline, List.of(result(Map.of("markerY", "164"))));
    assertTrue(mismatch != null && mismatch.contains("expected 100, observed 164"), mismatch);
  }
}
