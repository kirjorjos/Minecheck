package net.minecheck.common.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.platform.api.WorldStorage;

/**
 * The saves directory on disk: the single implementation every loader's client platform returns.
 *
 * <p>The directory is the game's own, so nothing here guesses at a layout: a world is a folder with
 * a {@code level.dat} in it, which is exactly what the game writes and what the harness copies
 * around. The class is deliberately free of game types so it can be unit tested against a temporary
 * directory.
 */
public final class SavesWorldStorage implements WorldStorage {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private final Path saves;

  public SavesWorldStorage(Path saves) {
    this.saves = saves.toAbsolutePath().normalize();
  }

  @Override
  public List<String> worlds() {
    List<String> names = new ArrayList<>();
    if (!Files.isDirectory(saves)) {
      return names;
    }
    try (var stream = Files.list(saves)) {
      for (Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
        Path candidate = it.next();
        if (Files.isRegularFile(candidate.resolve("level.dat"))) {
          names.add(candidate.getFileName().toString());
        }
      }
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Minecheck: could not list " + saves, e);
    }
    return names;
  }

  @Override
  public boolean exists(String name) {
    Path dir = safeDirectory(name);
    return dir != null && Files.isRegularFile(dir.resolve("level.dat"));
  }

  @Override
  public boolean copy(String from, String to) {
    Path source = safeDirectory(from);
    Path target = safeDirectory(to);
    if (source == null || target == null || !Files.isRegularFile(source.resolve("level.dat"))) {
      return false;
    }
    try {
      delete(to);
      try (var stream = Files.walk(source)) {
        for (Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
          Path p = it.next();
          if (p.getFileName() != null && p.getFileName().toString().equals("session.lock")) {
            continue;
          }
          Path dest = target.resolve(source.relativize(p).toString());
          if (Files.isDirectory(p)) {
            Files.createDirectories(dest);
          } else {
            Files.createDirectories(dest.getParent());
            Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
      return true;
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Minecheck: could not copy the world " + from + " to " + to, e);
      return false;
    }
  }

  @Override
  public boolean delete(String name) {
    Path dir = safeDirectory(name);
    if (dir == null || !Files.isDirectory(dir)) {
      return false;
    }
    try (var stream = Files.walk(dir)) {
      List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
      for (Path p : paths) {
        Files.deleteIfExists(p);
      }
      return true;
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Minecheck: could not delete the world " + name, e);
      return false;
    }
  }

  @Override
  public Path directory(String name) {
    Path dir = safeDirectory(name);
    if (dir == null) {
      throw new IllegalArgumentException("not a world name: " + name);
    }
    return dir;
  }

  /**
   * The folder for {@code name}, or {@code null} when the name is not one segment directly under
   * the saves directory. A path separator, a {@code ..} or an absolute path is refused rather than
   * normalized into place: a script deletes a world by name, never by path.
   */
  private Path safeDirectory(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
      return null;
    }
    Path dir = saves.resolve(name).normalize();
    return dir.getParent() != null && dir.getParent().equals(saves) ? dir : null;
  }
}
