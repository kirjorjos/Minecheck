package net.minecheck.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the decision that makes a lifecycle task green.
 *
 * <p>Every case here is one the live suites cannot cheaply produce: a missing result file, a run
 * that lost one of its recorded expectations, a run that failed when it was expected to pass. They
 * are the ways a suite can look green while having verified nothing.
 */
class RunVerdictTest {

  private static RunResultDto result(
      boolean finished, boolean failed, String message, ExpectDto... expects) {
    return new RunResultDto("s.lua", finished, failed, message, List.of(expects));
  }

  private static ExpectDto ok(String name) {
    return new ExpectDto(name, true);
  }

  private static ExpectDto notOk(String name) {
    return new ExpectDto(name, false);
  }

  @Test
  void aFinishedRunWithoutFailuresPasses() {
    RunVerdict verdict = RunVerdict.of(result(true, false, null, ok("joined"), ok("left")), false);

    assertEquals(RunVerdict.Outcome.PASSED, verdict.outcome());
    assertFalse(verdict.isFailure());
    assertEquals("PASSED (2 expects passed / 2 total)", verdict.summary());
  }

  @Test
  void aMissingResultIsAFailureEvenWhenAFailureWasExpected() {
    // A run that reports nothing proved nothing, so this must never be tolerated — including in the
    // fail-path suites, where passing would look like "it failed as expected".
    for (boolean expectFailure : new boolean[] {false, true}) {
      RunVerdict verdict = RunVerdict.of(null, expectFailure);
      assertTrue(verdict.isFailure(), "no result must fail (expectFailure=" + expectFailure + ")");
      assertEquals("wrote no run-result.json", verdict.detail());
    }
  }

  @Test
  void aLostExpectationFailsEvenThoughTheRunFinished() {
    // The trap this rule exists for: `finished && !failed` was true, so the run used to go green
    // while one of its checks was demonstrably false.
    RunVerdict verdict = RunVerdict.of(result(true, false, null, ok("a"), notOk("b")), false);

    assertEquals(RunVerdict.Outcome.FAILED, verdict.outcome());
    assertEquals("1/2 expects passed", verdict.detail());
  }

  @Test
  void expectFailureAcceptsAnyRunThatDidNotPass() {
    // "Did not pass" is uniform across both modes: finished && !failed && no lost expectation. So a
    // fail-path suite is green for any shape of not-passing, including a run that merely lost a
    // check — the suites themselves do both (expect(false, ...) *and* minecheck.fail) so this can
    // never be the only thing they prove.
    for (RunResultDto didNotPass :
        new RunResultDto[] {
          result(false, true, "minecheck.fail: boom", notOk("a")),
          result(true, false, null, notOk("a")),
          result(false, false, null, ok("a")),
        }) {
      assertEquals(
          RunVerdict.Outcome.FAILED_AS_EXPECTED,
          RunVerdict.of(didNotPass, true).outcome(),
          "expected a failure and got one: " + didNotPass);
    }

    // ...but a run that genuinely passed is never tolerated in either mode.
    RunVerdict passed = RunVerdict.of(result(true, false, null, ok("a")), true);
    assertEquals(RunVerdict.Outcome.FAILED, passed.outcome());
    assertTrue(
        passed.detail().startsWith("expected the run to fail but it passed"),
        "got: " + passed.detail());
  }

  @Test
  void anUnfinishedRunFails() {
    RunVerdict verdict = RunVerdict.of(result(false, true, "script error", ok("a")), false);

    assertEquals(RunVerdict.Outcome.FAILED, verdict.outcome());
    assertEquals("script error", verdict.detail());
  }

  @Test
  void aReportedFailureFailsWithItsMessage() {
    RunVerdict verdict = RunVerdict.of(result(false, true, "minecheck.fail: boom", ok("a")), false);

    assertEquals(RunVerdict.Outcome.FAILED, verdict.outcome());
    assertEquals("minecheck.fail: boom", verdict.detail());
  }

  @Test
  void expectFailureInvertsAFailingRun() {
    RunVerdict verdict = RunVerdict.of(result(false, true, "minecheck.fail: boom", ok("a")), true);

    assertEquals(RunVerdict.Outcome.FAILED_AS_EXPECTED, verdict.outcome());
    assertFalse(verdict.isFailure());
    assertEquals(
        "FAILED AS EXPECTED (1 expects passed / 1 total): minecheck.fail: boom", verdict.summary());
  }

  @Test
  void expectFailureButAPassingRunIsAPlainFailure() {
    RunVerdict verdict = RunVerdict.of(result(true, false, null, ok("a")), true);

    assertEquals(RunVerdict.Outcome.FAILED, verdict.outcome());
    assertTrue(
        verdict.detail().startsWith("expected the run to fail but it passed"),
        "got: " + verdict.detail());
  }

  @Test
  void aResultWithoutTheExpectsFieldCountsAsZeroExpects() {
    // Hand-written or older results may omit `expects`; that is 0/0, not a crash.
    RunResultDto noExpects = new RunResultDto("s.lua", true, false, null, null);

    assertEquals(0, noExpects.passed());
    assertEquals(0, noExpects.total());
    assertEquals(RunVerdict.Outcome.PASSED, RunVerdict.of(noExpects, false).outcome());

    RunResultDto failedNoExpects = new RunResultDto("s.lua", true, true, null, null);
    assertEquals(RunVerdict.Outcome.FAILED, RunVerdict.of(failedNoExpects, false).outcome());
    assertEquals(
        "the run failed",
        RunVerdict.of(failedNoExpects, false).detail(),
        "a failure with no message still says something useful");
  }

  @Test
  void aSummaryNeverPrintsALiteralNullMessage() {
    RunResultDto failed = new RunResultDto("s.lua", true, false, null, List.of(notOk("a")));

    String summary = RunVerdict.of(failed, true).summary();

    assertFalse(summary.contains("null"), "got: " + summary);
    assertEquals("FAILED AS EXPECTED (0 expects passed / 1 total)", summary);
  }

  @Test
  void detailIsNullUnlessTheRunIsAFailure() {
    assertEquals(
        "0/1 expects passed", RunVerdict.of(result(true, false, null, notOk("a")), false).detail());
    assertEquals("wrote no run-result.json", RunVerdict.of(null, false).detail());

    assertNull(
        RunVerdict.of(result(true, false, null, ok("a")), false).detail(),
        "a passing run has nothing to explain");
    assertNull(
        RunVerdict.of(result(false, true, "boom", ok("a")), true).detail(),
        "a failure that was expected is not an error to report");
  }
}
