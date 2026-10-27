package net.minecheck.loader.forge;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers Minecheck with JEI and hands JEI's runtime to {@link ForgeJeiRecipeViewer}.
 *
 * <p>Forge discovers plugins by scanning every mod jar for the {@link JeiPlugin} annotation, so
 * nothing here is touched when JEI is absent. It must stay public with a public no-argument
 * constructor: JEI instantiates plugins reflectively.
 */
@JeiPlugin
public final class ForgeJeiPlugin implements IModPlugin {

  @Override
  public ResourceLocation getPluginUid() {
    return new ResourceLocation("minecheck", "jei");
  }

  @Override
  public void onRuntimeAvailable(IJeiRuntime runtime) {
    ForgeJeiRecipeViewer.setRuntime(runtime);
  }

  @Override
  public void onRuntimeUnavailable() {
    ForgeJeiRecipeViewer.setRuntime(null);
  }
}
