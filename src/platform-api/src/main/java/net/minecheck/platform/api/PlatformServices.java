package net.minecheck.platform.api;

/**
 * Loader-provided services consumed by the common bootstrap. Each loader entrypoint builds an
 * implementation bound to its own APIs (mirrors the GlobalControls PlatformServices pattern).
 */
public interface PlatformServices {

  /** The running Minecraft version string (e.g. "1.21.1"). */
  String minecraftVersion();

  /** Where loader configs live (config dir of the running loader). */
  ConfigDirProvider configDir();

  /** Client-side services, or null when running on a dedicated server. */
  ClientPlatform client();

  /** Server-side services, or null when running on a client. */
  ServerPlatform server();

  /** True when running on a dedicated server (no client). */
  boolean dedicatedServer();
}
