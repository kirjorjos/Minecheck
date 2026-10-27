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

  /**
   * The loader's Minecheck packet transport, or {@code null} when the loader wires none. Used by
   * the {@code sendPacket}/{@code waitForPacket} script surface; scripts see an unavailable channel
   * as a false return rather than an error.
   */
  default net.minecheck.platform.api.net.NetworkPlatform network() {
    return null;
  }

  /**
   * The recipe viewer to bridge to (EMI/JEI/...), or {@code null} when none is installed or ready.
   * Loaders register one provider per installed viewer and resolve them via {@link
   * RecipeViewers#active(java.util.List)}, so an absent viewer is simply unavailable.
   */
  default RecipeViewer recipeViewer() {
    return null;
  }

  /**
   * A specific installed recipe viewer by id (see {@link RecipeViewers#byId(java.util.List,
   * String)}), or {@code null} when that viewer is not installed or ready.
   */
  default RecipeViewer recipeViewer(String id) {
    return null;
  }

  /**
   * True when the loader has the mod with the given id loaded (e.g. {@code "jei"}), on either side.
   * Scripts branch on soft dependencies with this. Loaders override it with their own mod list.
   */
  default boolean isModLoaded(String modId) {
    return false;
  }
}
