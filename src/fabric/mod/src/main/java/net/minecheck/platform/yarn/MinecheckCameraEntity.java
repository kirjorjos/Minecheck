package net.minecheck.platform.yarn;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.Packet;
import net.minecraft.world.World;

/**
 * The client-only entity the camera looks through.
 *
 * <p>Its tick does nothing, on purpose: the platform moves it from the client tick hook, and no
 * vanilla system — gravity, fluid, collision, block effects — may alter where the camera is. A
 * marker type is used because the entity is never rendered and never sent to a server; everything
 * the renderer reads (position, rotation and the previous position it interpolates from) is written
 * by {@link YarnCameraPlatform}.
 */
final class MinecheckCameraEntity extends Entity {

  MinecheckCameraEntity(World world) {
    super(EntityType.MARKER, world);
    setNoGravity(true);
    this.noClip = true;
  }

  @Override
  public void tick() {}

  /**
   * Never sent anywhere: the camera exists on one client and has no server counterpart. Vanilla
   * declares this abstract on {@code Entity}, so it has to be answered even when the answer is
   * "impossible".
   */
  @Override
  public Packet<?> createSpawnPacket() {
    throw new UnsupportedOperationException("the camera entity is client-only");
  }

  /** Nothing worth saving: the pose lives in the platform and is rebuilt on attach. */
  @Override
  public void writeCustomDataToNbt(NbtCompound nbt) {}

  @Override
  public void readCustomDataFromNbt(NbtCompound nbt) {}

  /** No tracked data: the camera has no state a client and server would need to agree on. */
  @Override
  protected void initDataTracker() {}
}
