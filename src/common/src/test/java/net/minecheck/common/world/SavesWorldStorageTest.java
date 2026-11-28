package net.minecheck.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavesWorldStorageTest {

  @TempDir Path tempDir;

  /** Writes a minimal world tree, the way the game leaves one behind. */
  private void world(String name) throws Exception {
    Path dir = tempDir.resolve("saves").resolve(name);
    Files.createDirectories(dir.resolve("region"));
    Files.writeString(dir.resolve("level.dat"), "level");
    Files.writeString(dir.resolve("session.lock"), "lock");
    Files.writeString(dir.resolve("region").resolve("r.0.0.mca"), "chunk");
  }

  private SavesWorldStorage storage() {
    return new SavesWorldStorage(tempDir.resolve("saves"));
  }

  @Test
  void existsAnswersForACompleteWorldOnly() throws Exception {
    world("suite");
    Files.createDirectories(tempDir.resolve("saves").resolve("half-made"));

    assertEquals(List.of("suite"), storage().worlds());
    assertTrue(storage().exists("suite"));
    assertFalse(storage().exists("half-made"), "a folder without level.dat is not a world");
    assertFalse(storage().exists("missing"));
  }

  @Test
  void copySkipsTheSessionLockAndReplacesTheTarget() throws Exception {
    world("suite");

    assertTrue(storage().copy("suite", "copy"));
    Path copy = tempDir.resolve("saves").resolve("copy");
    assertTrue(Files.isRegularFile(copy.resolve("level.dat")));
    assertTrue(Files.isRegularFile(copy.resolve("region").resolve("r.0.0.mca")));
    assertFalse(
        Files.exists(copy.resolve("session.lock")),
        "the lock belongs to whichever server had the world open");

    assertFalse(storage().copy("missing", "copy"), "nothing to copy");
  }

  @Test
  void deleteRemovesTheWholeTree() throws Exception {
    world("suite");

    assertTrue(storage().delete("suite"));
    assertFalse(Files.exists(tempDir.resolve("saves").resolve("suite")));
    assertFalse(storage().delete("suite"), "already gone");
  }

  /** A name is one folder under saves: a path, an escape or a blank is refused, not resolved. */
  @Test
  void aNameThatIsNotOneFolderIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> storage().directory("../elsewhere"));
    assertThrows(IllegalArgumentException.class, () -> storage().directory("nested/world"));
    assertThrows(IllegalArgumentException.class, () -> storage().directory(""));
    assertFalse(storage().delete("../elsewhere"));
    assertFalse(storage().exists(".."));
  }
}
