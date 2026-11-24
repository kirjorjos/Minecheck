package net.minecheck.loader.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.ForgeSubscribe;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * This line's client-only hooks.
 *
 * <p>1.4.7 ships no Mixin and no tick event bus, so the hooks the newer lines carry as mixins are
 * polled from the tick handler here:
 *
 * <ul>
 *   <li>the camera's F4 toggle and Ctrl+scroll shortcut, read from LWJGL directly because this line
 *       has no key-binding registry and no mouse event;
 *   <li>the idle-player rule, applied at the start of the client tick — the input object vanilla is
 *       about to read is cleared there, which is where the {@code Input#tick} injection sits on the
 *       newer lines.
 * </ul>
 *
 * <p>Scroll cannot be cancelled on this line, so a camera speed change also moves the hotbar by the
 * same notch. That is a difference the game imposes; the camera itself still answers.
 *
 * <p>The sound refusal lives here too: {@code PlaySoundEvent} names a client-only sound class, and
 * {@code muteAudio} is a client setting.
 */
public final class ForgeClientHooks {

  /** Forge's own "play nothing" answer, for {@code muteAudio}. */
  @ForgeSubscribe
  public void onPlaySound(PlaySoundEvent event) {
    ForgeSilentSound.refuse(event);
  }

  /** Polls the camera's input and clears the movement input vanilla is about to tick. */
  static void tickStart() {
    ForgeCameraPlatform.pollToggle(Keyboard.isKeyDown(Keyboard.KEY_F4));
    int wheel = Mouse.getDWheel();
    if (wheel != 0) {
      ForgeCameraPlatform.handleScroll(wheel);
    }
    if (!ForgeCameraPlatform.steering()) {
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
}
