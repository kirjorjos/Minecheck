package net.minecheck.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    JsonObject obj = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    return new ConfigData(
        obj.has("script") && !obj.get("script").isJsonNull() ? obj.get("script").getAsString() : "",
        obj.has("coordinator") && !obj.get("coordinator").isJsonNull()
            ? obj.get("coordinator").getAsString()
            : null,
        obj.has("failMode") && !obj.get("failMode").isJsonNull()
            ? obj.get("failMode").getAsString()
            : "exit",
        obj.has("defaultTimeout") && !obj.get("defaultTimeout").isJsonNull()
            ? obj.get("defaultTimeout").getAsInt()
            : 30);
  }

  @Override
  public void save(ConfigData config) throws IOException {
    Files.createDirectories(configDir);
    Files.writeString(configFile(), GSON.toJson(config));
  }
}
