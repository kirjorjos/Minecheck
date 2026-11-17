package net.minecheck.common.result;

import java.util.List;

/**
 * The machine-readable outcome of a script run, serialized to {@code
 * config/minecheck/run-result.json} when the script finishes.
 *
 * <p>This is the stable handshake between the game (which produces it) and the Gradle plugin / CI
 * (which consumes it): the plugin treats the run as passed iff {@code finished == true && failed ==
 * false}. {@code expects} lets a runner report each named check even when the script itself
 * branched and kept going rather than hard-failing.
 *
 * @param script the script file name that ran
 * @param finished whether the script ran to completion
 * @param failed whether the run failed (script error, minecheck.fail, assert)
 * @param failureMessage human message when {@code failed}; else null
 * @param expects one entry per {@code minecheck.expect(...)} call, in order
 * @param observations named values recorded with {@code minecheck.observe}, or {@code null} when
 *     the run recorded none (Gson then omits the key, so a run that reports nothing extra writes
 *     exactly the file older runners wrote)
 */
public record RunResult(
    String script,
    boolean finished,
    boolean failed,
    String failureMessage,
    List<ExpectRecord> expects,
    java.util.Map<String, String> observations) {

  public RunResult {
    // Hand-written and older result files may omit `expects`; that is 0/0, not a crash. The
    // plugin's
    // own mirror (RunResultDto) already guards the same way — this record must not be stricter than
    // the file it is deserialized from.
    expects = expects == null ? List.of() : expects;
    observations =
        observations == null || observations.isEmpty() ? null : java.util.Map.copyOf(observations);
  }

  /** The shape every caller used before observations existed. */
  public RunResult(
      String script,
      boolean finished,
      boolean failed,
      String failureMessage,
      List<ExpectRecord> expects) {
    this(script, finished, failed, failureMessage, expects, null);
  }

  public long passed() {
    return expects.stream().filter(ExpectRecord::ok).count();
  }

  public long total() {
    return expects.size();
  }
}
