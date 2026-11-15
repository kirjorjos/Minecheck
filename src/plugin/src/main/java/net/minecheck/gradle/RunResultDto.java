package net.minecheck.gradle;

import java.util.List;
import java.util.Map;

/**
 * Plugin-local mirror of the mod's {@code run-result.json} contract.
 *
 * <p>Deliberately independent from the mod's own {@code common} module: the plugin must not depend
 * on the mod's game classes, so this small record is the only shape the plugin knows about the
 * verdict.
 *
 * @param script the Lua script file name that ran
 * @param finished whether the script ran to completion
 * @param failed whether the run recorded a failure
 * @param failureMessage human message when failed
 * @param expects per-{@code minecheck.expect} outcomes
 */
public record RunResultDto(
    String script,
    boolean finished,
    boolean failed,
    String failureMessage,
    List<ExpectDto> expects,
    Map<String, String> observations) {

  /** The shape every caller used before observations existed. */
  public RunResultDto(
      String script,
      boolean finished,
      boolean failed,
      String failureMessage,
      List<ExpectDto> expects) {
    this(script, finished, failed, failureMessage, expects, null);
  }

  public long passed() {
    return expects == null ? 0 : expects.stream().filter(ExpectDto::ok).count();
  }

  public long total() {
    return expects == null ? 0 : expects.size();
  }
}
