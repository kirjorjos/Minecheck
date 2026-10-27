package net.minecheck.platform.api;

import java.util.List;

/**
 * Picks the recipe-viewer provider to use when more than one is installed.
 *
 * <p>Providers are tried in the order the loader registered them, which is also the documented
 * priority order (a viewer that can transfer recipes into the open container wins over one that can
 * only display them).
 */
public final class RecipeViewers {

  private RecipeViewers() {}

  /** The first available provider, or {@code null} when no viewer is installed/ready. */
  public static RecipeViewer active(List<RecipeViewer> providers) {
    if (providers == null) {
      return null;
    }
    for (RecipeViewer provider : providers) {
      if (provider != null && provider.isAvailable()) {
        return provider;
      }
    }
    return null;
  }

  /**
   * The available provider with the given viewer id (e.g. {@code "jei"}), or {@code null} when that
   * viewer is not installed/ready. Scripts use this when a viewer other than the priority one is
   * wanted, which is also how a specific viewer is verified when several are installed.
   */
  public static RecipeViewer byId(List<RecipeViewer> providers, String id) {
    if (providers == null || id == null) {
      return null;
    }
    for (RecipeViewer provider : providers) {
      if (provider != null && id.equals(provider.id()) && provider.isAvailable()) {
        return provider;
      }
    }
    return null;
  }
}
