package net.minecheck.common.config;

/**
 * Contents of config/minecheck/config.json.
 *
 * <p>The screenshot fields drive the deterministic capture profile ({@code
 * minecheck.captureScreenshot}): the window the client is pinned to, the GUI scale HUD geometry is
 * laid out at, the encode-time downscale, and whether chat may appear in a capture. They are
 * run-level on purpose — one run has one profile, otherwise a baseline has no fixed picture to
 * compare against.
 *
 * <p>Two more fields are about the client the run drives rather than about a script: {@code
 * viewWindow} shows the instance to a human, and {@code restoreVanillaCamera} keeps Minecheck out
 * of the camera/render path entirely. Both default to {@code false}, so a run that names neither
 * behaves exactly as it did before they existed.
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
 * @param viewWindow whether to open a view-only window onto the client's frames (default: off)
 * @param restoreVanillaCamera whether to leave the camera/render path untouched — scripting keeps
 *     working, every capture fails (default: off)
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
    boolean restoreVanillaCamera) {

  public static final int DEFAULT_SCREENSHOT_WIDTH = 854;

  public static final int DEFAULT_SCREENSHOT_HEIGHT = 480;

  public static final int DEFAULT_SCREENSHOT_GUI_SCALE = 2;

  public static final double DEFAULT_SCREENSHOT_SCALE = 1.0;

  public static final boolean DEFAULT_SCREENSHOT_CHAT_VISIBLE = false;

  public static final boolean DEFAULT_VIEW_WINDOW = false;

  public static final boolean DEFAULT_RESTORE_VANILLA_CAMERA = false;

  /** Every field of the capture profile and both client switches at their documented defaults. */
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
        DEFAULT_RESTORE_VANILLA_CAMERA);
  }

  public static ConfigData defaults() {
    return new ConfigData("", null, "exit", 30);
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
