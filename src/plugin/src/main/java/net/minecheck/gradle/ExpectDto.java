package net.minecheck.gradle;

/**
 * One {@code minecheck.expect(...)} outcome as read from the mod's {@code run-result.json}.
 *
 * @param name the labelled check
 * @param ok whether the check passed
 * @param phase the {@code minecheck.phase} the check ran under, or {@code null} outside one — the
 *     field is absent from results written before phases existed
 */
public record ExpectDto(String name, boolean ok, String phase) {

  /** The shape every caller used before phases existed. */
  public ExpectDto(String name, boolean ok) {
    this(name, ok, null);
  }
}
