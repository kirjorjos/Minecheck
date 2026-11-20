package net.minecheck.loader.forge;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;

/**
 * This line's client-only hooks.
 *
 * <p>1.7.10 ships no Mixin at all, so the hooks the newer lines carry as mixins are native Forge
 * events here. The camera's Ctrl+scroll shortcut is {@link MouseEvent} (which fires before the
 * notch becomes a hotbar change, so cancelling it keeps the hotbar still).
 *
 * <p>The idle-player rule has no event of its own on this line — {@code InputUpdateEvent} arrives
 * with 1.8 — so it is applied at the start of the client tick instead: the input object vanilla is
 * about to read is cleared there, which is where the {@code Input#tick} injection sits on the newer
 * lines. A tick's start is before the player moves, so the body still never leaves the pose the
 * server last saw.
 *
 * <p>Registered by {@link ForgeEntrypoint} only on a physical client, and kept in its own class on
 * purpose: these types name client-only classes, and a server that merely loads a class mentioning
 * them dies in Forge's dist cleaner.
 *
 * <p>The sound refusal lives here too, for the same reason: {@code PlaySoundEvent} names a
 * client-only sound class, and {@code muteAudio} is a client setting.
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

  /** Clears the movement input vanilla is about to tick, while the camera holds the body still. */
  @SubscribeEvent
  public void onClientTick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.START || !ForgeCameraPlatform.steering()) {
      return;
    }
    EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
    if (player == null) {
      return;
    }
    MovementInput input = player.movementInput;
    if (input == null) {
      return;
    }
    input.moveForward = 0.0f;
    input.moveStrafe = 0.0f;
    input.jump = false;
    input.sneak = false;
  }

  @SubscribeEvent
  public void onMouseScroll(MouseEvent event) {
    if (ForgeCameraPlatform.handleScroll(event.dwheel)) {
      event.setCanceled(true);
    }
  }
}
