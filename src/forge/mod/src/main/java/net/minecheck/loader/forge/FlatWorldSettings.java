package net.minecheck.loader.forge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The flat world every Minecheck suite runs in, taken from the declaration the harness also hands
 * the dedicated server.
 *
 * <p>{@code tests/flat-world.json} is the one description of the world's layers and biome: it is
 * bundled into this mod, handed to level creation for the worlds a script creates, and written
 * verbatim into the server's {@code generator-settings}. Both sides therefore build the same
 * surface, so the fixed coordinates and surface heights the fixtures assume cannot drift from the
 * world they run in.
 *
 * <p>1.12.2's {@code WorldSettings} takes the flat generator's settings as the option string, so
 * this line hands the declaration over as text rather than as a parsed element.
 */
final class FlatWorldSettings {

  private static final String RESOURCE = "/flat-world.json";

  private FlatWorldSettings() {}

  /** The declaration, validated, ready for {@code WorldSettings#setGeneratorOptions}. */
  static String declaration() {
    try (InputStream in = FlatWorldSettings.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the mod's resources");
      }
      JsonElement declared =
          new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class);
      if (declared == null || !declared.isJsonObject()) {
        throw new IllegalStateException(RESOURCE + " is not a JSON object");
      }
      JsonElement layers = declared.getAsJsonObject().get("layers");
      if (layers == null || !layers.isJsonArray() || layers.getAsJsonArray().size() == 0) {
        throw new IllegalStateException(RESOURCE + " declares no layers");
      }
      return declared.toString();
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + RESOURCE, e);
    }
  }
}
