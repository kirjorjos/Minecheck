package net.minecheck.loader.legacyfabric;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/**
 * The client-only entity the camera looks through, on the Legacy Fabric side.
 *
 * <p>Its tick does nothing, on purpose: the platform moves it from the client tick hook, and no
 * vanilla system — gravity, fluid, collision, block effects — may alter where the camera is. {@code
 * noClip} is set instead of a no-gravity flag, which this line does not have in the form the newer
 * lines use. A plain {@link Entity} is used because the entity is never rendered and never sent to
 * a server; everything the renderer reads is written by {@link LegacyFabricCameraPlatform}.
 */
final class LegacyCameraEntity extends Entity {

  LegacyCameraEntity(World world) {
    super(world);
    this.noClip = true;
  }

  @Override
  protected void initDataTracker() {}

  @Override
  public void tick() {}

  @Override
  protected void readCustomDataFromNbt(NbtCompound tag) {}

  @Override
  protected void writeCustomDataToNbt(NbtCompound tag) {}
}
