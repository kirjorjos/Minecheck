package net.minecheck.common.config;

import java.io.IOException;

public interface ConfigManager {

  ConfigData load() throws IOException;

  void save(ConfigData config) throws IOException;
}
