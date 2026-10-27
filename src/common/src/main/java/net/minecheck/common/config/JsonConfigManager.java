package net.minecheck.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads/writes {@code config/minecheck/config.json}.
 *
 * <p>Every field is optional (see {@link ConfigData#defaults()}); a missing field, and an explicit
 * JSON {@code null}, both fall back to the documented default. A file that cannot be read as an
 * object of the expected types is reported as an {@link IOException} naming the file and the field
 * — it is deliberately <em>not</em> silently replaced by the defaults, because the defaults run no
 * script at all, and the resulting "wrote no run-result.json" hides the real cause. The broken file
 * is left in place so it can be inspected.
 */
public class JsonConfigManager implements ConfigManager {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private final Path configDir; // .../minecheck

  public JsonConfigManager(Path loaderConfigDir) {
    this.configDir = loaderConfigDir.resolve("minecheck");
  }

  public Path configDirectory() {
    return configDir;
  }

  public Path configFile() {
    return configDir.resolve("config.json");
  }

  public Path scriptDirectory() {
    return configDir.resolve("scripts");
  }

  public Path resultFile() {
    return configDir.resolve("run-result.json");
  }

  @Override
  public ConfigData load() throws IOException {
    Files.createDirectories(configDir);
    Path file = configFile();
    if (!Files.exists(file)) {
      ConfigData defaults = ConfigData.defaults();
      save(defaults);
      return defaults;
    }
    String json = Files.readString(file);
    try {
      JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
      return new ConfigData(
          stringField(obj, "script", ""),
          stringField(obj, "coordinator", null),
          stringField(obj, "failMode", "exit"),
          intField(obj, "defaultTimeout", 30));
    } catch (RuntimeException e) {
      throw new IOException(
          String.format(
              "%s is not a usable config (%s) — fix it or delete it to regenerate the defaults",
              file, e.getMessage()),
          e);
    }
  }

  @Override
  public void save(ConfigData config) throws IOException {
    Files.createDirectories(configDir);
    Files.writeString(configFile(), GSON.toJson(config));
  }

  /** A string field, or {@code fallback} when absent or JSON {@code null}. */
  private static String stringField(JsonObject obj, String key, String fallback) {
    JsonElement value = present(obj, key);
    if (value == null) {
      return fallback;
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException("\"" + key + "\" must be a string");
    }
    return value.getAsString();
  }

  /**
   * A whole-number field, or {@code fallback} when absent or JSON {@code null}.
   *
   * <p>Checked for being a whole number that fits, rather than handed to {@code getAsInt()}, which
   * silently mangles whatever it cannot hold: {@code 2147483648} came out as <b>-2147483648</b> (a
   * {@code defaultTimeout} of "every wait expires instantly"), and {@code 0.5} — half a second — as
   * <b>0</b>. A value the field cannot represent is reported the same way a wrong type is, naming
   * the file and the field, because a quietly altered timeout is exactly the "hides the real cause"
   * failure this class exists to avoid.
   */
  private static int intField(JsonObject obj, String key, int fallback) {
    JsonElement value = present(obj, key);
    if (value == null) {
      return fallback;
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException("\"" + key + "\" must be a number");
    }
    double number = value.getAsDouble();
    if (number != Math.rint(number)) {
      throw new IllegalArgumentException(
          "\"" + key + "\" must be a whole number, got " + value.getAsString());
    }
    if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "\""
              + key
              + "\" must fit in a 32-bit integer ("
              + Integer.MIN_VALUE
              + " to "
              + Integer.MAX_VALUE
              + "), got "
              + value.getAsString());
    }
    return (int) number;
  }

  /** The field's value, or {@code null} when it is absent or an explicit JSON {@code null}. */
  private static JsonElement present(JsonObject obj, String key) {
    return !obj.has(key) || obj.get(key).isJsonNull() ? null : obj.get(key);
  }
}
