package net.minecheck.loader.legacyfabric;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The client-only entity the camera looks through, on the Legacy Fabric side.
 *
 * <p>Its tick does nothing, on purpose: the platform moves it from the client tick hook, and no
 * vanilla system — gravity, fluid, collision, block effects — may alter where the camera is. {@code
 * noClip} is set instead of a no-gravity flag, which this line does not have in the form the newer
 * lines use.
 *
 * <p>It is a player entity because that is what this line's renderer looks through. The client
 * holds its view entity in a living field, and more than one place in the world renderer casts that
 * field to a player outright — the field of view and the world render itself — so anything else
 * fails the first time a frame is drawn with the camera attached. Vanilla's own view entity is a
 * player for exactly that reason.
 *
 * <p>Nothing about it is a player in any other sense: it is invisible, never ticked, never sent to
 * a server, and everything the renderer reads is written by {@link LegacyFabricCameraPlatform}. It
 * answers the command-source questions a player entity has to answer as a non-command, so nothing
 * it is asked can act on a world.
 */
public final class LegacyCameraEntity extends PlayerEntity {

  /** The profile the camera's player carries; it is never sent anywhere. */
  private static final GameProfile PROFILE =
      new GameProfile(new java.util.UUID(0L, 0L), "MinecheckCamera");

  LegacyCameraEntity(World world) {
    super(world, PROFILE);
    this.noClip = true;
    // The camera is the point the picture is taken from, never something standing in it.
    setInvisible(true);
  }

  /**
   * Registers the player's tracked data, then stops there.
   *
   * <p>A camera needs none of it for itself, but the superclass constructor writes its health
   * through the tracker before this object is finished, so an empty override is a null entry and a
   * crash on construction.
   */
  @Override
  protected void initDataTracker() {
    super.initDataTracker();
  }

  @Override
  public void tick() {}

  @Override
  public void readCustomDataFromNbt(NbtCompound tag) {}

  @Override
  public void writeCustomDataToNbt(NbtCompound tag) {}

  // ------------------------------------------------------------------ command source

  @Override
  public String getTranslationKey() {
    return "entity.minecraft.player";
  }

  @Override
  public Text getName() {
    return new LiteralText("MinecheckCamera");
  }

  @Override
  public void sendMessage(Text message) {
    // Nothing is listening to the camera: it is a viewpoint, not a speaker.
  }

  @Override
  public boolean canUseCommand(int permissionLevel, String command) {
    return false;
  }

  @Override
  public BlockPos getPosition() {
    // This line names an entity's position x/y/z, not posX/posY/posZ.
    return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
  }

  @Override
  public World getWorld() {
    return world;
  }
}
