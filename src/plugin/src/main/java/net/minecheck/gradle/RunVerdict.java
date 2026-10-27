package net.minecheck.gradle;

/**
 * The pass/fail decision for one {@code run-result.json}.
 *
 * <p>Deliberately a plain, dependency-free class rather than logic inside {@link
 * MinecheckLifecycleTest}: this is the single place that decides whether a lifecycle task is green,
 * so it has to be reachable from a unit test (a Gradle task is not — it needs a live project and
 * two JVM launches to do anything).
 *
 * <p>The rules, all of them deliberate:
 *
 * <ul>
 *   <li>A missing {@code run-result.json} is always a failure, even when the run was
 *       <em>expected</em> to fail: a run that reports nothing at all proved nothing.
 *   <li>{@code minecheck.expect} losses count as failures ({@code passed() != total()}), so a run
 *       cannot print "PASSED (6/7)" and go green while hiding what it measured.
 *   <li>"Did not pass" means the same thing in both modes — unfinished, failed, <em>or</em> a lost
 *       expectation — and {@code expectFailure} accepts any of them as {@link
 *       Outcome#FAILED_AS_EXPECTED}. The fail-path suites assert the recorded failure <em>and</em>
 *       a lost expectation, so this can never be the only thing such a suite proves.
 *   <li>A run that passed is never tolerated by {@code expectFailure}; it is reported as a plain
 *       failure, never as "failed as expected".
 * </ul>
 */
public final class RunVerdict {

  /** How a run's recorded outcome maps onto the task's expectation. */
  public enum Outcome {
    /** The run finished, recorded no failure, and lost no expectation. */
    PASSED,
    /** The run failed, and this task was declared to expect a failure. */
    FAILED_AS_EXPECTED,
    /** The verdict is a failure: the task must fail. */
    FAILED
  }

  private final RunResultDto result;
  private final boolean expectFailure;
  private final Outcome outcome;

  private RunVerdict(RunResultDto result, boolean expectFailure, Outcome outcome) {
    this.result = result;
    this.expectFailure = expectFailure;
    this.outcome = outcome;
  }

  /** Decides the verdict for one run, whose result may be absent ({@code null}). */
  public static RunVerdict of(RunResultDto result, boolean expectFailure) {
    if (result == null) {
      return new RunVerdict(null, expectFailure, Outcome.FAILED);
    }
    boolean lostExpectation = result.passed() != result.total();
    boolean runFailed = !result.finished() || result.failed() || lostExpectation;
    Outcome outcome;
    if (runFailed == expectFailure) {
      outcome = expectFailure ? Outcome.FAILED_AS_EXPECTED : Outcome.PASSED;
    } else {
      outcome = Outcome.FAILED;
    }
    return new RunVerdict(result, expectFailure, outcome);
  }

  public Outcome outcome() {
    return outcome;
  }

  public boolean isFailure() {
    return outcome == Outcome.FAILED;
  }

  /** The one-line log summary: e.g. {@code PASSED (15 expects passed / 15 total)}. */
  public String summary() {
    if (result == null) {
      return "FAILED (no run-result.json)";
    }
    String counts = result.passed() + " expects passed / " + result.total() + " total";
    return switch (outcome) {
      case PASSED -> "PASSED (" + counts + ")";
      case FAILED_AS_EXPECTED ->
          "FAILED AS EXPECTED ("
              + counts
              + ")"
              + (result.failureMessage() != null ? ": " + result.failureMessage() : "");
      case FAILED -> "FAILED (" + counts + ")";
    };
  }

  /**
   * Why this run is a failure; the sentence the task puts in its exception. {@code null} when the
   * run is not a failure — there is nothing to explain.
   */
  public String detail() {
    if (outcome != Outcome.FAILED) {
      return null;
    }
    if (result == null) {
      return "wrote no run-result.json";
    }
    if (expectFailure) {
      return "expected the run to fail but it passed ("
          + result.passed()
          + "/"
          + result.total()
          + " expects)";
    }
    if (result.passed() != result.total()) {
      return result.passed()
          + "/"
          + result.total()
          + " expects passed"
          + (result.failureMessage() != null ? " — " + result.failureMessage() : "");
    }
    return result.failureMessage() != null ? result.failureMessage() : "the run failed";
  }
}
