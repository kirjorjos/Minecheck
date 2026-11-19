package net.minecheck.loader.forge;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

/**
 * The client-only entity the camera looks through, on the 1.12.2 side.
 *
 * <p>Its tick does nothing, on purpose: the platform moves it from the client tick hook, and no
 * vanilla system — gravity, fluid, collision, block effects — may alter where the camera is. {@code
 * noClip} is set instead of 1.13's no-gravity flag, which 1.12.2 does not have. A plain {@link
 * Entity} is used because the entity is never rendered and never sent to a server; everything the
 * renderer reads is written by {@link ForgeCameraPlatform}.
 */
final class MinecheckCameraEntity extends Entity {

  MinecheckCameraEntity(World world) {
    super(world);
    setSize(0.1f, 0.1f);
    this.noClip = true;
  }

  @Override
  protected void entityInit() {}

  @Override
  public void onUpdate() {}

  @Override
  protected void readEntityFromNBT(NBTTagCompound tag) {}

  @Override
  protected void writeEntityToNBT(NBTTagCompound tag) {}
}
