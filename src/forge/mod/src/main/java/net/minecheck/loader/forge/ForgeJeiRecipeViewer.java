package net.minecheck.loader.forge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecheck.platform.api.GhostSlot;
import net.minecheck.platform.api.RecipeViewer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * JEI-backed {@link RecipeViewer} for Forge.
 *
 * <p>JEI is a soft dependency: this class is only instantiated when the mod is loaded, and every
 * JEI type it touches is used inside method bodies, so an absent JEI is never resolved. The runtime
 * is handed over by {@link ForgeJeiPlugin}, which JEI itself instantiates.
 */
public final class ForgeJeiRecipeViewer implements RecipeViewer {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** JEI's runtime, or {@code null} while JEI is absent or has not started. */
  private static volatile IJeiRuntime runtime;

  static void setRuntime(IJeiRuntime value) {
    runtime = value;
  }

  @Override
  public String id() {
    return "jei";
  }

  @Override
  public boolean isAvailable() {
    return runtime != null;
  }

  @Override
  public List<GhostSlot> recipeSlots(String recipeId) {
    try {
      RecipeMatch match = match(recipeId);
      if (match == null) {
        return List.of();
      }
      IRecipeSlotsView slots = slotsView(match);
      if (slots == null) {
        return List.of();
      }
      List<IRecipeSlotView> views = slots.getSlotViews(RecipeIngredientRole.INPUT);
      List<GhostSlot> out = new ArrayList<>(views.size());
      for (int i = 0; i < views.size(); i++) {
        out.add(new GhostSlot(i, itemIds(views.get(i))));
      }
      return out;
    } catch (Throwable t) {
      LOG.log(
          Level.WARNING,
          String.format("JEI could not read the ghost slots of recipe %s", recipeId),
          t);
      return List.of();
    }
  }

  @Override
  public boolean fillFromRecipe(String recipeId, int maxItems) {
    try {
      RecipeMatch match = match(recipeId);
      if (match == null) {
        return false;
      }
      Minecraft client = Minecraft.getInstance();
      if (client == null || client.player == null) {
        return false;
      }
      AbstractContainerMenu menu = client.player.containerMenu;
      if (menu == null) {
        return false;
      }
      IRecipeSlotsView slots = slotsView(match);
      if (slots == null) {
        return false;
      }
      IRecipeTransferManager transfers = runtime.getRecipeTransferManager();
      if (transfers == null) {
        return false;
      }
      IRecipeTransferHandler handler =
          transfers.getRecipeTransferHandler(menu, match.category()).orElse(null);
      if (handler == null) {
        return false;
      }
      Object error =
          handler.transferRecipe(menu, match.recipe(), slots, client.player, maxItems <= 0, true);
      return error == null;
    } catch (Throwable t) {
      LOG.log(Level.WARNING, String.format("JEI could not fill from recipe %s", recipeId), t);
      return false;
    }
  }

  /** One recipe JEI knows: the category that owns it and the recipe object itself. */
  private record RecipeMatch(IRecipeCategory<?> category, Object recipe) {}

  private static volatile boolean probed;

  /**
   * One-shot INFO diagnostic of what JEI's index holds, logged the first time a script reads a
   * recipe. An empty index is the difference between "the viewer is broken" and "this JEI build
   * registered no recipes" (a dev-environment JEI limitation: it can load its ingredients and
   * categories yet hold 0 recipes) — see the recipe-viewer notes.
   */
  private static void probe(
      IRecipeManager manager, IJeiRuntime rt, int categories, int recipes, List<String> sample) {
    if (probed) {
      return;
    }
    probed = true;
    int crafting =
        manager
            .getRecipeType(new ResourceLocation("minecraft", "crafting"))
            .map(type -> countFor(manager, type))
            .orElse(-1);
    LOG.info(
        String.format(
            "JEI index: %s categories, %s recipes, sample recipe ids %s | ingredients: %s | crafting recipes: %s",
            categories,
            recipes,
            sample,
            rt.getIngredientManager().getAllItemStacks().size(),
            crafting));
  }

  /** Diagnostic helper: how many recipes JEI holds for a given recipe type. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static int countFor(IRecipeManager manager, RecipeType<?> type) {
    int n = 0;
    Iterator<?> it = manager.createRecipeLookup((RecipeType) type).includeHidden().get().iterator();
    while (it.hasNext()) {
      it.next();
      n++;
    }
    return n;
  }

  /**
   * Finds the recipe with the given registry id. JEI has no id lookup, so every visible category is
   * scanned and each recipe is asked for its registry name (only vanilla-backed recipes have one).
   */
  private static RecipeMatch match(String recipeId) {
    IJeiRuntime rt = runtime;
    if (rt == null || recipeId == null) {
      return null;
    }
    IRecipeManager manager = rt.getRecipeManager();
    if (manager == null) {
      return null;
    }
    int categoryCount = 0;
    int recipeCount = 0;
    List<String> sample = new ArrayList<>();
    Iterator<IRecipeCategory<?>> categories =
        manager.createRecipeCategoryLookup().includeHidden().get().iterator();
    while (categories.hasNext()) {
      IRecipeCategory category = categories.next();
      categoryCount++;
      Iterator<?> recipes =
          manager.createRecipeLookup(category.getRecipeType()).includeHidden().get().iterator();
      while (recipes.hasNext()) {
        Object recipe = recipes.next();
        recipeCount++;
        ResourceLocation name = (ResourceLocation) category.getRegistryName(recipe);
        if (name != null && sample.size() < 5) {
          sample.add(name.toString());
        }
        if (name != null && recipeId.equals(name.toString())) {
          probe(manager, rt, categoryCount, recipeCount, sample);
          return new RecipeMatch(category, recipe);
        }
      }
    }
    probe(manager, rt, categoryCount, recipeCount, sample);
    return null;
  }

  /** The laid-out slot views of a recipe, as JEI would draw them for that recipe. */
  private static IRecipeSlotsView slotsView(RecipeMatch match) {
    IJeiRuntime rt = runtime;
    if (rt == null) {
      return null;
    }
    IFocusGroup focus = rt.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
    return layout(rt.getRecipeManager(), match, focus)
        .map(IRecipeLayoutDrawable::getRecipeSlotsView)
        .orElse(null);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Optional<IRecipeLayoutDrawable<Object>> layout(
      IRecipeManager manager, RecipeMatch match, IFocusGroup focus) {
    return (Optional)
        manager.createRecipeLayoutDrawable(
            (IRecipeCategory) match.category(), match.recipe(), focus);
  }

  /** The item ids a ghost slot accepts, tags already expanded to their members by JEI. */
  private static List<String> itemIds(IRecipeSlotView view) {
    List<String> ids = new ArrayList<>();
    Iterator<ItemStack> stacks = view.getItemStacks().iterator();
    while (stacks.hasNext()) {
      ItemStack stack = stacks.next();
      if (stack.isEmpty()) {
        continue;
      }
      ResourceLocation id = Registry.ITEM.getKey(stack.getItem());
      if (id != null && !ids.contains(id.toString())) {
        ids.add(id.toString());
      }
    }
    return ids;
  }
}
