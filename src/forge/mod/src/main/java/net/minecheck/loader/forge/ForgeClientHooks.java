package net.minecheck.loader.forge;

import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * This line's client-only hooks.
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
 *
 * <p>The sound refusal lives here too, for the same reason: {@code PlaySoundEvent} names a
 * client-only sound instance, and {@code muteAudio} is a client setting. Forge 28 posts no event a
 * mod can receive before the engine starts, so a muted run is a refused play plus a closed device
 * rather than a refused open — see {@link ForgeSilentSound}.
 */
public final class ForgeClientHooks {

  /**
   * Forge's own "play nothing" answer, for {@code muteAudio}. Every sound passes through here
   * before the engine mixes it, so refusing it is what makes a muted run silent — see {@link
   * ForgeSilentSound} for why this loader cannot hook the device itself.
   */
  @SubscribeEvent
  public void onPlaySound(net.minecraftforge.client.event.sound.PlaySoundEvent event) {
    ForgeSilentSound.refuse(event);
  }

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
