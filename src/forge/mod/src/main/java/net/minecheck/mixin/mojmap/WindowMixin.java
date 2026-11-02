package net.minecheck.mixin.mojmap;

import com.mojang.blaze3d.platform.Window;
import net.minecheck.common.config.GameWindowPolicy;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Forge/Mojmap twin of {@code net.minecheck.mixin.yarn.WindowMixin}: creates the client's
 * window unmapped when {@code hideGameWindow} says so. The target is the same class in different
 * mappings, so the reasoning lives in the yarn mixin's javadoc — in short, {@code Window}'s
 * constructor resets every GLFW hint and then creates the window a few instructions later, leaving
 * no seam outside the constructor itself.
 *
 * <p>Registered through the {@code MixinConfigs} manifest attribute (see the module's build
 * script), client-only, because a dedicated server has no window to hide.
 */
@Mixin(Window.class)
public class WindowMixin {

  @Inject(
      method = "<init>",
      at =
          @At(
              value = "INVOKE",
              target = "Lorg/lwjgl/glfw/GLFW;glfwDefaultWindowHints()V",
              shift = At.Shift.AFTER))
  private void minecheck$hideWindowAtCreation(CallbackInfo callback) {
    if (GameWindowPolicy.hide(FMLPaths.CONFIGDIR.get())) {
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }
  }

  /**
   * Hides the window the moment its constructor returns.
   *
   * <p>This is where the Mojmap twin cannot follow the Yarn one: the Yarn constructor calls {@code
   * glfwCreateWindow} itself, so the handle can be hidden the instant it exists, but Mojmap's
   * constructor never contains that call — the window arrives through a helper — so there is no
   * call site inside {@code <init>} to redirect. Hiding at the end of the constructor reaches the
   * same state without naming whatever creates it, and it reads the handle through the public
   * accessor, so nothing here depends on a field name in either mapping.
   *
   * <p>What it protects against is the hint above being ignored: GLFW drops window hints set before
   * the library is initialised, and nothing in Minecheck guarantees the game initialised it before
   * this constructor's hint block. The constructor has not swapped a frame yet, so a window hidden
   * here was never seen.
   */
  @Inject(method = "<init>", at = @At("TAIL"))
  private void minecheck$hideWindowAfterCreation(CallbackInfo callback) {
    if (!GameWindowPolicy.hide(FMLPaths.CONFIGDIR.get())) {
      return;
    }
    long handle = ((Window) (Object) this).getWindow();
    if (handle != 0L) {
      GLFW.glfwHideWindow(handle);
    }
  }
}
