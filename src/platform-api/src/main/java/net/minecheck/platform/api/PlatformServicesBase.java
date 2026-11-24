package net.minecheck.platform.api;

/**
 * The shared implementations {@link PlatformServices} used to carry as {@code default} methods. See
 * {@link ClientPlatformBase} for why they live in a superclass.
 */
public abstract class PlatformServicesBase implements PlatformServices {

  @Override
  public net.minecheck.platform.api.net.NetworkPlatform network() {
    return null;
  }

  @Override
  public RecipeViewer recipeViewer() {
    return null;
  }

  @Override
  public RecipeViewer recipeViewer(String id) {
    return null;
  }

  @Override
  public boolean isModLoaded(String modId) {
    return false;
  }
}
