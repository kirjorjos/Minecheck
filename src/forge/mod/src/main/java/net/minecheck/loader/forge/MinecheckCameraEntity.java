package net.minecheck.loader.forge;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

/**
 * The client-only entity the camera looks through, on the Forge 1.7.10 side.
 *
 * <p>Its tick does nothing, on purpose: the platform moves it from the client tick hook, and no
 * vanilla system — gravity, fluid, collision, block effects — may alter where the camera is. {@code
 * noClip} is set instead of a no-gravity flag, which this line does not have in the form the newer
 * lines use.
 *
 * <p>It is a player entity because that is what this line's renderer looks through. The client
 * holds its view entity in a field declared as a living entity, and more than one place in the
 * world renderer casts that field to a player outright — the field of view and the world render
 * itself — so anything else fails the first time a frame is drawn with the camera attached.
 * Vanilla's own view entity is a player for exactly that reason.
 *
 * <p>Nothing about it is a player in any other sense: it is invisible, never ticked, never sent to
 * a server, and everything the renderer reads is written by {@link ForgeCameraPlatform}.
 */
final class MinecheckCameraEntity extends EntityPlayer {

  /** The profile the camera's player carries; it is never sent anywhere. */
  private static final GameProfile PROFILE =
      new GameProfile(new java.util.UUID(0L, 0L), "MinecheckCamera");

  MinecheckCameraEntity(World world) {
    super(world, PROFILE);
    this.noClip = true;
    // The camera is the point the picture is taken from, never something standing in it.
    setInvisible(true);
  }

  @Override
  protected void entityInit() {
    super.entityInit();
  }

  @Override
  public void onUpdate() {}

  /**
   * The id the camera answers, without touching this line's private id field: the camera is never
   * looked up on the server, so nothing it is asked about can act on a world.
   */
  @Override
  public int getEntityId() {
    return ForgeCameraPlatform.CAMERA_ENTITY_ID;
  }

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
  public void addChatMessage(net.minecraft.util.IChatComponent message) {
    // Nothing is listening to the camera: it is a viewpoint, not a speaker.
  }

  @Override
  public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
    return false;
  }

  @Override
  public net.minecraft.util.ChunkCoordinates getCommandSenderPosition() {
    return new net.minecraft.util.ChunkCoordinates(
        (int) Math.floor(posX), (int) Math.floor(posY), (int) Math.floor(posZ));
  }
}
