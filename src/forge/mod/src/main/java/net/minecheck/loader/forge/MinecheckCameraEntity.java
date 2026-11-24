package net.minecheck.loader.forge;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * The client-only entity the camera looks through, on the Forge 1.4.7 side.
 *
 * <p>Its tick does nothing, on purpose: the platform moves it from the client tick hook, and no
 * vanilla system — gravity, fluid, collision, block effects — may alter where the camera is. {@code
 * noClip} is set instead of a no-gravity flag, which this line does not have in the form the newer
 * lines use.
 *
 * <p>It is a player entity because that is what this line's renderer looks through. The client
 * holds its view entity in a field declared as a living entity, and the world renderer casts that
 * field to a player for the field of view, so anything else fails the first time a frame is drawn
 * with the camera attached. 1.4.7 identifies a player by a plain username, so that is what it
 * carries.
 *
 * <p>Nothing about it is a player in any other sense: it is invisible, never ticked, never sent to
 * a server, and everything the renderer reads is written by {@link ForgeCameraPlatform}.
 */
final class MinecheckCameraEntity extends EntityPlayer {

  MinecheckCameraEntity(World world) {
    super(world);
    this.username = "MinecheckCamera";
    this.noClip = true;
    this.entityId = ForgeCameraPlatform.CAMERA_ENTITY_ID;
  }

  @Override
  protected void entityInit() {
    super.entityInit();
  }

  @Override
  public void onUpdate() {}

  @Override
  public void readEntityFromNBT(NBTTagCompound tag) {}

  @Override
  public void writeEntityToNBT(NBTTagCompound tag) {}

  // ------------------------------------------------------------------ command source

  @Override
  public String getCommandSenderName() {
    return "MinecheckCamera";
  }

  @Override
  public void sendChatToPlayer(String message) {
    // Nothing is listening to the camera: it is a viewpoint, not a speaker.
  }

  @Override
  public String translateString(String key, Object... args) {
    return key;
  }

  @Override
  public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
    return false;
  }

  @Override
  public ChunkCoordinates getPlayerCoordinates() {
    return new ChunkCoordinates(
        (int) Math.floor(posX), (int) Math.floor(posY), (int) Math.floor(posZ));
  }
}
