package net.minecheck.loader.legacyfabric;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
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
 * field to a player outright, so anything else fails the first time a frame is drawn with the
 * camera attached. Vanilla's own view entity is a player for exactly that reason.
 *
 * <p>Nothing about it is a player in any other sense: it is never ticked, never sent to a server,
 * and everything the renderer reads is written by {@link LegacyFabricCameraPlatform}. It answers
 * the command-source questions a player entity has to answer as a non-command, so nothing it is
 * asked can act on a world.
 */
public final class LegacyCameraEntity extends PlayerEntity {

  /** The camera's name. 1.4.7 player entities carry a name, not a profile. */
  private static final String NAME = "MinecheckCamera";

  LegacyCameraEntity(World world) {
    super(world);
    this.username = NAME;
    // The client's camera is not an entity any server knows about, and this line's renderer skips
    // the entity with the camera's sentinel id rather than drawing it twice.
    this.id = -1;
    this.noClip = true;
  }

  @Override
  public void tick() {}

  @Override
  public void readCustomDataFromNbt(NbtCompound tag) {}

  @Override
  public void writeCustomDataToNbt(NbtCompound tag) {}

  // ------------------------------------------------------------------ command source

  @Override
  public net.minecraft.util.math.BlockPos getPosition() {
    return new net.minecraft.util.math.BlockPos(
        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
  }

  @Override
  public void method_3331(String message) {
    // Nothing is listening to the camera: it is a viewpoint, not a speaker.
  }

  @Override
  public boolean canUseCommand(int permissionLevel, String command) {
    return false;
  }
}
