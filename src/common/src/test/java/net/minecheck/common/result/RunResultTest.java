package net.minecheck.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for the verdict record itself.
 *
 * <p>{@link RunResultWriterTest} covers the file; this covers the <em>record</em> — the counting
 * the plugin trusts, and the shapes of its pieces. The counting matters because {@code passed() !=
 * total()} is precisely what fails a lifecycle task, so a contradiction between the two would turn
 * a real failure green.
 */
class RunResultTest {

  private static RunResult run(ExpectRecord... expects) {
    return new RunResult("s.lua", true, false, null, List.of(expects));
  }

  private static ExpectRecord check(String name, boolean ok) {
    return new ExpectRecord(name, ok);
  }

  // ------------------------------------------------------------ the expect record

  /**
   * The record's whole shape: a label and one bit. This is what "what does an expect record look
   * like" comes down to — there is deliberately no place for the <em>value</em> that was tested, so
   * a check involving an int64 or a byte string produces exactly the same record as any other: the
   * condition's truthiness became {@code ok}, and it leaves no trace in the file.
   */
  @Test
  void anExpectRecordIsJustALabelAndOneBit() {
    ExpectRecord passed = check("exact seed", true);
    ExpectRecord failed = check("exact seed", false);

    assertEquals("exact seed", passed.name());
    assertTrue(passed.ok());
    assertFalse(failed.ok(), "name and ok are the whole record");
    assertEquals(
        passed, new ExpectRecord("exact seed", true), "the record is the pair, nothing more");
  }

  // ------------------------------------------------------------ counting

  @Test
  void passedAndTotalCountTheExpectsExactly() {
    RunResult mixed = run(check("a", true), check("b", false), check("c", true), check("d", false));

    assertEquals(2, mixed.passed());
    assertEquals(4, mixed.total());
    assertTrue(mixed.passed() != mixed.total(), "the case the plugin refuses to forgive");
  }

  @Test
  void noExpectsIsZeroOfZero() {
    RunResult empty = run();

    assertEquals(0, empty.passed());
    assertEquals(0, empty.total());
  }

  /** The record's own guarantee: {@code passed()} can never exceed {@code total()}. */
  @Test
  void passedNeverExceedsTotal() {
    for (int n = 0; n < 10; n++) {
      RunResult r = run(check("x", n % 2 == 0), check("y", true), check("z", n % 3 == 0));
      assertTrue(r.passed() <= r.total(), "passed=" + r.passed() + " total=" + r.total());
    }
  }

  // ------------------------------------------------------------ null handling

  /**
   * The plugin's mirror ({@code RunResultDto}) counts a missing {@code expects} field as zero; the
   * mod's own record must not be stricter than the file it is deserialized from.
   */
  @Test
  void aNullExpectsListIsZeroOfZeroNotACrash() {
    RunResult fromOldFile = new RunResult("s.lua", true, false, null, null);

    assertEquals(0, fromOldFile.passed());
    assertEquals(0, fromOldFile.total());
    assertTrue(fromOldFile.expects().isEmpty(), "normalized to an empty list, not kept null");
  }

  @Test
  void everyFieldIsReadBackAsGiven() {
    RunResult failed =
        new RunResult(
            "fail-path-client.lua",
            false,
            true,
            "minecheck.fail: expected the run to fail",
            List.of(check("joined the dedicated server", true)));

    assertEquals("fail-path-client.lua", failed.script());
    assertFalse(failed.finished());
    assertTrue(failed.failed());
    assertEquals("minecheck.fail: expected the run to fail", failed.failureMessage());
    assertEquals(1, failed.total());
  }
}
