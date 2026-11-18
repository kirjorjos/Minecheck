package net.minecheck.loader.forge;

import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * This line's client-only hooks.
 *
 * <p>1.12.2 ships no Mixin at all, so the two hooks the newer lines carry as mixins are native
 * Forge events here: the idle-player rule is {@link InputUpdateEvent} (the player has just filled
 * the movement input vanilla is about to tick with, which is where the {@code Input#tick} injection
 * sat) and the camera's Ctrl+scroll shortcut is {@link MouseEvent} (which fires before the notch
 * becomes a hotbar change, so cancelling it keeps the hotbar still).
 *
 * <p>Registered by {@link ForgeEntrypoint} only on a physical client, and kept in its own class on
 * purpose: the input event's type names a client-only class, and a server that merely loads a class
 * mentioning it dies in Forge's dist cleaner.
 *
 * <p>The sound refusal lives here too, for the same reason: {@code PlaySoundEvent} names a
 * client-only sound instance, and {@code muteAudio} is a client setting.
 */
public final class ForgeClientHooks {

  /**
   * Forge's own "play nothing" answer, for {@code muteAudio}. Every sound passes through here
   * before the engine mixes it, so refusing it is what makes a muted run silent.
   */
  @SubscribeEvent
  public void onPlaySound(PlaySoundEvent event) {
    ForgeSilentSound.refuse(event);
  }

  @SubscribeEvent
  public void onInputUpdate(InputUpdateEvent event) {
    if (!ForgeCameraPlatform.steering()) {
      return;
    }
    MovementInput input = event.getMovementInput();
    input.moveForward = 0.0f;
    input.moveStrafe = 0.0f;
    input.jump = false;
    input.sneak = false;
  }

  @SubscribeEvent
  public void onMouseScroll(MouseEvent event) {
    if (ForgeCameraPlatform.handleScroll(event.getDwheel())) {
      event.setCanceled(true);
    }
  }
}
