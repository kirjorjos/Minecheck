package net.minecheck.platform.yarn;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecheck.common.camera.CameraController;
import net.minecheck.common.camera.CameraMode;
import net.minecheck.common.camera.CameraMovement;
import net.minecheck.common.camera.CameraPose;
import net.minecheck.common.config.CameraPolicy;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.config.JsonConfigManager;
import net.minecheck.platform.api.CameraPlatform;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * The camera on the yarn side, shared by Fabric and Quilt.
 *
 * <p>It owns the one camera entity, the F4 binding and the per-tick flight, and delegates every
 * decision about <em>where</em> the camera is to {@link CameraController} in {@code :common}. That
 * split is why this class has no arithmetic in it: the flight model is unit-tested without a game,
 * and this only reads keys, publishes a pose to the entity and puts the entity in the level.
 *
 * <p>The camera is installed only when {@code cameraMode} names a mode; with {@code off} the
 * constructor still exists but installs no binding and every method is inert, which is what keeps
 * {@code off} free of render-path and input work.
 */
public final class YarnCameraPlatform implements CameraPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** The client-only entity id: negative, so it cannot collide with a server-assigned one. */
  private static final int CAMERA_ENTITY_ID = -1;

  /** Used when the config cannot be read at construction: the documented default speed. */
  private static final double DEFAULT_SPEED = 1.0;

  /** The installed platform, for the scroll mixin: a wheel notch has no per-instance hook. */
  private static volatile YarnCameraPlatform current;

  private final YarnClientPlatform owner;
  private final CameraMode mode;
  private final CameraController controller;
  private KeyBinding toggleKey;

  private MinecheckCameraEntity camera;

  /**
   * The rotation the player is held at while the camera steers: the last one the server saw, so the
   * body never turns and mouse look has somewhere to be measured from.
   */
  private float baseYaw;

  private float basePitch;

  public YarnCameraPlatform(YarnClientPlatform owner) {
    this.owner = owner;
    Path configDir = FabricLoader.getInstance().getConfigDir();
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
    YarnCameraPlatform platform = current;
    if (platform == null) {
      return false;
    }
    if (!platform.active()) {
      return false;
    }
    if (!platform.humanInputAllowed()) {
      return false;
    }
    MinecraftClient mc = mc();
    if (mc.currentScreen != null) {
      return false;
    }
    long handle = mc.getWindow().getHandle();
    boolean control =
        GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
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
        KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                "key.minecheck.camera.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F4,
                "key.categories.minecheck"));
  }

  /**
   * Whether the human's mouse and keys are the camera's this tick. The movement mixin asks this to
   * keep the player idle, so it must answer the same way the flight does.
   */
  public static boolean steering() {
    YarnCameraPlatform platform = current;
    return platform != null
        && platform.active()
        && platform.humanInputAllowed()
        && MinecraftClient.getInstance().currentScreen == null;
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
    // Drained even when the human may not toggle, so a press in fullControl cannot sit in the
    // binding's queue and fire the day the mode changes.
    boolean allowed = humanInputAllowed();
    if (toggleKey != null) {
      while (toggleKey.wasPressed()) {
        if (allowed) {
          toggle();
        }
      }
    }
    if (camera == null) {
      return;
    }
    MinecraftClient mc = mc();
    if (mc.player == null || mc.world == null || camera.getWorld() != mc.world) {
      detach();
      return;
    }
    // Re-asserted every tick: vanilla computes the render camera from this field, and a spectator
    // switch or a dimension change may have pointed it somewhere else since the last tick.
    mc.cameraEntity = camera;
    steer(mc.player, allowed);
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
    MinecraftClient mc = mc();
    ClientPlayerEntity player = mc.player;
    ClientWorld world = mc.world;
    if (player == null || world == null) {
      LOG.info("Minecheck: the camera cannot attach before the client is in a world");
      return;
    }
    CameraPose start =
        new CameraPose(
            player.getX(), player.getEyeY(), player.getZ(), player.getYaw(), player.getPitch());
    baseYaw = player.getYaw();
    basePitch = player.getPitch();
    if (!controller.attach(start)) {
      return;
    }
    MinecheckCameraEntity entity = new MinecheckCameraEntity(world);
    entity.setId(CAMERA_ENTITY_ID);
    // Placed before the first publish so the old position is the camera's, not the origin: without
    // this the first rendered frame would interpolate the camera in from 0,0,0.
    entity.setPos(start.x(), start.y(), start.z());
    place(entity, start);
    world.addEntity(CAMERA_ENTITY_ID, entity);
    mc.cameraEntity = entity;
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
    MinecraftClient mc = mc();
    if (entity != null) {
      if (mc.cameraEntity == entity) {
        mc.cameraEntity = mc.player;
      }
      entity.discard();
    }
    LOG.info("Minecheck: camera detached");
  }

  /**
   * Publishes a pose to the entity: the old position is snapshotted first, because the renderer
   * interpolates between it and the new one. Vanilla does that at the start of an entity's tick;
   * this entity's tick is empty on purpose, so it is done here instead.
   */
  private static void place(MinecheckCameraEntity entity, CameraPose pose) {
    entity.prevX = entity.getX();
    entity.prevY = entity.getY();
    entity.prevZ = entity.getZ();
    entity.setPos(pose.x(), pose.y(), pose.z());
    entity.setYaw(pose.yaw());
    entity.setPitch(pose.pitch());
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
    long handle = mc().getWindow().getHandle();
    return GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE
        && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
  }

  /**
   * Mouse look without touching what the server sees.
   *
   * <p>Vanilla has already turned the player by the time this runs (mouse deltas are applied during
   * a frame, not a tick), so the turn is read as a delta from the angle the player is held at,
   * added to the camera, and the player is put back before the tick that would send it. The body is
   * therefore always at the rotation the server last saw: an observer cannot tell it from an idle
   * one, and the human keeps the view they were pointing at once the camera detaches.
   *
   * <p>When the human's look is not the camera's this tick the held angle follows the player
   * instead, so a script that turns the player is not later read as one enormous mouse delta.
   */
  private void steer(ClientPlayerEntity player, boolean allowed) {
    if (!allowed || mc().currentScreen != null) {
      baseYaw = player.getYaw();
      basePitch = player.getPitch();
      return;
    }
    float yawDelta = MathHelper.wrapDegrees(player.getYaw() - baseYaw);
    float pitchDelta = player.getPitch() - basePitch;
    player.setYaw(baseYaw);
    player.setPitch(basePitch);
    if (yawDelta == 0.0f && pitchDelta == 0.0f) {
      return;
    }
    CameraPose current = controller.pose();
    if (current == null) {
      return;
    }
    CameraPose turned =
        new CameraPose(
            current.x(),
            current.y(),
            current.z(),
            MathHelper.wrapDegrees(current.yaw() + yawDelta),
            clampPitch(current.pitch() + pitchDelta));
    if (controller.setPose(turned)) {
      place(camera, turned);
    }
  }

  private static float clampPitch(double pitch) {
    return (float) Math.max(-90.0, Math.min(90.0, pitch));
  }

  private CameraMovement.Inputs inputs() {
    MinecraftClient mc = mc();
    if (mc.currentScreen != null) {
      return CameraMovement.Inputs.none();
    }
    return new CameraMovement.Inputs(
        mc.options.forwardKey.isPressed(),
        mc.options.backKey.isPressed(),
        mc.options.leftKey.isPressed(),
        mc.options.rightKey.isPressed(),
        mc.options.jumpKey.isPressed(),
        mc.options.sneakKey.isPressed(),
        mc.options.sprintKey.isPressed());
  }

  private static MinecraftClient mc() {
    return MinecraftClient.getInstance();
  }
}
