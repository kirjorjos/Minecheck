package net.minecheck.loader.forge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.gui.recipes.layout.IRecipeLayoutInternal;
import mezz.jei.common.recipes.RecipeTransferManager;
import net.minecheck.platform.api.GhostSlot;
import net.minecheck.platform.api.RecipeViewer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
 *
 * <p>Two things JEI exposes on 1.18.2 only through internals are reached deliberately and in one
 * place: the slot views of a laid-out recipe, and the transfer manager that owns the per-screen
 * transfer handlers (JEI 11 promoted it to the API as {@code IRecipeTransferManager}). Both reads
 * happen inside try/catch so a JEI build that moves them degrades to "no ghost slots" / "fill
 * refused" instead of breaking the script.
 */
public final class ForgeJeiRecipeViewer implements RecipeViewer {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** JEI's runtime, or {@code null} while JEI is absent or has not started. */
  private static volatile IJeiRuntime runtime;

  /** Cached private field on JEI's recipes screen; {@code null} until first looked up. */
  private static volatile Field transferManagerField;

  /** Cached private field on JEI's layout drawable adapter; {@code null} until first looked up. */
  private static volatile Field internalLayoutField;

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
      // A transfer needs an open container screen: the player's own inventory menu exists even with
      // no screen open, so reading `player.containerMenu` would "fill" a container nobody is
      // looking at (and JEI's player-inventory handler would happily return no error for it).
      if (client == null || client.player == null) {
        return false;
      }
      if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
        return false;
      }
      AbstractContainerMenu menu = screen.getMenu();
      if (menu == null) {
        return false;
      }
      IRecipeSlotsView slots = slotsView(match);
      if (slots == null) {
        return false;
      }
      RecipeTransferManager transfers = transferManager();
      if (transfers == null) {
        return false;
      }
      @SuppressWarnings("unchecked")
      IRecipeTransferHandler<AbstractContainerMenu, Object> handler =
          (IRecipeTransferHandler<AbstractContainerMenu, Object>)
              transfers.getRecipeTransferHandler(menu, (IRecipeCategory<Object>) match.category());
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

  /** A laid-out recipe: its slot views plus the drawable JEI handed us (needed for transfer). */
  private record Layout(IRecipeSlotsView slots, IRecipeLayoutDrawable drawable) {}

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
    IRecipeCategory<?> crafting =
        manager.getRecipeCategory(new ResourceLocation("minecraft", "crafting"), false);
    int craftingRecipes =
        crafting == null ? -1 : manager.getRecipes(crafting, List.of(), true).size();
    LOG.info(
        String.format(
            "JEI index: %s categories, %s recipes, sample recipe ids %s | ingredients: %s | crafting recipes: %s",
            categories,
            recipes,
            sample,
            rt.getIngredientManager().getAllItemStacks().size(),
            craftingRecipes));
  }

  private static volatile boolean warnedNoRuntime;

  /**
   * Finds the recipe with the given registry id. JEI has no id lookup, so every visible category is
   * scanned and each recipe is asked for its registry name (only vanilla-backed recipes have one).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static RecipeMatch match(String recipeId) {
    IJeiRuntime rt = runtime;
    if (recipeId == null) {
      return null;
    }
    if (rt == null) {
      // Logged once: this is the difference between "JEI never handed over its runtime" and "its
      // index is empty", which otherwise look the same from a script.
      if (!warnedNoRuntime) {
        warnedNoRuntime = true;
        LOG.warning(
            "JEI is installed but has not handed over its runtime; recipe lookups answer with no"
                + " ghost slots until it does");
      }
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

  /**
   * Lays the recipe out the way JEI would draw it. JEI 10's drawable carries no slot views, so they
   * come off the internal layout that backs it.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Layout layout(RecipeMatch match) {
    IJeiRuntime rt = runtime;
    if (rt == null) {
      return null;
    }
    IRecipeLayoutDrawable drawable =
        rt.getRecipeManager()
            .createRecipeLayoutDrawable((IRecipeCategory) match.category(), match.recipe(), null);
    IRecipeLayoutInternal<?> internal = internalLayout(drawable);
    return internal == null ? null : new Layout(internal.getRecipeSlots().getView(), drawable);
  }

  /**
   * The layout behind a JEI 10 drawable. {@code createRecipeLayoutDrawable} hands back the *legacy*
   * adapter, which is not itself an internal layout: it wraps the real one in a private field (the
   * modern {@code RecipeLayout}). Read reflectively and cached, like the transfer manager below, so
   * a JEI that renames the field degrades to "no ghost slots" instead of failing the script.
   */
  private static IRecipeLayoutInternal<?> internalLayout(IRecipeLayoutDrawable drawable) {
    if (drawable instanceof IRecipeLayoutInternal<?> internal) {
      return internal;
    }
    try {
      Field field = internalLayoutField;
      Class<?> type = drawable.getClass();
      if (field == null || field.getDeclaringClass() != type) {
        field = type.getDeclaredField("recipeLayout");
        field.setAccessible(true);
        internalLayoutField = field;
      }
      return field.get(drawable) instanceof IRecipeLayoutInternal<?> internal ? internal : null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.log(Level.WARNING, "JEI 10: the drawable's recipe layout is not reachable", e);
      return null;
    }
  }

  private static IRecipeSlotsView slotsView(RecipeMatch match) {
    Layout layout = layout(match);
    return layout == null ? null : layout.slots();
  }

  /**
   * JEI 10 builds its transfer manager but never hands it out: the only reachable instance is the
   * one {@code IJeiRuntime#getRecipesGui()} holds. Read once and cached; a JEI that renames the
   * field yields {@code null} and fill is simply refused.
   */
  private static RecipeTransferManager transferManager() {
    IJeiRuntime rt = runtime;
    if (rt == null) {
      return null;
    }
    Object gui = rt.getRecipesGui();
    if (gui == null) {
      return null;
    }
    try {
      Field field = transferManagerField;
      if (field == null || !field.getDeclaringClass().isInstance(gui)) {
        field = gui.getClass().getDeclaredField("recipeTransferManager");
        field.setAccessible(true);
        transferManagerField = field;
      }
      return (RecipeTransferManager) field.get(gui);
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.log(Level.FINE, "JEI 10: recipe transfer manager is not reachable", e);
      return null;
    }
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
