package net.minecheck.gradle;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The handoff suite's committed baseline: the observations a run of it must report.
 *
 * <p>The handoff has its own baseline because it measures things no assertion of its own names: the
 * surface the client generated, the height it placed the marker at, and whether the marker's chunk
 * had actually arrived on the side that looked. A regression there used to surface as whatever the
 * <em>next</em> assertion happened to say ("marker block present" false, or a block read of {@code
 * nil}); comparing the observations themselves says "the handoff never reported surfaceY" and
 * points at the chunk.
 *
 * <p>Deliberately a plain class rather than logic inside the task: a Gradle task needs a live
 * project and two JVM launches to run, so the comparison has to be reachable from a unit test.
 */
public final class HandoffBaseline {

  private static final Gson GSON = new Gson();

  private HandoffBaseline() {}

  /**
   * Why the run does not match the baseline, or {@code null} when it does.
   *
   * <p>Every key in the baseline must be observed by at least one of the results (the handoff is
   * two processes, so a key may come from either), and its value must be the baseline's. A missing
   * key is a failure, not a skip: that is exactly the shape a chunk that never arrived takes.
   */
  public static String mismatch(Path baselineFile, List<RunResultDto> results) {
    Map<String, String> expected;
    try {
      expected =
          GSON.fromJson(
              Files.readString(baselineFile), new TypeToken<Map<String, String>>() {}.getType());
    } catch (IOException | RuntimeException e) {
      return "could not read the baseline " + baselineFile + ": " + e.getMessage();
    }
    if (expected == null || expected.isEmpty()) {
      return "the baseline " + baselineFile + " declares no observations";
    }
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      String observed = observed(results, entry.getKey());
      if (observed == null) {
        return "the handoff never reported "
            + entry.getKey()
            + " — the chunk did not arrive, or the script stopped before observing it";
      }
      if (!entry.getValue().equals(observed)) {
        return entry.getKey() + ": expected " + entry.getValue() + ", observed " + observed;
      }
    }
    return null;
  }

  private static String observed(List<RunResultDto> results, String key) {
    for (RunResultDto result : results) {
      if (result == null || result.observations() == null) {
        continue;
      }
      String value = result.observations().get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }
}
