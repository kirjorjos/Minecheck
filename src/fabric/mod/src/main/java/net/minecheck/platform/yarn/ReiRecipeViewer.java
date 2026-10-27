package net.minecheck.platform.yarn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecheck.platform.api.GhostSlot;
import net.minecheck.platform.api.RecipeViewer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

/**
 * REI-backed {@link RecipeViewer}.
 *
 * <p>REI is a soft dependency: this class is only instantiated when the mod is loaded, and every
 * REI type it touches is used inside method bodies, so an absent REI is never resolved. (REI's mod
 * id is {@code roughlyenoughitems}, not {@code rei} — the viewer id it reports here is the short
 * one scripts use.)
 */
public final class ReiRecipeViewer implements RecipeViewer {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  @Override
  public String id() {
    return "rei";
  }

  @Override
  public boolean isAvailable() {
    try {
      return DisplayRegistry.getInstance() != null;
    } catch (Throwable t) {
      return false;
    }
  }

  @Override
  public List<GhostSlot> recipeSlots(String recipeId) {
    try {
      Display display = display(recipeId);
      if (display == null) {
        return List.of();
      }
      List<EntryIngredient> inputs = display.getInputEntries();
      List<GhostSlot> slots = new ArrayList<>(inputs.size());
      for (int i = 0; i < inputs.size(); i++) {
        slots.add(new GhostSlot(i, itemIds(inputs.get(i))));
      }
      probe(inputs.size());
      return slots;
    } catch (Throwable t) {
      LOG.log(
          Level.WARNING,
          String.format("REI could not read the ghost slots of recipe %s", recipeId),
          t);
      return List.of();
    }
  }

  @Override
  public boolean fillFromRecipe(String recipeId, int maxItems) {
    try {
      Display display = display(recipeId);
      if (display == null) {
        return false;
      }
      MinecraftClient client = MinecraftClient.getInstance();
      if (client == null || !(client.currentScreen instanceof HandledScreen<?> screen)) {
        return false;
      }
      // REI transfers a whole recipe; it has no per-transfer item cap, so maxItems cannot narrow
      // it.
      TransferHandler.Context context =
          TransferHandler.Context.create(true, false, screen, display);
      List<TransferHandler> handlers = new ArrayList<>();
      TransferHandlerRegistry.getInstance().forEach(handlers::add);
      // "higher priorities will be called first" (TransferHandler#getPriority).
      handlers.sort(Comparator.comparingDouble(TransferHandler::getPriority).reversed());
      for (TransferHandler handler : handlers) {
        if (!handler.checkApplicable(context).isApplicable()) {
          continue;
        }
        TransferHandler.Result result = handler.handle(context);
        return result != null && result.isSuccessful();
      }
      return false;
    } catch (Throwable t) {
      LOG.log(Level.WARNING, String.format("REI could not fill from recipe %s", recipeId), t);
      return false;
    }
  }

  /** One-shot INFO diagnostic of what REI's index holds, logged on the first recipe read. */
  private static void probe(int inputCount) {
    if (probed) {
      return;
    }
    probed = true;
    int displays = 0;
    try {
      for (List<Display> group : DisplayRegistry.getInstance().getAll().values()) {
        displays += group.size();
      }
    } catch (Throwable t) {
      LOG.log(Level.FINE, "REI display count failed", t);
    }
    LOG.info(
        String.format("REI index: %s displays | first read: %s input slots", displays, inputCount));
  }

  private static volatile boolean probed;

  /**
   * Finds the display with the given recipe id. REI displays carry their own location ({@link
   * Display#getDisplayLocation()}), so this is a direct match rather than JEI's category scan.
   */
  private static Display display(String recipeId) {
    if (recipeId == null) {
      return null;
    }
    for (List<Display> group : DisplayRegistry.getInstance().getAll().values()) {
      for (Display display : group) {
        Optional<Identifier> location = display.getDisplayLocation();
        if (location.isPresent() && recipeId.equals(location.get().toString())) {
          return display;
        }
      }
    }
    return null;
  }

  /** The item ids a ghost slot accepts, tags already expanded to their members by REI. */
  private static List<String> itemIds(EntryIngredient ingredient) {
    List<String> ids = new ArrayList<>();
    for (EntryStack<?> stack : ingredient) {
      if (stack.isEmpty()) {
        continue;
      }
      Object value = stack.getValue();
      if (!(value instanceof ItemStack item) || item.isEmpty()) {
        continue;
      }
      Identifier id = Registry.ITEM.getId(item.getItem());
      if (id != null && !ids.contains(id.toString())) {
        ids.add(id.toString());
      }
    }
    return ids;
  }
}
