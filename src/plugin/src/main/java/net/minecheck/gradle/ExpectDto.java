package net.minecheck.gradle;

/**
 * One {@code minecheck.expect(...)} outcome as read from the mod's {@code run-result.json}.
 *
 * @param name the labelled check
 * @param ok whether the check passed
 */
public record ExpectDto(String name, boolean ok) {}
