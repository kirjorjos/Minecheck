package net.minecheck.platform.api;

import java.nio.file.Path;

/**
 * One capture of the client's frame, handed to {@link ClientPlatform#requestScreenshot}.
 *
 * <p>The script action creates the request, then polls {@link #state()} once per tick while it
 * yields. The platform owns everything a capture needs that the scripting layer cannot touch: the
 * render thread, the framebuffer readback, the PNG encoder, and the run directory the file lands
 * in. Nothing Minecraft-shaped is exposed here — the request is plain data plus a state machine, so
 * the scripting layer stays testable without a game.
 *
 * <p><b>Threading.</b> The script action (on LuaJ's coroutine thread) calls {@link
 * ClientPlatform#requestScreenshot} and reads {@link #state()}; the platform may write every field
 * from the render thread or from its encoder thread. All mutable fields are {@code volatile} for
 * that reason, and the action only ever reads a field after the state says it is safe to.
 */
public final class ScreenshotRequest {

  /**
   * The capture's lifecycle. The script loop only ever waits for {@link #DONE} or {@link #FAILED}.
   */
  public enum State {
    /**
     * Not captured yet; the platform may still be waiting for the window to reach the right size.
     */
    QUEUED,
    /** The frame has been read and is being cropped/encoded/written. */
    RUNNING,
    /** Written; {@link #path()}, {@link #width()}, {@link #height()}, {@link #bytes()} are set. */
    DONE,
    /** Failed; {@link #failure()} says why and the action turns it into a Lua error. */
    FAILED
  }

  /** Region coordinates are framebuffer pixels (the default). */
  public static final String UNIT_FRAMEBUFFER = "framebuffer";

  /**
   * Region coordinates are GUI pixels, i.e. framebuffer pixels divided by the effective GUI scale.
   */
  public static final String UNIT_GUI = "gui";

  private final String name;
  private final boolean region;
  private final int x;
  private final int y;
  private final int width;
  private final int height;
  private final String unit;
  private final double scale;
  private final boolean chatVisible;
  private final int frameWidth;
  private final int frameHeight;

  private volatile int retries;
  private volatile boolean prepared;
  private volatile State state = State.QUEUED;
  private volatile String failure;
  private volatile Path path;
  private volatile int outWidth;
  private volatile int outHeight;
  private volatile long bytes;
  private volatile String sha256;

  /**
   * @param name capture name; also the file name ({@code <name>.png})
   * @param region whether {@code x/y/width/height} describe a crop (false = the whole frame)
   * @param unit {@link #UNIT_FRAMEBUFFER} or {@link #UNIT_GUI}
   * @param scale encode-time downscale, {@code 0.25}–{@code 1.0}
   * @param chatVisible whether the capture profile left chat on (recorded in the profile report)
   * @param frameWidth the frame the profile says the client is pinned to, in pixels
   * @param frameHeight the frame the profile says the client is pinned to, in pixels
   */
  public ScreenshotRequest(
      String name,
      boolean region,
      int x,
      int y,
      int width,
      int height,
      String unit,
      double scale,
      boolean chatVisible,
      int frameWidth,
      int frameHeight) {
    this.name = name;
    this.region = region;
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    this.unit = unit == null ? UNIT_FRAMEBUFFER : unit;
    this.scale = scale;
    this.chatVisible = chatVisible;
    this.frameWidth = frameWidth;
    this.frameHeight = frameHeight;
  }

  public String name() {
    return name;
  }

  public boolean hasRegion() {
    return region;
  }

  public int x() {
    return x;
  }

  public int y() {
    return y;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public String unit() {
    return unit;
  }

  public double scale() {
    return scale;
  }

  public boolean chatVisible() {
    return chatVisible;
  }

  /** The frame width the capture profile pins the client to. */
  public int frameWidth() {
    return frameWidth;
  }

  /** The frame height the capture profile pins the client to. */
  public int frameHeight() {
    return frameHeight;
  }

  /**
   * Whether the platform has already done the one-tick work a capture needs before it reads.
   *
   * <p>The framebuffer a capture reads was drawn by the frame before the action ran, so anything
   * that has to be true <em>of the picture</em> — chat cleared out of the HUD, for example — has to
   * happen one frame ahead of the read. The platform prepares on the first pass and reads on the
   * next.
   */
  public boolean prepared() {
    return prepared;
  }

  /** Records that the platform has prepared the frame the next read will pick up. */
  public void markPrepared() {
    prepared = true;
  }

  /**
   * Counts how many ticks the platform has asked for before it can capture (e.g. while a window
   * resize is still being applied) and answers whether it may keep waiting.
   */
  public boolean retry(String reason) {
    if (++retries > MAX_RETRIES) {
      fail(reason);
      return false;
    }
    requeue();
    return true;
  }

  /** How many ticks a capture may be deferred before it is reported as a failure. */
  public static final int MAX_RETRIES = 40;

  public State state() {
    return state;
  }

  public boolean finished() {
    return state == State.DONE || state == State.FAILED;
  }

  /** The written PNG's absolute path; {@code null} until the request is {@link State#DONE}. */
  public Path path() {
    return path;
  }

  /** The written PNG's width in pixels (after the region crop and the downscale). */
  public int outWidth() {
    return outWidth;
  }

  /** The written PNG's height in pixels (after the region crop and the downscale). */
  public int outHeight() {
    return outHeight;
  }

  /** The written PNG's size in bytes. */
  public long bytes() {
    return bytes;
  }

  /** Lower-case hex SHA-256 of the written PNG — what makes a mismatch diagnosable from a log. */
  public String sha256() {
    return sha256;
  }

  /** Why the capture failed; {@code null} unless the state is {@link State#FAILED}. */
  public String failure() {
    return failure;
  }

  /** Mark the capture as in progress (the readback has started). Called by the platform. */
  public void running() {
    if (state == State.QUEUED) {
      state = State.RUNNING;
    }
  }

  /**
   * Put the request back in the queue. The platform uses this when it cannot capture yet — most
   * often because it just resized the window and the new framebuffer size has not been applied — so
   * the action's next tick asks again instead of capturing the wrong geometry.
   */
  public void requeue() {
    state = State.QUEUED;
  }

  /** Record a successful capture. Called by the platform once the PNG is on disk. */
  public void succeed(Path written, int width, int height, long bytes, String sha256) {
    this.path = written;
    this.outWidth = width;
    this.outHeight = height;
    this.bytes = bytes;
    this.sha256 = sha256;
    this.state = State.DONE;
  }

  /** Record a failed capture. Called by the platform; the action raises it as a Lua error. */
  public void fail(String message) {
    this.failure = message;
    this.state = State.FAILED;
  }
}
