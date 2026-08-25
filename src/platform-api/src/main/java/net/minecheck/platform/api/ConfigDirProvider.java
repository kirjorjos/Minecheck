package net.minecheck.platform.api;

import java.nio.file.Path;

/** Provides the loader's config directory. */
public interface ConfigDirProvider {

  Path getConfigDirectory();
}
