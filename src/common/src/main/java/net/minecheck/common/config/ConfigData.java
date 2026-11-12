package net.minecheck.common.config;

import net.minecheck.common.camera.CameraInteractionMode;
import net.minecheck.common.camera.CameraMode;

/**
 * Contents of config/minecheck/config.json.
 *
 * <p>The screenshot fields drive the deterministic capture profile ({@code
 * minecheck.captureScreenshot}): the window the client is pinned to, the GUI scale HUD geometry is
 * laid out at, the encode-time downscale, and whether chat may appear in a capture. They are
 * run-level on purpose — one run has one profile, otherwise a baseline has no fixed picture to
 * compare against.
 *
 * <p>Four more fields are about the client the run drives rather than about a script: {@code
 * hideGameWindow} keeps the game's own window from ever appearing (the default), {@code muteAudio}
 * keeps the sound engine from ever opening an audio device (also the default), {@code viewWindow}
 * shows the instance's frames in a window of Minecheck's own instead, and {@code
 * restoreVanillaCamera} keeps Minecheck out of the camera/render path entirely.
 *
 * <p>The {@code camera} fields configure the built-in observation camera. It is off unless {@code
 * cameraMode} names a mode, and the two mode-dependent defaults — damage auto-exit and the
 * action-bar notices — are resolved from the mode once, when the file is read, so a file that names
 * a mode and leaves those fields out gets that mode's behaviour. {@code restoreVanillaCamera} wins
 * over {@code cameraMode} when they disagree; {@link #effectiveCameraMode()} is the answer every
 * consumer must use.
 *
 * @param script single-phase boot script file name; empty = passive
 * @param coordinator optional coordinator file that declares the order of phases (long-lived
 *     session; wins over {@code script} when set)
 * @param failMode "exit" (default): stop client on script failure; "continue": log and keep going
 * @param defaultTimeout default timeout in seconds for waits/joins
 * @param screenshotWidth captured frame width in pixels (the client's window), 320–7680
 * @param screenshotHeight captured frame height in pixels, 240–4320
 * @param screenshotGuiScale pinned GUI scale, 1–4 (never auto, so HUD geometry is stable)
 * @param screenshotScale encode-time downscale, 0.25–1.0
 * @param screenshotChatVisible whether chat may appear in a capture (default: hidden)
 * @param viewWindow whether to open the run's window onto the client's frames, used when the game's
 *     own window is hidden (default: off)
 * @param restoreVanillaCamera whether to leave the camera/render path untouched — scripting keeps
 *     working, every capture fails (default: off)
 * @param hideGameWindow whether to keep the game's own window from appearing at all (default: on)
 * @param muteAudio whether the sound engine may open an audio device at all (default: no)
 * @param ownTickClock whether scripts may hold, step and hand back the server's tick loop (default:
 *     no — a run that does not ask for it keeps vanilla's twenty ticks a second)
 * @param cameraMode how much of the run the camera owns (default: off)
 * @param cameraHorizontalSpeed horizontal flight speed factor, 0–10
 * @param cameraVerticalSpeed vertical flight speed factor, 0–10
 * @param cameraIgnoreAll whether the camera passes through every block (default: on)
 * @param cameraInteractionMode whether interactions originate at the camera or the player
 * @param cameraShowPlayer whether the player's body is drawn while the camera is detached
 * @param cameraFullBright whether the lightmap is forced to maximum brightness
 * @param cameraDisableOnDamage whether damage leaves the camera (default: on only in assist)
 * @param cameraFreezePlayer whether the human's player movement is blocked while flying
 * @param cameraNotifications whether action-bar notices are shown (default: on only in assist)
 */
