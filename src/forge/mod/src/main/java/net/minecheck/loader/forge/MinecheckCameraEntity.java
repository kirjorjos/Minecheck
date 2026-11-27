package net.minecheck.loader.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * The client-only entity the camera looks through, on the mojmap side.
 *
 * <p>Its tick does nothing, on purpose: the platform moves it from the client tick hook, and no
 * vanilla system — gravity, fluid, collision, block effects — may alter where the camera is. A
 * marker type is used because the entity is never rendered and never sent to a server; everything
 * the renderer reads is written by {@link ForgeCameraPlatform}.
 */
final class MinecheckCameraEntity extends Entity {

  MinecheckCameraEntity(Level level) {
    super(EntityType.MARKER, level);
    setNoGravity(true);
    this.noPhysics = true;
  }

  @Override
  public void tick() {}

  /** Nothing can hurt a camera: 1.21.2 made hurtServer abstract on Entity. */
  @Override
  public boolean hurtServer(
      net.minecraft.server.level.ServerLevel level,
      net.minecraft.world.damagesource.DamageSource source,
      float amount) {
    return false;
  }

  @Override
  protected void defineSynchedData(
      net.minecraft.network.syncher.SynchedEntityData.Builder builder) {}

  @Override
  protected void readAdditionalSaveData(CompoundTag tag) {}

  @Override
  protected void addAdditionalSaveData(CompoundTag tag) {}
}
