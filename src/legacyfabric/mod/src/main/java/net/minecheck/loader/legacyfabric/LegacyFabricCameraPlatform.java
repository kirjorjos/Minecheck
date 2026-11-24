package net.minecheck.loader.legacyfabric;

import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.common.camera.CameraController;
import net.minecheck.common.camera.CameraMode;
import net.minecheck.common.camera.CameraMovement;
import net.minecheck.common.camera.CameraPose;
import net.minecheck.common.config.CameraPolicy;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.config.JsonConfigManager;
import net.minecheck.platform.api.CameraPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.Input;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.ControllablePlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The camera on the Legacy Fabric side.
 *
 * <p>It owns the one camera entity, the toggle key and the per-tick flight, and delegates every
 * decision about <em>where</em> the camera is to {@link CameraController} in {@code :common}, which
 * is where the flight model is tested. This class only reads keys, publishes a pose to the entity
 * and puts the entity in the level.
 *
 * <p>Legacy Fabric ships no key-binding registry, so the toggle is a raw key poll rather than a
 * binding: {@code F4} is read from LWJGL directly and edge-detected here. Player input is the same
 * story from the other side — the zeroing the newer lines do from a mixin on the input tick is
 * {@code MovementInputMixin}'s job.
 *
 * <p>1.7.10 has no camera-entity accessors: the entity the renderer looks through is a {@code
 * LivingEntity} field, and the player is a {@code ControllablePlayerEntity} field. Both are written
 * directly here, which is why the camera entity is a {@link LegacyCameraEntity} — this line's
 * render view entity cannot be a plain {@code Entity}.
 */
