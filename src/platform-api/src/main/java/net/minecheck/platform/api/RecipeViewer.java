package net.minecheck.platform.api;

import java.util.List;

/**
 * Loader-independent bridge to a recipe-viewer mod (EMI, JEI, REI, ...).
 *
 * <p>Viewers are <b>soft dependencies</b>: a loader registers one provider per viewer it finds
 * installed (see {@link RecipeViewers#active(List)}), the provider lives in its own class, and that
 * class only touches the viewer's API inside method bodies — so an absent viewer is never resolved.
 * With no viewer installed the Lua actions report "unavailable" instead of failing.
 */
public interface RecipeViewer {

  /** Short viewer id, e.g. {@code "emi"} or {@code "jei"}. */
  String id();

  /** True when the viewer is loaded and its API is ready to answer queries. */
  boolean isAvailable();

  /**
   * The ghost ingredient slots of the recipe with the given registry id, in slot order. Empty when
   * the recipe is unknown to the viewer (or the viewer is not ready).
   */
  List<GhostSlot> recipeSlots(String recipeId);

  /**
   * Asks the viewer to fill the currently open container from the named recipe (its recipe-transfer
   * / fill action). {@code maxItems} caps how many items may be placed; {@code 0} uses the viewer's
   * default. Returns {@code true} when the viewer accepted the request.
   */
  boolean fillFromRecipe(String recipeId, int maxItems);
}
