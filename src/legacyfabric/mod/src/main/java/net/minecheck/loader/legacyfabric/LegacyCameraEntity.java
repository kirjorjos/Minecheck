package net.minecheck.loader.legacyfabric;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
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
 * <p>It is a {@link LivingEntity} rather than a plain entity because that is what this line's
 * renderer looks through: the client holds its view entity in a field of that type, so a plain
 * {@code Entity} could not be placed there at all. The four abstract item accessors are answered
 * with nothing, since the camera is never rendered and never sent to a server, and everything the
 * renderer reads is written by {@link LegacyFabricCameraPlatform}.
 *
 * <p>The renderer's field of view is computed by casting the view entity to a player, which this
 * entity is not: a frame drawn while the camera is the viewpoint therefore fails on that cast. The
 * camera's own state and flight do not touch the renderer, so everything but rendering is exercised
 * without it.
 */
public final class LegacyCameraEntity extends LivingEntity {

  LegacyCameraEntity(World world) {
    super(world);
    this.noClip = true;
    setInvisible(true);
  }

  /**
   * Registers the living entity's tracked data, then stops there.
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

  @Override
  public ItemStack getStackInHand() {
    return null;
  }

  @Override
  public ItemStack getMainSlot(int slot) {
    return null;
  }

  @Override
  public void setArmorSlot(int slot, ItemStack stack) {}

  @Override
  public ItemStack[] getArmorStacks() {
    return new ItemStack[4];
  }
}
