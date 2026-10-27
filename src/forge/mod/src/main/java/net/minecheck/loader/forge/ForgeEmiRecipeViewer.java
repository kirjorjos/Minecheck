package net.minecheck.loader.forge;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.platform.api.GhostSlot;
import net.minecheck.platform.api.RecipeViewer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

/**
 * EMI-backed {@link RecipeViewer} for Forge.
 *
 * <p>EMI is a soft dependency: this class is only instantiated when the mod is loaded, and every
 * EMI type it touches is used inside method bodies, so an absent EMI is never resolved.
 */
public final class ForgeEmiRecipeViewer implements RecipeViewer {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  @Override
  public String id() {
    return "emi";
  }

  @Override
  public boolean isAvailable() {
    try {
      return EmiApi.getRecipeManager() != null;
    } catch (Throwable t) {
      return false;
    }
  }

  @Override
  public List<GhostSlot> recipeSlots(String recipeId) {
    EmiRecipe recipe = recipe(recipeId);
    if (recipe == null) {
      return List.of();
    }
    List<EmiIngredient> inputs = recipe.getInputs();
    List<GhostSlot> slots = new ArrayList<>(inputs.size());
    for (int i = 0; i < inputs.size(); i++) {
      List<String> ids = new ArrayList<>();
      for (EmiStack stack : inputs.get(i).getEmiStacks()) {
        if (!stack.isEmpty()) {
          ResourceLocation id = stack.getId();
          if (id != null && !ids.contains(id.toString())) {
            ids.add(id.toString());
          }
        }
      }
      slots.add(new GhostSlot(i, ids));
    }
    return slots;
  }

  @Override
  public boolean fillFromRecipe(String recipeId, int maxItems) {
    EmiRecipe recipe = recipe(recipeId);
    if (recipe == null) {
      return false;
    }
    AbstractContainerScreen<?> screen = EmiApi.getHandledScreen();
    if (screen == null) {
      return false;
    }
    try {
      return dev.emi.emi.registry.EmiRecipeFiller.performFill(
          recipe,
          screen,
          EmiCraftContext.Type.FILL_BUTTON,
          EmiCraftContext.Destination.NONE,
          maxItems);
    } catch (Throwable t) {
      LOG.log(Level.WARNING, String.format("EMI could not fill from recipe %s", recipeId), t);
      return false;
    }
  }

  private static EmiRecipe recipe(String recipeId) {
    try {
      return EmiApi.getRecipeManager().getRecipe(new ResourceLocation(recipeId));
    } catch (Throwable t) {
      LOG.log(Level.FINE, String.format("EMI recipe lookup failed for %s", recipeId), t);
      return null;
    }
  }
}
