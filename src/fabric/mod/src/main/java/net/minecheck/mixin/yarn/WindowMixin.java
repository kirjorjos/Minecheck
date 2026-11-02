package net.minecheck.mixin.yarn;

import net.fabricmc.loader.api.FabricLoader;
import net.minecheck.common.config.GameWindowPolicy;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creates the client's window without ever mapping it on screen, when {@code hideGameWindow} says
 * so.
 *
 * <p>The window itself cannot be skipped — it carries the GL context every frame is drawn into and
 * every capture is read back from — so "no window" means "never made visible". That has to be a
 * creation-time attribute: {@code Window}'s constructor starts with {@code
 * glfwDefaultWindowHints()}, which resets every hint to its default ({@code GLFW_VISIBLE}
 * included), and then sets its own and calls {@code glfwCreateWindow} a few instructions later.
 * There is no seam between the reset and the create for a mod to use from outside, which is why
 * this hook exists at all — and it is placed immediately after the reset precisely so the hint
 * cannot be wiped.
 *
 * <p>Minecraft never calls {@code glfwShowWindow} or {@code glfwHideWindow} on 1.18.2, so the
 * window stays unmapped for the life of the process. Nothing else notices: input goes through
 * Minecraft's own handlers rather than OS focus, and captures read the framebuffer, not the screen.
 *
 * <p>The handler takes no constructor arguments on purpose — a subset is matched by position, and
 * none of {@code WindowSettings} is needed to answer the question.
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
    if (GameWindowPolicy.hide(FabricLoader.getInstance().getConfigDir())) {
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }
  }

  /**
   * Hides the window the instant its handle exists.
   *
   * <p>The hint above is the clean path and the one that does the work. This is the belt to that
   * brace: GLFW ignores window hints set while the library is not initialised, and nothing in
   * Minecheck guarantees the game initialised it before this constructor's hint block. Hiding the
   * handle the create call returns cannot be dropped that way, and it costs nothing a person can
   * see — the constructor has not swapped a frame yet, so there is no mapped window to flash, focus
   * or notice.
   */
  @Redirect(
      method = "<init>",
      at =
          @At(
              value = "INVOKE",
              target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
  private long minecheck$createWindowHidden(
      int width, int height, CharSequence title, long monitor, long share) {
    long handle = GLFW.glfwCreateWindow(width, height, title, monitor, share);
    if (handle != 0L && GameWindowPolicy.hide(FabricLoader.getInstance().getConfigDir())) {
      GLFW.glfwHideWindow(handle);
    }
    return handle;
  }
}
