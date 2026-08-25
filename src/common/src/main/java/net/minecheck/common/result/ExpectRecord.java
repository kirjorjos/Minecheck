package net.minecheck.common.result;

/**
 * One recorded expectation from a script run (the CI contract, spec §8.4/§16).
 *
 * <p>{@code name} is a human/CI-readable label; {@code ok} is the pass/fail of a single {@code
 * minecheck.expect(...)} call. The script may branch on it and keep running, but the record is
 * always produced so the Gradle plugin can report which checks ran.
 *
 * @param name the labelled check (e.g. "joined world")
 * @param ok whether the check passed
 */
public record ExpectRecord(String name, boolean ok) {}
