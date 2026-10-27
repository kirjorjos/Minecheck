package net.minecheck.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Direct tests for the config record itself.
 *
 * <p>{@link JsonConfigManagerTest} covers the file; this covers the <em>type</em> — every field,
 * the defaults, and the three derived answers the rest of the mod actually branches on ({@code
 * entryScript}, {@code isCoordinator}, {@code exitOnFail}). They are tested here rather than only
 * through a loaded file so a rule about a blank or absent value cannot be lost behind a default
 * that happens to be filled in first.
 */
class ConfigDataTest {

  // ------------------------------------------------------------ defaults

  @Test
  void theDefaultsRunNothingAndStopOnFailure() {
    ConfigData defaults = ConfigData.defaults();

    assertEquals("", defaults.script(), "an empty script means the run does nothing");
    assertNull(defaults.coordinator(), "no coordinator, and null rather than an empty string");
    assertEquals("exit", defaults.failMode());
    assertEquals(30, defaults.defaultTimeout());

    assertFalse(defaults.isCoordinator());
    assertEquals("", defaults.entryScript());
    assertTrue(defaults.exitOnFail(), "the default is to stop on failure");
  }

  @Test
  void defaultsReturnsAFreshEqualValueEveryTime() {
    // A record, so this is really pinning that no mutable state sneaks into it: two calls are
    // interchangeable, and the fields a caller reads are the ones it passed.
    assertEquals(ConfigData.defaults(), ConfigData.defaults());

    ConfigData explicit = new ConfigData("", null, "exit", 30);
    assertEquals(ConfigData.defaults(), explicit, "the defaults are just one ConfigData value");
    assertEquals(ConfigData.defaults().hashCode(), explicit.hashCode());
  }

  @Test
  void everyFieldIsReadBackAsGiven() {
    ConfigData config = new ConfigData("phase.lua", "coordinator.lua", "continue", 90);

    assertEquals("phase.lua", config.script());
    assertEquals("coordinator.lua", config.coordinator());
    assertEquals("continue", config.failMode());
    assertEquals(90, config.defaultTimeout());
  }

  // ------------------------------------------------------------ entry script

  @Test
  void theCoordinatorWinsOverTheScriptWhenItIsSet() {
    ConfigData both = new ConfigData("phase.lua", "coordinator.lua", "exit", 30);

    assertTrue(both.isCoordinator());
    assertEquals("coordinator.lua", both.entryScript());
    assertEquals("phase.lua", both.script(), "the phase script is still reported");
  }

  @Test
  void anAbsentOrBlankCoordinatorFallsBackToTheScript() {
    for (String coordinator : new String[] {null, "", "   ", "\t\n"}) {
      ConfigData config = new ConfigData("phase.lua", coordinator, "exit", 30);
      assertFalse(
          config.isCoordinator(),
          "a blank coordinator is not a coordinator: " + quote(coordinator));
      assertEquals("phase.lua", config.entryScript(), "must fall back: " + quote(coordinator));
    }
  }

  @Test
  void aBlankScriptIsReturnedAsIsForTheCallerToReject() {
    // entryScript() does not try to be helpful here: an all-whitespace script is a *value*, and the
    // bootstrap is what decides it means "passive" (it calls isBlank()). Pinned so a future change
    // cannot start silently matching a file named " " or swallowing the state entirely.
    ConfigData spaces = new ConfigData("  ", null, "exit", 30);

    assertEquals("  ", spaces.entryScript());
    assertFalse(spaces.isCoordinator());

    ConfigData neither = new ConfigData("", null, "exit", 30);
    assertEquals("", neither.entryScript(), "no script and no coordinator is an empty entry");
  }

  /**
   * The invariant the rest of the mod depends on: {@code isCoordinator()} is true exactly when
   * {@code entryScript()} is the coordinator. If these two ever disagree, a run picks one file and
   * reports the other.
   */
  @Test
  void isCoordinatorAgreesWithEntryScriptAcrossEveryCombination() {
    String[] coordinators = {null, "", "  ", "coordinator.lua"};
    String[] scripts = {"", "  ", "phase.lua"};

    for (String coordinator : coordinators) {
      for (String script : scripts) {
        ConfigData config = new ConfigData(script, coordinator, "exit", 30);
        String label = "script=" + quote(script) + " coordinator=" + quote(coordinator);

        if (config.isCoordinator()) {
          assertEquals(
              coordinator, config.entryScript(), "the coordinator must be the entry: " + label);
        } else {
          assertEquals(script, config.entryScript(), "the script must be the entry: " + label);
        }
      }
    }
  }

  // ------------------------------------------------------------ fail mode

  @Test
  void exitOnFailIsOptOutAndCaseInsensitive() {
    assertFalse(new ConfigData("s.lua", null, "continue", 30).exitOnFail());
    assertFalse(new ConfigData("s.lua", null, "CONTINUE", 30).exitOnFail());
    assertFalse(new ConfigData("s.lua", null, "Continue", 30).exitOnFail());

    assertTrue(new ConfigData("s.lua", null, "exit", 30).exitOnFail());
  }

  /**
   * Anything that is not {@code "continue"} stops the run. A typo therefore fails <em>safe</em> —
   * it is the strict reading, not the lenient one — which is the direction a test harness should
   * err in, since the alternative is a suite that reports success after a failure.
   */
  @Test
  void anyFailModeThatIsNotContinueMeansStop() {
    for (String failMode : new String[] {"exit", "", "   ", "continu", "EXIT", "false", "no"}) {
      assertTrue(
          new ConfigData("s.lua", null, failMode, 30).exitOnFail(),
          "must stop on failure: " + quote(failMode));
    }
  }

  @Test
  void aNullFailModeDoesNotThrowAndStopsTheRun() {
    // Not reachable from the file (JsonConfigManager fills in "exit"), but the record is also built
    // by hand in tests and by the bootstrap, so it must not NPE on the way to a decision.
    ConfigData config = new ConfigData("s.lua", null, null, 30);

    assertTrue(config.exitOnFail());
  }

  @Test
  void theTimeoutIsCarriedVerbatimIncludingZeroAndNegative() {
    // The record does not police the range — JsonConfigManager rejects what cannot be represented,
    // and 0 or a negative number *are* representable. They mean "every wait expires at once", which
    // is coherent, so the value is passed through rather than clamped into something else.
    assertEquals(0, new ConfigData("s.lua", null, "exit", 0).defaultTimeout());
    assertEquals(-5, new ConfigData("s.lua", null, "exit", -5).defaultTimeout());
    assertEquals(
        Integer.MAX_VALUE,
        new ConfigData("s.lua", null, "exit", Integer.MAX_VALUE).defaultTimeout());
  }

  private static String quote(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }
}
