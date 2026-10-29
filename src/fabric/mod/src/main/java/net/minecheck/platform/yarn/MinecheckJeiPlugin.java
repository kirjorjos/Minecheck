package net.minecheck.platform.yarn;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.util.Identifier;

/**
 * Registers Minecheck with JEI and hands JEI's runtime to {@link JeiRecipeViewer}.
 *
 * <p>JEI loads this class itself (the {@code jei_mod_plugin} entrypoint on Fabric, the {@link
 * JeiPlugin} annotation on Forge), so nothing here is touched when JEI is absent. It must stay
 * public with a public no-argument constructor: JEI instantiates plugins reflectively.
 */
@JeiPlugin
public final class MinecheckJeiPlugin implements IModPlugin {

  @Override
  public Identifier getPluginUid() {
    return new Identifier("minecheck", "jei");
  }

  @Override
  public void onRuntimeAvailable(IJeiRuntime runtime) {
    JeiRecipeViewer.setRuntime(runtime);
  }
}
