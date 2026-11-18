package net.minecheck.loader.forge;

import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * This line's client-only input hooks.
 *
 * <p>Forge 28 ships no Mixin at all — its ModLauncher has no mixin transformation and its launcher
 * jar contains none of it — so the two hooks the newer lines carry as mixins are native Forge
 * events here: the idle-player rule is {@link net.minecraftforge.client.event.InputUpdateEvent}
 * (the player has just filled the movement input vanilla is about to tick with, which is where the
 * {@code Input#tick} injection sat) and the camera's Ctrl+scroll shortcut is {@link
 * net.minecraftforge.client.event.InputEvent.MouseScrollEvent} (which fires before the notch
 * becomes a hotbar change, so cancelling it keeps the hotbar still).
 *
 * <p>Registered by {@link ForgeEntrypoint} only on a physical client, and kept in its own class on
 * purpose: the input event's type names a client-only class, and a server that merely loads a class
 * mentioning it dies in Forge's dist cleaner.
 */
final class ForgeClientHooks {

  @SubscribeEvent
  public void onInputUpdate(net.minecraftforge.client.event.InputUpdateEvent event) {
    if (!ForgeCameraPlatform.steering()) {
      return;
    }
    net.minecraft.client.player.Input input = event.getMovementInput();
    input.up = false;
    input.down = false;
    input.left = false;
    input.right = false;
    input.jumping = false;
    input.sneakKeyDown = false;
    input.forwardImpulse = 0.0f;
    input.leftImpulse = 0.0f;
  }

  @SubscribeEvent
  public void onMouseScroll(net.minecraftforge.client.event.InputEvent.MouseScrollEvent event) {
    if (ForgeCameraPlatform.handleScroll(event.getScrollDelta())) {
      event.setCanceled(true);
    }
  }
}
