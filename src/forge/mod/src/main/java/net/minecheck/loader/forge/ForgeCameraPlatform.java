package net.minecheck.loader.forge;

import java.nio.file.Path;
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
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Loader;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

/**
 * The camera on the Forge 1.7.10 side.
 *
 * <p>It owns the one camera entity, the key binding and the per-tick flight, and delegates every
 * decision about <em>where</em> the camera is to {@link CameraController} in {@code :common}, which
 * is where the flight model is tested. This class only reads keys, publishes a pose to the entity
 * and puts the entity in the level.
 *
 * <p>The camera is installed only when {@code cameraMode} names a mode; with {@code off} no binding
 * is registered and every method is inert.
 */
public final class ForgeCameraPlatform implements CameraPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /**
   * The client-only entity id, held by {@link MinecheckCameraEntity}: negative, so it cannot
   * collide with a server-assigned one. This line's id field is private, so the entity answers the
   * id itself.
   */
  static final int CAMERA_ENTITY_ID = -1;

  /** Used when the config cannot be read at construction: the documented default speed. */
  private static final double DEFAULT_SPEED = 1.0;

  /** The installed platform, for the scroll hook: a wheel notch has no per-instance hook. */
  private static volatile ForgeCameraPlatform current;

  private final ForgeClientPlatform owner;
  private final CameraMode mode;
  private final CameraController controller;

  private KeyBinding toggleKey;
  private MinecheckCameraEntity camera;

  /**
   * The rotation the player is held at while the camera steers: the last one the server saw, so the
   * body never turns and mouse look has somewhere to be measured from.
   */
  private double baseYaw;

  private double basePitch;

  public ForgeCameraPlatform(ForgeClientPlatform owner) {
    this.owner = owner;
    Path configDir = Loader.instance().getConfigDir().toPath();
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
   * One scroll notch while the human may steer: Ctrl+scroll changes the flight speed a notch at a
   * time and the caller cancels the event, so the hotbar does not also change. Returns whether the
   * scroll was the camera's.
   *
   * <p>Speed is human input like any other: in {@code fullControl} the mouse is not the human's, so
   * this answers {@code false} and the scroll falls through to vanilla unchanged.
   */
  public static boolean handleScroll(double vertical) {
    ForgeCameraPlatform platform = current;
    if (platform == null || !platform.active() || !platform.humanInputAllowed()) {
      return false;
    }
    Minecraft mc = mc();
    if (mc.currentScreen != null) {
      return false;
    }
    boolean control =
        Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    if (!control) {
      return false;
    }
    platform.controller.adjustSpeed(vertical);
    return true;
  }

  @Override
  public void registerBindings() {
    if (!mode.enabled() || toggleKey != null) {
      return;
    }
    toggleKey =
        new KeyBinding("key.minecheck.camera.toggle", Keyboard.KEY_F4, "key.categories.minecheck");
    ClientRegistry.registerKeyBinding(toggleKey);
  }

  /**
   * Whether the human's mouse and keys are the camera's this tick. The movement hook asks this to
   * keep the player idle, so it must answer the same way the flight does.
   */
  public static boolean steering() {
    ForgeCameraPlatform platform = current;
    return platform != null
        && platform.active()
        && platform.humanInputAllowed()
        && Minecraft.getMinecraft().currentScreen == null;
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
    if (toggleKey != null) {
      while (toggleKey.isPressed()) {
        if (allowed) {
          toggle();
        }
      }
    }
    if (camera == null) {
      return;
    }
    Minecraft mc = mc();
    if (mc.thePlayer == null || mc.theWorld == null || camera.worldObj != mc.theWorld) {
      detach();
      return;
    }
    // Re-asserted every tick: vanilla computes the render camera from this field, and a respawn or
    // a dimension change may have pointed it somewhere else since the last tick.
    mc.setRenderViewEntity(camera);
    steer(mc.thePlayer, allowed);
    CameraPose moved = controller.tick(inputs(), allowed);
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

  // ------------------------------------------------------------------ attach / detach

  private void attach() {
    Minecraft mc = mc();
    EntityPlayerSP player = mc.thePlayer;
    WorldClient level = mc.theWorld;
    if (player == null || level == null) {
      LOG.info("Minecheck: the camera cannot attach before the client is in a world");
      return;
    }
    CameraPose start =
        new CameraPose(
            player.posX,
            // posY is the eye position on this line; the camera starts there.
            player.posY,
            player.posZ,
            player.rotationYaw,
            player.rotationPitch);
    baseYaw = player.rotationYaw;
    basePitch = player.rotationPitch;
    if (!controller.attach(start)) {
      return;
    }
    MinecheckCameraEntity entity = new MinecheckCameraEntity(level);
    // Placed before the first publish so the old position is the camera's, not the origin: without
    // this the first rendered frame would interpolate the camera in from 0,0,0.
    entity.setPosition(start.x(), start.y(), start.z());
    place(entity, start);
    level.spawnEntityInWorld(entity);
    mc.setRenderViewEntity(entity);
    camera = entity;
    LOG.info(
        String.format(
            "Minecheck: camera attached at %.2f %.2f %.2f (%s)",
            start.x(), start.y(), start.z(), mode.jsonName()));
  }

  private void detach() {
    MinecheckCameraEntity entity = camera;
    camera = null;
    controller.detach();
    Minecraft mc = mc();
    if (entity != null) {
      if (mc.getRenderViewEntity() == entity) {
        mc.setRenderViewEntity(mc.thePlayer);
      }
      entity.setDead();
    }
    LOG.info("Minecheck: camera detached");
  }

  /**
   * Publishes a pose to the entity: the old position is snapshotted first, because the renderer
   * interpolates between it and the new one. Vanilla does that at the start of an entity's tick;
   * this entity's tick is empty on purpose, so it is done here instead.
   */
  private static void place(MinecheckCameraEntity entity, CameraPose pose) {
    entity.prevPosX = entity.posX;
    entity.prevPosY = entity.posY;
    entity.prevPosZ = entity.posZ;
    entity.setPosition(pose.x(), pose.y(), pose.z());
    entity.rotationYaw = pose.yaw();
    entity.rotationPitch = pose.pitch();
  }

  // ------------------------------------------------------------------ input

  /**
   * Whether the human's keys are the camera's this tick: only in {@code assist}, and only while
   * there is a window someone can be looking at — the game's own, visible and focused, or the run's
   * view window, whose keys are the human's by rule. A headless client has neither, so this is
   * false there by construction.
   */
  private boolean humanInputAllowed() {
    if (mode != CameraMode.ASSIST) {
      return false;
    }
    if (owner.viewWindowVisible()) {
      return true;
    }
    return Display.isVisible() && Display.isActive();
  }

  /**
   * Mouse look without touching what the server sees.
   *
   * <p>Vanilla has already turned the player by the time this runs (mouse deltas are applied during
   * a frame, not a tick), so the turn is read as a delta from the angle the player is held at,
   * added to the camera, and the player is put back before the tick that would send it. The body is
   * therefore always at the rotation the server last saw.
   */
  private void steer(EntityPlayerSP player, boolean allowed) {
    if (!allowed || mc().currentScreen != null) {
      baseYaw = player.rotationYaw;
      basePitch = player.rotationPitch;
      return;
    }
    float yawDelta = MathHelper.wrapAngleTo180_float(player.rotationYaw - (float) baseYaw);
    float pitchDelta = player.rotationPitch - (float) basePitch;
    player.rotationYaw = (float) baseYaw;
    player.rotationPitch = (float) basePitch;
    if (yawDelta == 0.0f && pitchDelta == 0.0f) {
      return;
    }
    CameraPose pose = controller.pose();
    if (pose == null) {
      return;
    }
    CameraPose turned =
        new CameraPose(
            pose.x(),
            pose.y(),
            pose.z(),
            MathHelper.wrapAngleTo180_float((float) pose.yaw() + yawDelta),
            MathHelper.clamp_float((float) pose.pitch() + pitchDelta, -90.0f, 90.0f));
    if (controller.setPose(turned)) {
      place(camera, turned);
    }
  }

  private CameraMovement.Inputs inputs() {
    Minecraft mc = mc();
    if (mc.currentScreen != null) {
      return CameraMovement.Inputs.none();
    }
    return new CameraMovement.Inputs(
        mc.gameSettings.keyBindForward.isKeyDown(),
        mc.gameSettings.keyBindBack.isKeyDown(),
        mc.gameSettings.keyBindLeft.isKeyDown(),
        mc.gameSettings.keyBindRight.isKeyDown(),
        mc.gameSettings.keyBindJump.isKeyDown(),
        mc.gameSettings.keyBindSneak.isKeyDown(),
        mc.gameSettings.keyBindSprint.isKeyDown());
  }

  private static Minecraft mc() {
    return Minecraft.getMinecraft();
  }
}