public final class LegacyFabricCameraPlatform implements CameraPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** The client-only entity id: negative, so it cannot collide with a server-assigned one. */
  private static final int CAMERA_ENTITY_ID = -1;

  /** The key that attaches and detaches the camera, since there is no binding to register. */
  private static final int TOGGLE_KEY = Keyboard.KEY_F4;

  /** Used when the config cannot be read at construction: the documented default speed. */
  private static final double DEFAULT_SPEED = 1.0;

  /** The installed platform, for the input mixin: it asks whether the human is steering. */
  private static volatile LegacyFabricCameraPlatform current;

  private final CameraMode mode;
  private final CameraController controller;

  private LegacyCameraEntity camera;

  /**
   * The rotation the player is held at while the camera steers: the last one the server saw, so the
   * body never turns and mouse look has somewhere to be measured from.
   */
  private double baseYaw;

  private double basePitch;

  /** Whether the toggle key was down last tick, so one press is one toggle. */
  private boolean toggleWasDown;

  LegacyFabricCameraPlatform(Path configDir) {
    this.mode = CameraPolicy.mode(configDir);

    double horizontal = DEFAULT_SPEED;
    double vertical = DEFAULT_SPEED;
    if (mode.enabled()) {
      try {
        ConfigData config = new JsonConfigManager(configDir).load();
        horizontal = config.cameraHorizontalSpeed();
        vertical = config.cameraVerticalSpeed();
      } catch (Exception e) {
        LOG.log(
            Level.WARNING,
            "Minecheck: could not read the camera's speed settings — using the default",
            e);
      }
    }
    this.controller = new CameraController(mode, horizontal, vertical);
    if (mode.enabled()) {
      current = this;
    }
  }

  /**
   * Whether the human's mouse and keys are the camera's this tick. The movement hook asks this to
   * keep the player idle, so it must answer the same way the flight does.
   */
  public static boolean steering() {
    LegacyFabricCameraPlatform platform = current;
    return platform != null
        && platform.active()
        && platform.humanInputAllowed()
        && mc().currentScreen == null;
  }

  private static Minecraft mc() {
    return Minecraft.getMinecraft();
  }

  @Override
  public void registerBindings() {
    if (!mode.enabled()) {
      return;
    }
    LOG.info("Minecheck: camera installed — F4 toggles it (no binding registry on this loader)");
  }

  @Override
  public boolean installed() {
    return mode.enabled();
  }

  @Override
  public boolean active() {
    return camera != null;
  }

  @Override
  public void toggle() {
    if (!mode.enabled()) {
      return;
    }
    if (camera != null) {
      detach();
    } else {
      attach();
    }
  }

  @Override
  public void tick() {
    if (!mode.enabled()) {
      return;
    }
    boolean allowed = humanInputAllowed();
    boolean down = Keyboard.isKeyDown(TOGGLE_KEY);
    if (down && !toggleWasDown && allowed) {
      toggle();
    }
    toggleWasDown = down;
    if (camera == null) {
      return;
    }
    Minecraft mc = mc();
    ControllablePlayerEntity player = mc.playerEntity;
    if (player == null || mc.world == null || camera.world != mc.world) {
      detach();
      return;
    }
    // Ctrl+scroll changes the flight speed a notch at a time, which is where the newer lines'
    // mouse hook does it: with no event to cancel here, the hotbar also moves, which is the one
    // visible difference on this loader.
    int wheel = Mouse.getDWheel();
    if (wheel != 0
        && allowed
        && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
            || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))) {
      controller.adjustSpeed(wheel > 0 ? 1 : -1);
    }
    // Re-asserted every tick: the renderer's view entity is read fresh each frame, and a respawn or
    // a dimension change may have pointed it somewhere else since the last tick.
    mc.cameraEntity = camera;
    steer(player, allowed);
    CameraPose moved = controller.tick(inputs(player), allowed);
    if (moved != null) {
      place(camera, moved);
    }
  }

  @Override
  public void move(double forward, double up, double right) {
    if (camera == null) {
      return;
    }
    if (controller.move(forward, up, right)) {
      place(camera, controller.pose());
    }
  }

  @Override
  public State pose() {
    CameraPose pose = controller.pose();
    if (pose == null) {
      return null;
    }
    return new State(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
  }

  /** The keys the camera reads this tick, taken from the input vanilla already computed. */
  private static CameraMovement.Inputs inputs(ControllablePlayerEntity player) {
    // The input object is the local player's, so the cast is what reaches it: this line keeps it on
    // the client-side subclass, not on the player the field is typed as.
    if (!(player instanceof ClientPlayerEntity)) {
      return CameraMovement.Inputs.none();
    }
    Input input = ((ClientPlayerEntity) player).input;
    if (input == null) {
      return CameraMovement.Inputs.none();
    }
    // This line's input is two axes rather than four flags: the forward axis is positive for
    // forward and the sideways axis is positive for left, which is the mapping the booleans carry.
    return new CameraMovement.Inputs(
        input.movementForward > 0.0f,
        input.movementForward < 0.0f,
        input.movementSideways > 0.0f,
        input.movementSideways < 0.0f,
        input.jumping,
        input.sneaking,
        Keyboard.isKeyDown(Keyboard.KEY_LCONTROL));
  }

  /**
   * Whether the human's keys are the camera's this tick: only in {@code assist}, and only while
   * there is a window someone can be looking at. A headless client has none, so this is false there
   * by construction.
   */
  private boolean humanInputAllowed() {
    return mode == CameraMode.ASSIST && mc().currentScreen == null;
  }

  /**
   * Holds the body's rotation at what the server last saw, and lets mouse look steer the camera:
   * vanilla turns the player by the same movement the camera reads, so the two must be separated or
   * the body turns with the camera and the server sees a rotating player.
   */
  private void steer(ControllablePlayerEntity player, boolean allowed) {
    float yawDelta = MathHelper.wrapDegrees(player.yaw - (float) baseYaw);
    float pitchDelta = player.pitch - (float) basePitch;
    player.yaw = (float) baseYaw;
    player.pitch = (float) basePitch;
    player.prevYaw = (float) baseYaw;
    player.prevPitch = (float) basePitch;
    CameraPose pose = controller.pose();
    if (allowed && pose != null && (yawDelta != 0.0f || pitchDelta != 0.0f)) {
      // The controller owns the pose, so the look is applied as a new pose rather than by
      // mutating it: the camera's flight model and its rotation travel together.
      controller.setPose(
          new CameraPose(
              pose.x(),
              pose.y(),
              pose.z(),
              pose.yaw() + yawDelta,
              Math.max(-90.0f, Math.min(90.0f, pose.pitch() + pitchDelta))));
    }
  }

  private void attach() {
    Minecraft mc = mc();
    ControllablePlayerEntity player = mc.playerEntity;
    ClientWorld level = mc.world;
    if (player == null || level == null) {
      LOG.info("Minecheck: the camera cannot attach before the client is in a world");
      return;
    }
    CameraPose start =
        new CameraPose(
            player.x, player.y + player.getEyeHeight(), player.z, player.yaw, player.pitch);
    baseYaw = player.yaw;
    basePitch = player.pitch;
    if (!controller.attach(start)) {
      return;
    }
    LegacyCameraEntity entity = new LegacyCameraEntity(level);
    entity.id = CAMERA_ENTITY_ID;
    // Placed before the first publish so the old position is the camera's, not the origin: without
    // this the first rendered frame would interpolate the camera in from 0,0,0.
    entity.updatePosition(start.x(), start.y(), start.z());
    place(entity, start);
    level.spawnEntity(entity);
    mc.cameraEntity = entity;
    camera = entity;
    LOG.info(
        String.format(
            "Minecheck: camera attached at %.2f %.2f %.2f (%s)",
            start.x(), start.y(), start.z(), mode.jsonName()));
  }

  private void detach() {
    LegacyCameraEntity entity = camera;
    camera = null;
    controller.detach();
    Minecraft mc = mc();
    if (entity != null) {
      if (mc.cameraEntity == entity) {
        mc.cameraEntity = mc.playerEntity;
      }
      entity.removed = true;
    }
    LOG.info("Minecheck: camera detached");
  }

  /**
   * Publishes a pose to the entity: the old position is snapshotted first, because the renderer
   * interpolates between it and the new one. Vanilla does that at the start of an entity's tick;
   * this entity's tick is empty on purpose, so it is done here instead.
   */
  private static void place(LegacyCameraEntity entity, CameraPose pose) {
    entity.prevX = entity.x;
    entity.prevY = entity.y;
    entity.prevZ = entity.z;
    entity.updatePosition(pose.x(), pose.y(), pose.z());
    entity.prevYaw = entity.yaw;
    entity.prevPitch = entity.pitch;
    entity.yaw = pose.yaw();
    entity.pitch = pose.pitch();
  }

  /** The loaded entity list the camera lives in, for diagnostics. */
  static String describe(Entity entity) {
    return entity == null ? "none" : entity.getClass().getSimpleName().toLowerCase(Locale.ROOT);
  }
}
