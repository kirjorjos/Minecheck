package net.minecheck.common.result;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a {@link RunResult} to JSON (Gson, matching the config manager's serializer) at the config
 * dir's reserved {@code run-result.json} path.
 *
 * <p>The write is best-effort: a failure to persist the result is logged and swallowed so it never
 * crashes the game over the CI contract.
 */
public final class RunResultWriter {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private RunResultWriter() {}

  public static void write(Path file, RunResult result) {
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, GSON.toJson(result));
    } catch (IOException e) {
      System.err.println("Minecheck: failed to write " + file + ": " + e.getMessage());
    }
  }
}