public record ConfigData(
    String script,
    String coordinator,
    String failMode,
    int defaultTimeout,
    int screenshotWidth,
    int screenshotHeight,
    int screenshotGuiScale,
    double screenshotScale,
    boolean screenshotChatVisible,
    boolean viewWindow,
    boolean restoreVanillaCamera,
    boolean hideGameWindow,
    boolean muteAudio,
    boolean ownTickClock,
    CameraMode cameraMode,
    double cameraHorizontalSpeed,
    double cameraVerticalSpeed,
    boolean cameraIgnoreAll,
    CameraInteractionMode cameraInteractionMode,
    boolean cameraShowPlayer,
    boolean cameraFullBright,
    boolean cameraDisableOnDamage,
    boolean cameraFreezePlayer,
    boolean cameraNotifications) {

  public static final int DEFAULT_SCREENSHOT_WIDTH = 854;

  public static final int DEFAULT_SCREENSHOT_HEIGHT = 480;

  public static final int DEFAULT_SCREENSHOT_GUI_SCALE = 2;

  public static final double DEFAULT_SCREENSHOT_SCALE = 1.0;

  public static final boolean DEFAULT_SCREENSHOT_CHAT_VISIBLE = false;

  public static final boolean DEFAULT_VIEW_WINDOW = false;

  public static final boolean DEFAULT_RESTORE_VANILLA_CAMERA = false;

  public static final boolean DEFAULT_HIDE_GAME_WINDOW = true;

  public static final boolean DEFAULT_MUTE_AUDIO = true;

  /**
   * Off by default: owning the clock stops the server the script is playing on, which changes what
   * every other wait in the run means. A suite opts in when it wants the tick count, not the wall
   * clock, to decide what a capture contains.
   */
  public static final boolean DEFAULT_OWN_TICK_CLOCK = false;

  public static final CameraMode DEFAULT_CAMERA_MODE = CameraMode.OFF;

  public static final double DEFAULT_CAMERA_HORIZONTAL_SPEED = 1.0;

  public static final double DEFAULT_CAMERA_VERTICAL_SPEED = 1.0;

  public static final boolean DEFAULT_CAMERA_IGNORE_ALL = true;

  public static final CameraInteractionMode DEFAULT_CAMERA_INTERACTION_MODE =
      CameraInteractionMode.CAMERA;

  public static final boolean DEFAULT_CAMERA_SHOW_PLAYER = true;

  public static final boolean DEFAULT_CAMERA_FULL_BRIGHT = false;

  public static final boolean DEFAULT_CAMERA_FREEZE_PLAYER = false;

  /**
   * The mode-dependent default for damage auto-exit, or {@code false} when no mode is known.
   *
   * <p>Public because the file's parser needs it before it can build a {@link ConfigData}: the mode
   * has to be read first, then the fields that depend on it.
   */
  public static boolean defaultCameraDisableOnDamage(CameraMode mode) {
    return mode != null && mode.defaultDisableOnDamage();
  }

  /** The mode-dependent default for action-bar notices, or {@code false} when no mode is known. */
  public static boolean defaultCameraNotifications(CameraMode mode) {
    return mode != null && mode.defaultNotifications();
  }

  /** Every field of the capture profile, the client switches and the camera at their defaults. */
  public ConfigData(String script, String coordinator, String failMode, int defaultTimeout) {
    this(
        script,
        coordinator,
        failMode,
        defaultTimeout,
        DEFAULT_SCREENSHOT_WIDTH,
        DEFAULT_SCREENSHOT_HEIGHT,
        DEFAULT_SCREENSHOT_GUI_SCALE,
        DEFAULT_SCREENSHOT_SCALE,
        DEFAULT_SCREENSHOT_CHAT_VISIBLE,
        DEFAULT_VIEW_WINDOW,
        DEFAULT_RESTORE_VANILLA_CAMERA,
        DEFAULT_HIDE_GAME_WINDOW,
        DEFAULT_MUTE_AUDIO,
        DEFAULT_OWN_TICK_CLOCK,
        DEFAULT_CAMERA_MODE,
        DEFAULT_CAMERA_HORIZONTAL_SPEED,
        DEFAULT_CAMERA_VERTICAL_SPEED,
        DEFAULT_CAMERA_IGNORE_ALL,
        DEFAULT_CAMERA_INTERACTION_MODE,
        DEFAULT_CAMERA_SHOW_PLAYER,
        DEFAULT_CAMERA_FULL_BRIGHT,
        defaultCameraDisableOnDamage(DEFAULT_CAMERA_MODE),
        DEFAULT_CAMERA_FREEZE_PLAYER,
        defaultCameraNotifications(DEFAULT_CAMERA_MODE));
  }

  public static ConfigData defaults() {
    return new ConfigData("", null, "exit", 30);
  }

  /**
   * The mode behaviour must use: {@code restoreVanillaCamera} wins over the file's {@code
   * cameraMode}, and an absent mode (a value built by hand rather than read from the file) means
   * off.
   */
  public CameraMode effectiveCameraMode() {
    if (restoreVanillaCamera || cameraMode == null) {
      return CameraMode.OFF;
    }
    return cameraMode;
  }

  public boolean exitOnFail() {
    return !"continue".equalsIgnoreCase(failMode);
  }

  public String entryScript() {
    return (coordinator != null && !coordinator.isBlank()) ? coordinator : script;
  }

  public boolean isCoordinator() {
    return coordinator != null && !coordinator.isBlank();
  }
}
