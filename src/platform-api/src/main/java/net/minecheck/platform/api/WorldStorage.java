package net.minecheck.platform.api;

import java.nio.file.Path;
import java.util.List;

/**
 * The client's saves directory, as a script sees it: the worlds that exist, whether one does, and
 * the two operations a suite performs on them without a world open — copy and delete.
 *
 * <p>Why a platform service rather than paths in the script: only the game knows where its saves
 * live (it depends on the run directory the loader launched with), and a script that deleted by
 * path would be one typo from the wrong tree. Every name is a single folder name under that
 * directory; anything else is rejected rather than resolved.
 *
 * <p>A dedicated server has no saves directory of its own — its level is a directory in its run dir
 * — so {@link PlatformServices#worldStorage()} answers {@code null} there.
 */
public interface WorldStorage {

  /** The names of the worlds on disk, in no particular order. */
  List<String> worlds();

  /** True when a world of that name exists and is complete (it has a {@code level.dat}). */
  boolean exists(String name);

  /**
   * Copies {@code from} to {@code to}, replacing {@code to} if it exists.
   *
   * <p>{@code session.lock} is skipped: it is the lock file of whichever server had the world open,
   * meaningless in another tree. Everything else, {@code level.dat} first among them, is what makes
   * it the same world.
   *
   * @return false when {@code from} does not exist
   */
  boolean copy(String from, String to);

  /**
   * Deletes the world of that name.
   *
   * @return false when it does not exist
   */
  boolean delete(String name);

  /**
   * The directory a world of that name lives in, whether or not it exists yet — the handle a caller
   * passes to a platform that stages a world tree itself (the dedicated server's setup dir).
   *
   * @throws IllegalArgumentException when the name is not a single folder name
   */
  Path directory(String name);
}
