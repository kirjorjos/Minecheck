package net.minecheck.loader.forge;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiBeacon;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiCrafting;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.gui.inventory.GuiFurnace;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.Loader;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public class ForgeClientPlatform implements ClientPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** The camera this client exposes to scripts and to the client tick hook. */
  private final ForgeCameraPlatform cameraPlatform = new ForgeCameraPlatform(this);

  @Override
  public net.minecheck.platform.api.CameraPlatform camera() {
    return cameraPlatform;
  }

  /** Key bindings are a client-setup concern, so the entrypoint calls this at init. */
  void installCameraBindings() {
    cameraPlatform.registerBindings();
  }

  /** Set once this line's pause-on-focus-loss has been turned off for the run. */
  private boolean unfocusedPlayAllowed;

  /** Set once the window handling has been settled (or the config said not to). */
  private boolean windowSettled;

  /**
   * 1.12.2 runs on LWJGL 2, which has no way to hide a window: {@code Display} can resize it, move
   * it off screen or minimise it, but hiding it is a GLFW concept. The pause-on-lost-focus rule is
   * still turned off, so a run under Xvfb behaves exactly as it does on the newer lines; the one
   * visible difference is that the window keeps existing.
   */
  void hideWindowIfConfigured() {
    if (windowSettled) {
      return;
    }
    windowSettled = true;
    if (!net.minecheck.common.config.GameWindowPolicy.hide(
        Loader.instance().getConfigDir().toPath())) {
      return;
    }
    LOG.warning(
        "Minecheck: hideGameWindow is on, but 1.12.2's LWJGL 2 has no hidden window — the game"
            + " window stays mapped (pause-on-focus-loss is still off)");
  }

  /**
   * Turns off 1.12.2's pause-on-focus-loss.
   *
   * <p>A Minecheck run never focuses the game window, and the client pauses its integrated server
   * whenever the window is not focused — the world then loads and pauses in the same breath, so
   * {@code isWorldReady} never turns true.
   */
  void allowUnfocusedPlay() {
    if (unfocusedPlayAllowed) {
      return;
    }
    mc().gameSettings.pauseOnLostFocus = false;
    unfocusedPlayAllowed = true;
  }

  /**
   * True while the run's view window is on screen — the human's visible surface when the game's own
   * window is hidden.
   */
  boolean viewWindowVisible() {
    net.minecheck.platform.api.ViewWindow window = viewWindow;
    return window != null && window.visible();
  }

  private Minecraft mc() {
    return Minecraft.getMinecraft();
  }

  /** The game directory: the parent of the config directory FML hands every mod. */
  private static File gameDir() {
    return Loader.instance().getConfigDir().getParentFile();
  }

  private void onClientThread(Runnable r) {
    Minecraft mc = mc();
    if (mc.isCallingFromMinecraftThread()) {
      r.run();
    } else {
      mc.addScheduledTask(r);
    }
  }

  @Override
  public void runOnClientThread(Runnable action) {
    onClientThread(action);
  }

  // ------------------------------------------------------------ resource reload

  /** Ticks still to elapse before a 1.12.2 reload is reported settled. */
  private volatile int reloadTicksRemaining = -1;

  @Override
  public void reloadResources() {
    // 1.12.2's refreshResources() answers nothing, so settlement is observed as "a couple of client
    // ticks have passed since the request" rather than as a future. The reload itself runs on the
    // client thread and the following ticks are what let its listeners run.
    onClientThread(
        () -> {
          if (mc().world != null) {
            mc().refreshResources();
          }
          reloadTicksRemaining = 2;
        });
  }

  @Override
  public boolean isResourceReloadSettled() {
    return reloadTicksRemaining <= 0;
  }

  // ------------------------------------------------------------ screenshots

  /**
   * Encode/write thread for captures: the GL readback stays on the render thread, but the crop, the
   * PNG encode and the file write do not.
   */
  private static final java.util.concurrent.ExecutorService SCREENSHOT_WRITER =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Minecheck-screenshot");
            t.setDaemon(true);
            return t;
          });

  /** GL_RENDERER of the client, recorded in profile.json so renderer drift is diagnosable. */
  private static volatile String screenshotRenderer;

  /** Set while {@code restoreVanillaCamera} is in effect: no render-path work, and no captures. */
  private volatile boolean vanillaCameraRestored;

  /** The run's window, or {@code null}. */
  private volatile net.minecheck.platform.api.ViewWindow viewWindow;

  /** Client ticks between frames pushed to the view window (~10 fps at 20 ticks a second). */
  private static final int VIEW_WINDOW_TICK_INTERVAL = 2;

  private int viewWindowTicks;

  @Override
  public void setVanillaCameraRestored(boolean restored) {
    vanillaCameraRestored = restored;
    if (restored) {
      LOG.warning(
          "Minecheck: restoreVanillaCamera is on — the camera/render path is left untouched and"
              + " every capture fails: "
              + net.minecheck.platform.api.ClientPlatform.SCREENSHOTS_UNAVAILABLE_VANILLA_CAMERA);
    }
  }

  @Override
  public void setViewWindow(net.minecheck.platform.api.ViewWindow window) {
    viewWindow = window;
  }

  /**
   * Pushes the client's latest frame to the view window, if the run opened one, and ticks the
   * resource-reload countdown.
   */
  @Override
  public void pumpViewWindow() {
    if (reloadTicksRemaining > 0) {
      reloadTicksRemaining--;
    }
    net.minecheck.platform.api.ViewWindow window = viewWindow;
    if (window == null || !window.visible()) {
      return;
    }
    if (viewWindowTicks++ % VIEW_WINDOW_TICK_INTERVAL != 0) {
      return;
    }
    try {
      int width = mc().displayWidth;
      int height = mc().displayHeight;
      if (width <= 0 || height <= 0) {
        return;
      }
      window.offerFrame(readFramebuffer(), width, height);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Minecheck: could not read a frame for the view window", e);
      window.close();
      viewWindow = null;
    }
  }

  /**
   * Reads the main framebuffer into ARGB pixels, top row first.
   *
   * <p>1.12.2 has no {@code RenderTarget}/{@code NativeImage} pair to delegate to, so the readback
   * is done here: bind the framebuffer, {@code glReadPixels} the RGBA bytes, then repack them (GL's
   * origin is the bottom-left).
   */
  private static int[] readFramebuffer() {
    Framebuffer framebuffer = Minecraft.getMinecraft().getFramebuffer();
    int width = framebuffer.framebufferTextureWidth;
    int height = framebuffer.framebufferHeight;
    ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
    int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer.framebufferObject);
    try {
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
      GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
    } finally {
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
    }
    int[] argb = new int[width * height];
    for (int row = 0; row < height; row++) {
      int from = (height - 1 - row) * width * 4; // GL's origin is the bottom-left
      int to = row * width;
      for (int column = 0; column < width; column++) {
        int pixel = from + column * 4;
        argb[to + column] =
            (buffer.get(pixel + 3) & 0xFF) << 24
                | (buffer.get(pixel) & 0xFF) << 16
                | (buffer.get(pixel + 1) & 0xFF) << 8
                | (buffer.get(pixel + 2) & 0xFF);
      }
    }
    return argb;
  }

  /**
   * Dispatches a key typed into the view window through the client's own key path — the same
   * binding state a human's keypress sets.
   *
   * <p>Deliberately <em>not</em> marked with {@link net.minecheck.platform.api.InjectedInput}: that
   * marker is for Minecheck's own synthetic input, and a camera that ignores it must still obey the
   * human typing at this window.
   */
  @Override
  public void virtualKey(int glfwKey, boolean down) {
    String name = glfwKeyName(glfwKey);
    if (name == null) {
      return;
    }
    int code = Keyboard.getKeyIndex(name);
    if (code == Keyboard.KEY_NONE) {
      return;
    }
    onClientThread(() -> pressKeyCode(code, down));
  }

  /**
   * Points the client's clock back at the integrated server's. See {@link
   * net.minecheck.platform.api.ClientPlatform#pinClientClock()} for why the drift is invisible live
   * but not in a capture.
   */
  @Override
  public void pinClientClock() {
    Minecraft minecraft = mc();
    applyPendingWorldSettings(minecraft);
    WorldClient level = minecraft.world;
    if (level == null) {
      return;
    }
    MinecraftServer server = minecraft.getIntegratedServer();
    if (server == null) {
      // Remote server: no authoritative value in this process.
      return;
    }
    WorldServer overworld = server.getWorld(0);
    if (overworld == null) {
      return;
    }
    long serverTime = overworld.getWorldTime();
    if (level.getWorldTime() != serverTime) {
      level.setWorldTime(serverTime);
    }
  }

  /** Set once the pinned timer is installed, or once installation has been given up on. */
  private static volatile boolean renderTickDeltaPinned;

  private static volatile boolean renderTickDeltaUnavailable;

  /**
   * Draws every frame at render delta 1 — see {@link ClientPlatform#pinRenderTickDelta()} for why a
   * capture needs it.
   *
   * <p>1.12.2 recomputes the delta in {@code Timer.updateTimer()} at the top of every frame, and
   * the render tick event fires after it, so writing the value here is enough. The field is found
   * by type rather than by name, because the production jar carries SRG names.
   */
  @Override
  public void pinRenderTickDelta() {
    if (vanillaCameraRestored || renderTickDeltaPinned || renderTickDeltaUnavailable) {
      return;
    }
    Minecraft mc = mc();
    Field timerField = null;
    for (Field field : Minecraft.class.getDeclaredFields()) {
      if (field.getType().getSimpleName().equals("Timer")) {
        timerField = field;
        break;
      }
    }
    if (timerField == null) {
      renderTickDeltaUnavailable = true;
      LOG.warning(
          "Minecheck: no render timer on this client — captures keep the vanilla partial tick and"
              + " may differ by a level between runs");
      return;
    }
    try {
      timerField.setAccessible(true);
      Object timer = timerField.get(mc);
      for (Field field : timer.getClass().getDeclaredFields()) {
        if (field.getType() == float.class && !field.getName().equals("ticksPerSecond")) {
          field.setAccessible(true);
          field.setFloat(timer, 1.0f);
          renderTickDeltaPinned = true;
          LOG.info("Minecheck: pinned the render tick delta to 1");
          return;
        }
      }
      renderTickDeltaUnavailable = true;
      LOG.warning(
          "Minecheck: the render timer has no partial-tick field — captures keep the vanilla delta");
    } catch (ReflectiveOperationException | RuntimeException e) {
      renderTickDeltaUnavailable = true;
      LOG.log(Level.WARNING, "Minecheck: could not pin the render tick delta", e);
    }
  }

  @Override
  public void requestScreenshot(net.minecheck.platform.api.ScreenshotRequest request) {
    if (vanillaCameraRestored) {
      request.fail(
          net.minecheck.platform.api.ClientPlatform.SCREENSHOTS_UNAVAILABLE_VANILLA_CAMERA);
      return;
    }
    onClientThread(() -> captureScreenshot(request, true));
  }

  @Override
  public void requestFrame(net.minecheck.platform.api.ScreenshotRequest request) {
    if (vanillaCameraRestored) {
      request.fail(
          net.minecheck.platform.api.ClientPlatform.SCREENSHOTS_UNAVAILABLE_VANILLA_CAMERA);
      return;
    }
    onClientThread(() -> captureScreenshot(request, false));
  }

  /**
   * Writes a frame that was captured in memory: no readback, because the pixels <em>are</em> the
   * picture.
   */
  @Override
  public void writeFrame(net.minecheck.platform.api.ScreenshotRequest request) {
    if (vanillaCameraRestored) {
      request.fail(
          net.minecheck.platform.api.ClientPlatform.SCREENSHOTS_UNAVAILABLE_VANILLA_CAMERA);
      return;
    }
    request.running();
    encodeFrame(
        request,
        new File(gameDir(), "screenshots")
            .toPath()
            .resolve(request.name() + ".png")
            .toAbsolutePath());
  }

  /**
   * Frames the client has finished drawing, counted by the loader's per-frame hook.
   *
   * <p>A capture needs a frame count and not a delay: the read must pick up a frame drawn
   * <em>after</em> the chat was cleared, and one tick is not one frame.
   */
  private static final java.util.concurrent.atomic.AtomicLong DRAWN_FRAMES =
      new java.util.concurrent.atomic.AtomicLong();

  /** Records that the client finished drawing a frame. */
  public static void notifyFrameDrawn() {
    DRAWN_FRAMES.incrementAndGet();
  }

  /** The frame at which the pending capture cleared the chat, in {@link #DRAWN_FRAMES} terms. */
  private long chatClearedAtFrame;

  /**
   * One attempt at a capture, on the client (render) thread: empties the chat, waits for a frame
   * drawn without it, then reads the main framebuffer and hands the pixels to the writer thread.
   */
  private void captureScreenshot(
      net.minecheck.platform.api.ScreenshotRequest request, boolean write) {
    Minecraft mc = mc();
    boolean clearChat = !request.chatVisible() && mc.world != null;
    if (clearChat && !request.prepared()) {
      mc.ingameGUI.getChatGUI().clearChatMessages(true);
      chatClearedAtFrame = DRAWN_FRAMES.get();
      request.markPrepared();
      request.retry("clearing chat before the capture");
      return;
    }
    if (clearChat && DRAWN_FRAMES.get() == chatClearedAtFrame) {
      request.retry("waiting for the first frame drawn after the chat was cleared");
      return;
    }
    int frameWidth = mc.displayWidth;
    int frameHeight = mc.displayHeight;
    if (frameWidth <= 0 || frameHeight <= 0) {
      request.fail("no client framebuffer — is the client in a world?");
      return;
    }
    if (frameWidth != request.frameWidth() || frameHeight != request.frameHeight()) {
      // LWJGL 2 has no setWindowed: ask Display directly, and let the resize be reported back over
      // the next frames.
      try {
        Display.setDisplayMode(new DisplayMode(request.frameWidth(), request.frameHeight()));
      } catch (org.lwjgl.LWJGLException e) {
        request.fail("could not resize the client window: " + e.getMessage());
        return;
      }
      request.retry(
          "the client window is "
              + frameWidth
              + "x"
              + frameHeight
              + ", not the "
              + request.frameWidth()
              + "x"
              + request.frameHeight()
              + " the screenshot profile pins it to");
      return;
    }

    int guiScale = new ScaledResolution(mc).getScaleFactor();
    int x = 0;
    int y = 0;
    int cropWidth = frameWidth;
    int cropHeight = frameHeight;
    if (request.hasRegion()) {
      long unit =
          request.unit().equals(net.minecheck.platform.api.ScreenshotRequest.UNIT_GUI)
              ? guiScale
              : 1;
      long left = (long) request.x() * unit;
      long top = (long) request.y() * unit;
      long right = left + (long) request.width() * unit;
      long bottom = top + (long) request.height() * unit;
      long fromX = Math.max(0, left);
      long fromY = Math.max(0, top);
      long toX = Math.min(frameWidth, right);
      long toY = Math.min(frameHeight, bottom);
      if (toX <= fromX || toY <= fromY) {
        request.fail(
            "region "
                + request.width()
                + "x"
                + request.height()
                + " at ("
                + request.x()
                + ", "
                + request.y()
                + ") "
                + request.unit()
                + " has no overlap with the "
                + frameWidth
                + "x"
                + frameHeight
                + " frame");
        return;
      }
      x = (int) fromX;
      y = (int) fromY;
      cropWidth = (int) (toX - fromX);
      cropHeight = (int) (toY - fromY);
    }

    int[] frame;
    try {
      frame = readFramebuffer();
    } catch (RuntimeException e) {
      request.fail("framebuffer read failed: " + e.getMessage());
      return;
    }
    if (screenshotRenderer == null) {
      screenshotRenderer = GL11.glGetString(GL11.GL_RENDERER);
    }
    request.running();
    if (!write) {
      readFrame(request, frame, frameWidth, x, y, cropWidth, cropHeight, guiScale);
      return;
    }
    java.nio.file.Path targetPath =
        new File(gameDir(), "screenshots")
            .toPath()
            .resolve(request.name() + ".png")
            .toAbsolutePath();
    writeScreenshot(
        request, frame, frameWidth, frameHeight, x, y, cropWidth, cropHeight, guiScale, targetPath);
  }

  /** Crops, downscales and digests the capture in memory — off the render thread. */
  private static void readFrame(
      net.minecheck.platform.api.ScreenshotRequest request,
      int[] source,
      int sourceWidth,
      int x,
      int y,
      int cropWidth,
      int cropHeight,
      int guiScale) {
    SCREENSHOT_WRITER.execute(
        () -> {
          try {
            int outWidth = Math.max(1, (int) Math.round(cropWidth * request.scale()));
            int outHeight = Math.max(1, (int) Math.round(cropHeight * request.scale()));
            int[] pixels = new int[outWidth * outHeight];
            int at = 0;
            for (int dy = 0; dy < outHeight; dy++) {
              int sy = y + (int) ((long) dy * cropHeight / outHeight);
              for (int dx = 0; dx < outWidth; dx++) {
                int sx = x + (int) ((long) dx * cropWidth / outWidth);
                pixels[at++] = source[sy * sourceWidth + sx];
              }
            }
            request.succeedFrame(
                outWidth,
                outHeight,
                pixels,
                net.minecheck.common.screenshot.Digests.sha256(pixels),
                guiScale);
          } catch (RuntimeException e) {
            request.fail(
                "framebuffer read failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
          }
        });
  }

  /** Encodes a frame captured in memory and writes it — off the render thread. */
  private static void encodeFrame(
      net.minecheck.platform.api.ScreenshotRequest request, java.nio.file.Path target) {
    SCREENSHOT_WRITER.execute(
        () -> {
          try {
            int outWidth = request.outWidth();
            int outHeight = request.outHeight();
            writePng(
                request,
                request.pixels(),
                outWidth,
                outHeight,
                outWidth,
                target,
                request.guiScale());
          } catch (Exception e) {
            request.fail(
                "framebuffer read failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
          }
        });
  }

  /** Crops, downscales, PNG-encodes and writes the capture — all off the render thread. */
  private static void writeScreenshot(
      net.minecheck.platform.api.ScreenshotRequest request,
      int[] source,
      int frameWidth,
      int frameHeight,
      int x,
      int y,
      int cropWidth,
      int cropHeight,
      int guiScale,
      java.nio.file.Path target) {
    SCREENSHOT_WRITER.execute(
        () -> {
          try {
            int outWidth = Math.max(1, (int) Math.round(cropWidth * request.scale()));
            int outHeight = Math.max(1, (int) Math.round(cropHeight * request.scale()));
            int[] pixels = new int[outWidth * outHeight];
            int at = 0;
            for (int dy = 0; dy < outHeight; dy++) {
              int sy = y + (int) ((long) dy * cropHeight / outHeight);
              for (int dx = 0; dx < outWidth; dx++) {
                int sx = x + (int) ((long) dx * cropWidth / outWidth);
                pixels[at++] = source[sy * frameWidth + sx];
              }
            }
            writePng(request, pixels, outWidth, outHeight, frameWidth, target, guiScale);
          } catch (Exception e) {
            request.fail(
                "framebuffer read failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
          }
        });
  }

  /**
   * Encodes packed ARGB pixels as a PNG and reports the file through {@code request}. The frame
   * report is rewritten by the capture so a capture is reproducible from its own metadata.
   */
  private static void writePng(
      net.minecheck.platform.api.ScreenshotRequest request,
      int[] pixels,
      int width,
      int height,
      int frameWidth,
      java.nio.file.Path target,
      int guiScale)
      throws java.io.IOException {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, width, height, pixels, 0, width);
    java.nio.file.Path directory = target.getParent();
    java.nio.file.Files.createDirectories(directory);
    java.nio.file.Path tmp = directory.resolve(target.getFileName() + ".part");
    javax.imageio.ImageIO.write(image, "png", tmp.toFile());
    java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    byte[] bytes = java.nio.file.Files.readAllBytes(target);
    net.minecheck.common.screenshot.ScreenshotProfile.write(
        directory,
        request.frameWidth(),
        request.frameHeight(),
        request.guiScale(),
        request.scale(),
        request.chatVisible(),
        screenshotRenderer);
    request.succeed(
        target, width, height, bytes.length, net.minecheck.common.screenshot.Digests.sha256(bytes));
  }

  @Override
  public void openChat(String mode) {
    boolean command = mode != null && mode.equalsIgnoreCase("command");
    onClientThread(() -> mc().displayGuiScreen(new GuiChat(command ? "/" : "")));
  }

  @Override
  public void joinLocalWorld(String worldName) {
    Minecraft mc = mc();
    File save = new File(new File(gameDir(), "saves"), worldName + "/level.dat");
    if (!save.isFile()) {
      throw new IllegalArgumentException("World not found: " + worldName);
    }
    onClientThread(
        () ->
            mc.launchIntegratedServer(
                worldName,
                worldName,
                new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT)));
  }

  /**
   * The options a {@code createWorld} call asked for, applied once the integrated server exists.
   */
  private volatile CreateWorldOptions pendingWorldOptions;

  /**
   * Applies the rules and difficulty a {@code createWorld} call asked for.
   *
   * <p>1.12.2's {@code WorldSettings} carries only the seed, the game type, whether map features
   * generate and the level type, so the game rules and the difficulty are set on the integrated
   * server once it exists, from the client tick hook.
   */
  private void applyPendingWorldSettings(Minecraft mc) {
    CreateWorldOptions options = pendingWorldOptions;
    if (options == null) {
      return;
    }
    MinecraftServer server = mc.getIntegratedServer();
    if (server == null) {
      return;
    }
    pendingWorldOptions = null;
    WorldServer overworld = server.getWorld(0);
    if (overworld != null) {
      GameRules rules = overworld.getGameRules();
      if (!options.mobSpawning()) {
        rules.setOrCreateGameRule("doMobSpawning", "false");
      }
      if (!options.daylightCycle()) {
        rules.setOrCreateGameRule("doDaylightCycle", "false");
      }
      if (!options.weatherCycle()) {
        rules.setOrCreateGameRule("doWeatherCycle", "false");
      }
    }
    // No insomnia rule to set: 1.12.2 has no such game rule (it arrives in 1.15).
    server.setDifficultyForAllWorlds(difficulty(options.difficulty()));
  }

  @Override
  public void createWorld(CreateWorldOptions options) {
    // 1.12.2 has no dimension set to swap a generator into: the world is created from a
    // WorldSettings whose level type selects flat generation and whose generator options are the
    // same declaration the harness writes into the dedicated server's `generator-settings`.
    WorldSettings settings =
        new WorldSettings(
            options.seed(),
            gameMode(options.gamemode()),
            options.structures(),
            false,
            WorldType.FLAT);
    settings.setGeneratorOptions(FlatWorldSettings.declaration());
    if (options.allowCheats()) {
      settings.enableCommands();
    }
    pendingWorldOptions = options;
    Minecraft mc = mc();
    onClientThread(() -> mc.launchIntegratedServer(options.name(), options.name(), settings));
  }

  @Override
  public void joinServer(String address) {
    ServerAddress serverAddress = ServerAddress.fromString(address);
    onClientThread(
        () -> {
          try {
            Minecraft mc = mc();
            mc.displayGuiScreen(
                new GuiConnecting(
                    mc.currentScreen, mc, serverAddress.getIP(), serverAddress.getPort()));
            LOG.info(
                String.format(
                    "joinServer: GuiConnecting(%s:%s)",
                    serverAddress.getIP(), serverAddress.getPort()));
          } catch (Throwable t) {
            LOG.log(Level.SEVERE, "joinServer: failed to connect", t);
          }
        });
  }

  @Override
  public void leaveWorld() {
    onClientThread(
        () -> {
          Minecraft mc = mc();
          if (mc.world != null) {
            mc.world.sendQuittingDisconnectingPacket();
          }
          // loadWorld(null) alone leaves the "Saving world" progress screen up; the main menu is
          // what makes isOnTitleScreen() true after leaving.
          mc.loadWorld(null);
          mc.displayGuiScreen(new GuiMainMenu());
        });
  }

  @Override
  public void quit() {
    onClientThread(mc()::shutdown);
  }

  @Override
  public boolean isInWorld() {
    return mc().player != null;
  }

  @Override
  public boolean isWorldReady() {
    return mc().player != null && mc().currentScreen == null;
  }

  @Override
  public boolean isOnTitleScreen() {
    return mc().currentScreen instanceof GuiMainMenu;
  }

  @Override
  public boolean isConnecting() {
    return mc().currentScreen instanceof GuiConnecting;
  }

  @Override
  public boolean isLoggingIn() {
    Minecraft mc = mc();
    if (mc.player != null) {
      return false;
    }
    NetHandlerPlayClient connection = mc.getConnection();
    if (connection == null) {
      return false;
    }
    // 1.12.2 leaves the play handler behind after the world is left, so its mere presence is not a
    // login: the channel is what says the handshake is still live. Calling a stale handler a login
    // made every later join look like an attempt already in flight, and the client sat out its
    // whole join deadline without ever opening a connection.
    NetworkManager manager = connection.getNetworkManager();
    return manager != null && manager.isChannelOpen();
  }

  @Override
  public boolean isConnectFailed() {
    return mc().currentScreen instanceof GuiDisconnected;
  }

  /**
   * 1.12.2 blocks the client thread inside its splash screen before the mod's tick hooks run, so
   * anything that can ask this question is already past the boot.
   */
  @Override
  public boolean isBootComplete() {
    return true;
  }

  /** 1.12.2 exposes no chunk-load progress to the client; -1 leaves the caller on its deadline. */
  @Override
  public float worldLoadProgress() {
    return -1f;
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer server = mc().getIntegratedServer();
    if (server == null) {
      LOG.warning("worldGenSettled: not in a local/integrated world; treating as settled");
      return true;
    }
    WorldServer overworld = server.getWorld(0);
    if (overworld == null) {
      LOG.warning("worldGenSettled: no overworld loaded; treating as settled");
      return true;
    }
    // 1.12.2 exposes no pending-task count, so settled means the area the level generated with is
    // there: the chunk its spawn point sits in. Asking about chunk 0,0 instead never settles here —
    // a fresh 1.12.2 world puts its spawn away from the origin, so the server keeps only the spawn
    // chunks loaded and 0,0 stays generated-but-unloaded (`chunkExists` answers for loaded chunks).
    // Fixtures reach the origin by teleporting and then wait for that chunk themselves.
    net.minecraft.util.math.BlockPos spawn = overworld.getSpawnPoint();
    boolean settled =
        overworld.getChunkProvider().chunkExists(spawn.getX() >> 4, spawn.getZ() >> 4);
    LOG.info(
        String.format(
            "worldGenSettled: spawn chunk (%d,%d) generated=%s",
            spawn.getX() >> 4, spawn.getZ() >> 4, settled));
    return settled;
  }

  // ------------------------------------------------------------ input

  /** Press or release a key code through the binding state a real keypress sets. */
  private void pressKeyCode(int code, boolean down) {
    KeyBinding.setKeyBindState(code, down);
    if (down) {
      KeyBinding.onTick(code);
    }
  }

  /**
   * Resolves a script key name to an LWJGL key code. Accepts either a key mapping id ("key.jump")
   * or a key name ("F3"). Returns {@code Keyboard.KEY_NONE} when unresolvable.
   */
  private int resolveKey(String key) {
    if (key.startsWith("key.")) {
      // Every registered binding, not `gameSettings.keyBindings`: that array leaves out bindings a
      // script still names, and 1.12.2 keeps the hotbar slots (`key.hotbar.1` … `key.hotbar.9`)
      // outside it. Looking there made `keyPress("key.hotbar.1")` answer false — a tap that never
      // happened, reported as an unresolvable name only by `keyDown`.
      for (KeyBinding mapping : mc().gameSettings.keyBindings) {
        if (mapping.getKeyDescription().equals(key)) {
          return mapping.getKeyCode();
        }
      }
      // The hotbar slots are the one family 1.12.2 leaves out of `gameSettings.keyBindings`, so
      // they are read from the field that holds them. Without this, `keyPress("key.hotbar.1")`
      // answered false — a tap that never happened.
      if (key.startsWith("key.hotbar.")) {
        try {
          int slot = Integer.parseInt(key.substring("key.hotbar.".length()));
          if (slot >= 1 && slot <= mc().gameSettings.keyBindsHotbar.length) {
            return mc().gameSettings.keyBindsHotbar[slot - 1].getKeyCode();
          }
        } catch (NumberFormatException e) {
          // Not a hotbar slot; fall through to the unresolvable-name answer below.
        }
      }
      return Keyboard.KEY_NONE;
    }
    int code = Keyboard.getKeyIndex(key.toUpperCase(Locale.ROOT));
    if (code == Keyboard.KEY_NONE && key.equalsIgnoreCase("enter")) {
      // LWJGL calls the key RETURN, so a script's "enter" resolved to nothing and the chat screen
      // never received the submit that closes it.
      code = Keyboard.KEY_RETURN;
    }
    if (code == Keyboard.KEY_NONE) {
      LOG.warning(String.format("keyDown: unresolvable key name '%s'", key));
    }
    return code;
  }

  @Override
  public boolean keyDown(String key) {
    int code = resolveKey(key);
    if (code == Keyboard.KEY_NONE) {
      return false;
    }
    onClientThread(
        () ->
            net.minecheck.platform.api.InjectedInput.run(
                () -> {
                  GuiScreen screen = mc().currentScreen;
                  if (screen != null) {
                    // A real press reaches the open screen, not the world bindings: 1.12.2 closes a
                    // container from `GuiScreen.keyTyped`, which a synthetic binding state never
                    // reaches. Escape is the extreme case (no binding at all), and E on an open
                    // inventory took the same dead path, leaving the screen up for every read
                    // after it.
                    typeKeyIntoScreen(screen, code);
                    return;
                  }
                  pressKeyCode(code, true);
                }));
    return true;
  }

  /**
   * Delivers a synthetic key to the open screen the way a real one arrives: {@code keyTyped} is how
   * 1.12.2 closes a container (Escape or the screen's own key), and it is protected, so it is
   * reached reflectively rather than through a binding that a screen never consults.
   */
  private static void typeKeyIntoScreen(GuiScreen screen, int code) {
    try {
      Method typed = GuiScreen.class.getDeclaredMethod("keyTyped", char.class, int.class);
      typed.setAccessible(true);
      typed.invoke(screen, (char) 0, code);
    } catch (ReflectiveOperationException e) {
      LOG.log(Level.WARNING, "keyDown: the open screen did not take the key", e);
    }
  }

  @Override
  public void keyUp(String key) {
    int code = resolveKey(key);
    if (code != Keyboard.KEY_NONE) {
      onClientThread(
          () -> net.minecheck.platform.api.InjectedInput.run(() -> pressKeyCode(code, false)));
    }
  }

  /**
   * 1.12.2 binds the first three mouse buttons to {@code -100 + button} — attack {@code -100}, use
   * {@code -99}, pick block {@code -98} — so a synthetic click is the same binding-state dispatch a
   * real one produces. Subtracting the button instead (the sign this used to have) addressed {@code
   * -102} for a middle click, a code no binding answers to, so pick-block never ran.
   */
  private void pressMouseButton(int button, boolean down) {
    int code = -100 + button;
    KeyBinding.setKeyBindState(code, down);
    if (down) {
      KeyBinding.onTick(code);
    }
  }

  @Override
  public boolean mouseDown(int button) {
    onClientThread(
        () -> net.minecheck.platform.api.InjectedInput.run(() -> pressMouseButton(button, true)));
    return true;
  }

  @Override
  public void mouseUp(int button) {
    onClientThread(
        () -> net.minecheck.platform.api.InjectedInput.run(() -> pressMouseButton(button, false)));
  }

  @Override
  public net.minecheck.platform.api.PlayerInfo getPlayer() {
    Minecraft mc = mc();
    if (mc.player == null) {
      return null;
    }
    net.minecraft.entity.player.EntityPlayer p = mc.player;
    String dimension = "minecraft:" + p.world.provider.getDimensionType().getName();
    return new net.minecheck.platform.api.PlayerInfo(
        p.getName(), p.posX, p.posY, p.posZ, dimension);
  }

  @Override
  public void setLook(float pitch, float yaw) {
    Minecraft mc = mc();
    onClientThread(
        () -> {
          if (mc.player != null) {
            mc.player.rotationPitch = pitch;
            mc.player.rotationYaw = yaw;
          }
        });
  }

  @Override
  public void lookAt(double x, double y, double z) {
    Minecraft mc = mc();
    onClientThread(
        () -> {
          if (mc.player == null) {
            return;
          }
          net.minecraft.entity.player.EntityPlayer p = mc.player;
          double dx = x - p.posX;
          double dy = y - (p.posY + p.getEyeHeight());
          double dz = z - p.posZ;
          double horizontal = Math.sqrt(dx * dx + dz * dz);
          p.rotationYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
          p.rotationPitch = (float) -(Math.atan2(dy, horizontal) * 180.0 / Math.PI);
        });
  }

  @Override
  public net.minecheck.platform.api.BlockPosition getTargetBlock(String entityName) {
    Minecraft mc = mc();
    if (mc.player == null || !mc.player.getName().equals(entityName) || mc.world == null) {
      return null;
    }
    RayTraceResult hit = mc.player.rayTrace(5.0, 1.0f);
    if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
      return null;
    }
    BlockPos pos = hit.getBlockPos();
    return new net.minecheck.platform.api.BlockPosition(pos.getX(), pos.getY(), pos.getZ());
  }

  @Override
  public net.minecheck.platform.api.BlockPosition getCrosshairBlock() {
    Minecraft mc = mc();
    if (mc.player == null || mc.world == null || mc.objectMouseOver == null) {
      return null;
    }
    RayTraceResult hit = mc.objectMouseOver;
    if (hit.typeOfHit != RayTraceResult.Type.BLOCK) {
      return null;
    }
    BlockPos pos = hit.getBlockPos();
    return new net.minecheck.platform.api.BlockPosition(pos.getX(), pos.getY(), pos.getZ());
  }

  @Override
  public java.util.List<net.minecheck.platform.api.EntityInfo> findNearbyEntities(String type) {
    Minecraft mc = mc();
    WorldClient level = mc.world;
    if (level == null) {
      return java.util.List.of();
    }
    ResourceLocation typeId =
        type == null ? null : new ResourceLocation(type.toLowerCase(Locale.ROOT));
    java.util.List<net.minecheck.platform.api.EntityInfo> out = new java.util.ArrayList<>();
    for (Object raw : level.loadedEntityList) {
      Entity entity = (Entity) raw;
      ResourceLocation actual = EntityList.getKey(entity);
      if (typeId != null && !typeId.equals(actual)) {
        continue;
      }
      // Scoreboard tags are server-side data: the tag set is empty for every client-side entity
      // (vanilla never syncs it), so this is a faithful copy of what the client actually knows.
      out.add(
          new net.minecheck.platform.api.EntityInfo(
              entity.getEntityId(),
              actual == null ? "" : actual.toString(),
              entity.posX,
              entity.posY,
              entity.posZ,
              new java.util.HashSet<>(entity.getTags())));
    }
    return java.util.List.copyOf(out);
  }

  @Override
  public void runCommand(String command) {
    Minecraft mc = mc();
    if (mc.player == null || mc.getConnection() == null) {
      throw new IllegalStateException("not connected to a world/server");
    }
    String cmd =
        LegacyCommandShim.translate(command.startsWith("/") ? command.substring(1) : command);
    // Executed on the integrated server as the local player's own server-side entity rather than
    // sent as a chat packet: the packet path resolved `@s` against a sender without an entity
    // ("Entity '@s' cannot be found"), so a script's teleport silently did nothing. On a remote
    // server there is nothing else to run it against, so the packet is the only option there.
    net.minecraft.server.MinecraftServer server = mc.getIntegratedServer();
    net.minecraft.entity.player.EntityPlayerMP self =
        server == null ? null : server.getPlayerList().getPlayerByUUID(mc.player.getUniqueID());
    if (self == null) {
      mc.player.sendChatMessage("/" + cmd);
      return;
    }
    java.util.concurrent.CompletableFuture<Void> done =
        new java.util.concurrent.CompletableFuture<>();
    server.addScheduledTask(
        () -> {
          try {
            Integer rule = LegacyCommandShim.gameruleValue(server.getWorld(0), cmd);
            Integer entity = rule == null ? LegacyCommandShim.executeIfEntity(self, cmd) : null;
            Integer conditional =
                rule == null && entity == null
                    ? LegacyCommandShim.executeIfBlock(server.getWorld(0), cmd)
                    : null;
            if (rule == null && entity == null && conditional == null) {
              server.getCommandManager().executeCommand(self, cmd);
            }
          } finally {
            done.complete(null);
          }
        });
    try {
      done.get(30, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("command did not run on the integrated server: " + cmd, e);
    }
  }

  @Override
  public net.minecheck.platform.api.BlockInfo getBlock(
      net.minecheck.platform.api.BlockPosition pos) {
    Minecraft mc = mc();
    if (mc.world == null) {
      throw new IllegalStateException("not in a world");
    }
    BlockPos blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
    if (!mc.world.isBlockLoaded(blockPos)) {
      return null;
    }
    net.minecraft.block.state.IBlockState state = mc.world.getBlockState(blockPos);
    TileEntity blockEntity = mc.world.getTileEntity(blockPos);
    java.util.Map<String, Object> nbt =
        blockEntity == null ? null : nbtToMap(blockEntity.writeToNBT(new NBTTagCompound()));
    return new net.minecheck.platform.api.BlockInfo(
        LegacyCommandShim.modernId(Block.REGISTRY.getNameForObject(state.getBlock()).toString()),
        nbt);
  }

  @Override
  public Integer groundHeight(int x, int z) {
    Minecraft mc = mc();
    if (mc.world == null) {
      throw new IllegalStateException("not in a world");
    }
    if (!isChunkLoaded(x >> 4, z >> 4)) {
      return null;
    }
    // 1.12.2's heightmap already reports the first air block above the surface, which is the height
    // the newer lines get from MOTION_BLOCKING plus one.
    return mc.world.getHeight(x, z);
  }

  @Override
  public boolean isChunkLoaded(int chunkX, int chunkZ) {
    Minecraft mc = mc();
    // Asked of the loaded block rather than the chunk provider: 1.12.2's client provider has no
    // chunk-presence query, while World.isBlockLoaded answers "is this column in a chunk the server
    // has sent me" — exactly the question this is.
    // The two-argument form with allowEmpty=false: the one-argument call answers true for any
    // position inside the world whether or not that chunk was ever sent, so a script waiting for a
    // chunk it had never received saw it "arrive" and then read an empty column (height 0).
    return mc.world != null
        && mc.world.isBlockLoaded(new BlockPos(chunkX << 4, 0, chunkZ << 4), false);
  }

  // ------------------------------------------------------------ GUI / inventory

  @Override
  public void useItemOnTarget() {
    Minecraft mc = mc();
    if (mc.player == null || mc.playerController == null || mc.world == null) {
      throw new IllegalStateException("not in a world");
    }
    RayTraceResult hit = mc.objectMouseOver;
    if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
      mc.playerController.processRightClickBlock(
          mc.player, mc.world, hit.getBlockPos(), hit.sideHit, hit.hitVec, EnumHand.MAIN_HAND);
    }
  }

  @Override
  public boolean clickSlot(int slot, int button, String actionType) {
    Minecraft mc = mc();
    if (!(mc.currentScreen instanceof GuiContainer)) {
      return false;
    }
    Container menu = ((GuiContainer) mc.currentScreen).inventorySlots;
    mc.playerController.windowClick(menu.windowId, slot, button, clickType(actionType), mc.player);
    return true;
  }

  private static ClickType clickType(String type) {
    switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
      case "quick_move":
        return ClickType.QUICK_MOVE;
      case "swap":
        return ClickType.SWAP;
      case "clone":
        return ClickType.CLONE;
      case "throw":
        return ClickType.THROW;
      case "quick_craft":
        return ClickType.QUICK_CRAFT;
      case "pickup_all":
        return ClickType.PICKUP_ALL;
      default:
        return ClickType.PICKUP;
    }
  }

  @Override
  public boolean clickGui(int button, double x, double y) {
    GuiScreen screen = mc().currentScreen;
    if (screen == null) {
      return false;
    }
    return invokeGui(
        screen,
        "mouseClicked",
        new Class<?>[] {int.class, int.class, int.class},
        (int) x,
        (int) y,
        button);
  }

  @Override
  public boolean clickGuiElement(String elementId, int button, int index) {
    GuiScreen screen = mc().currentScreen;
    if (screen == null) {
      return false;
    }
    String wanted = elementId.trim().toLowerCase(Locale.ROOT);
    if (wanted.isEmpty()) {
      return false;
    }
    // Label matches beat class-name matches: the label is the same string on every loader, the
    // class name is not, so the class form is a contains match.
    java.util.List<GuiButton> byLabel = new java.util.ArrayList<>();
    java.util.List<GuiButton> byClass = new java.util.ArrayList<>();
    for (GuiButton widget : guiButtons(screen)) {
      String label = widget.displayString.trim().toLowerCase(Locale.ROOT);
      if (label.equals(wanted)) {
        byLabel.add(widget);
      } else if (widget.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains(wanted)) {
        byClass.add(widget);
      }
    }
    java.util.List<GuiButton> matches = byLabel.isEmpty() ? byClass : byLabel;
    int at = index <= 1 ? 0 : index - 1;
    if (at >= matches.size()) {
      return false;
    }
    GuiButton target = matches.get(at);
    int clickX = target.x + target.width / 2;
    int clickY = target.y + target.height / 2;
    // A press alone leaves the button down: 1.12.2 runs its action on release, so the screen is
    // given the whole click the way a real one arrives (the sign editor's Done saves and closes
    // from that action, and stayed open while only the press was delivered).
    invokeGui(
        screen,
        "mouseClicked",
        new Class<?>[] {int.class, int.class, int.class},
        clickX,
        clickY,
        0);
    invokeGui(
        screen,
        "mouseReleased",
        new Class<?>[] {int.class, int.class, int.class},
        clickX,
        clickY,
        0);
    return true;
  }

  /**
   * Every {@link GuiButton} the screen holds. 1.12.2's {@code GuiScreen} keeps them in a private
   * list with a mapping-dependent name, so the field is found by its element type.
   */
  private static java.util.List<GuiButton> guiButtons(GuiScreen screen) {
    java.util.List<GuiButton> out = new java.util.ArrayList<>();
    for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (!java.util.List.class.isAssignableFrom(field.getType())) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object value = field.get(screen);
          if (!(value instanceof java.util.List)) {
            continue;
          }
          for (Object element : (java.util.List<?>) value) {
            if (element instanceof GuiButton) {
              out.add((GuiButton) element);
            }
          }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // A field this module cannot read is simply not a candidate.
        }
      }
    }
    return out;
  }

  @Override
  public boolean typeText(String text, String target) {
    Minecraft mc = mc();
    String kind = target == null ? "" : target.toLowerCase(Locale.ROOT);
    GuiScreen screen = mc.currentScreen;
    if (screen == null) {
      return false;
    }
    GuiTextField field = findEditBox(screen);
    if (field == null) {
      // Screens that own their text editing have no edit-box field — the sign editor is one, and it
      // handles the characters itself, so dispatch them at the screen instead.
      boolean typed = false;
      for (int i = 0; i < text.length(); i++) {
        if (deliverGui(
            screen, "keyTyped", new Class<?>[] {char.class, int.class}, text.charAt(i), 0)) {
          typed = true;
        }
      }
      return typed;
    }
    if (!field.isFocused()) {
      field.setFocused(true);
    }
    if (kind.equals("command") && !field.getText().startsWith("/")) {
      field.setText("/" + field.getText());
    }
    boolean dispatched = false;
    for (int i = 0; i < text.length(); i++) {
      if (deliverGui(
          screen, "keyTyped", new Class<?>[] {char.class, int.class}, text.charAt(i), 0)) {
        dispatched = true;
      }
    }
    return dispatched;
  }

  /** The screen's focused edit box, or its first visible edit box when none is focused. */
  private static GuiTextField findEditBox(GuiScreen screen) {
    GuiTextField first = null;
    for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (!GuiTextField.class.isAssignableFrom(field.getType())) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object value = field.get(screen);
          if (!(value instanceof GuiTextField) || !((GuiTextField) value).getVisible()) {
            continue;
          }
          GuiTextField box = (GuiTextField) value;
          if (box.isFocused()) {
            return box;
          }
          if (first == null) {
            first = box;
          }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // A field this module cannot read is simply not a candidate.
        }
      }
    }
    return first;
  }

  /**
   * Invokes a protected {@link GuiScreen} hook on {@code screen}, answering whether it returned
   * true.
   */
  /**
   * True when the screen took the call. {@code keyTyped} returns nothing on this line, so a
   * completed call is the only signal there is — without it, typing into a screen that has no
   * edit-box field (the sign editor) delivered every character and still reported failure.
   */
  private static boolean deliverGui(
      GuiScreen screen, String name, Class<?>[] parameterTypes, Object... args) {
    try {
      Method method = GuiScreen.class.getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      Object result = method.invoke(screen, args);
      return !(result instanceof Boolean) || (Boolean) result;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  private static boolean invokeGui(
      GuiScreen screen, String name, Class<?>[] parameterTypes, Object... args) {
    try {
      Method method = GuiScreen.class.getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      Object result = method.invoke(screen, args);
      return result instanceof Boolean && (Boolean) result;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  @Override
  public boolean openSignEditor(net.minecheck.platform.api.BlockPosition pos) {
    Minecraft mc = mc();
    if (mc.player == null || mc.world == null) {
      throw new IllegalStateException("not in a world");
    }
    TileEntity be = mc.world.getTileEntity(new BlockPos(pos.x(), pos.y(), pos.z()));
    if (!(be instanceof TileEntitySign)) {
      return false;
    }
    onClientThread(() -> mc.player.openEditSign((TileEntitySign) be));
    return true;
  }

  @Override
  public boolean isScreenOpen(String screenId) {
    GuiScreen screen = mc().currentScreen;
    if (screen == null) {
      return false;
    }
    if (screenId == null || screenId.isEmpty()) {
      return screen instanceof GuiContainer;
    }
    switch (screenId) {
      case "inventory":
        // Creative mode opens GuiContainerCreative, which does not extend GuiInventory.
        return screen instanceof GuiInventory || screen instanceof GuiContainerCreative;
      case "chat":
        return screen instanceof GuiChat;
      case "beacon":
        return screen instanceof GuiBeacon;
      case "sign":
        return screen instanceof GuiEditSign;
      case "chest":
        return screen instanceof GuiChest;
      case "crafting":
        return screen instanceof GuiCrafting;
      case "furnace":
        return screen instanceof GuiFurnace;
      default:
        return false;
    }
  }

  private Container openMenu(String screenId) {
    GuiScreen screen = mc().currentScreen;
    if (!(screen instanceof GuiContainer)) {
      throw new IllegalStateException("no container screen is open");
    }
    if (screenId != null && !screenId.isEmpty() && !isScreenOpen(screenId)) {
      throw new IllegalStateException(
          "open screen "
              + screen.getClass().getSimpleName()
              + " does not match '"
              + screenId
              + "'");
    }
    return ((GuiContainer) screen).inventorySlots;
  }

  @Override
  public int slotCount(String screenId) {
    try {
      return openMenu(screenId).inventorySlots.size();
    } catch (IllegalStateException e) {
      return 0;
    }
  }

  @Override
  public net.minecheck.platform.api.SlotInfo getSlot(int index, String screenId) {
    Container menu = openMenu(screenId);
    if (index < 0 || index >= menu.inventorySlots.size()) {
      throw new IllegalStateException(
          "slot index " + index + " out of range (0.." + (menu.inventorySlots.size() - 1) + ")");
    }
    ItemStack stack = menu.inventorySlots.get(index).getStack();
    if (stack.isEmpty()) {
      return new net.minecheck.platform.api.SlotInfo(true, null, 0, null);
    }
    NBTTagCompound nbt = stack.getTagCompound();
    return new net.minecheck.platform.api.SlotInfo(
        false,
        LegacyCommandShim.modernId(
            net.minecraft.item.Item.REGISTRY.getNameForObject(stack.getItem()).toString()),
        stack.getCount(),
        nbt == null ? null : nbtToMap(nbt));
  }

  private static java.util.Map<String, Object> nbtToMap(NBTTagCompound compound) {
    java.util.Map<String, Object> out = new java.util.HashMap<>();
    for (String key : compound.getKeySet()) {
      out.put(key, nbtValue(compound.getTag(key)));
    }
    return out;
  }

  private static Object nbtValue(NBTBase tag) {
    if (tag == null) {
      return null;
    }
    switch (tag.getId()) {
      case 1:
        return ((NBTTagByte) tag).getByte();
      case 2:
        return ((NBTTagShort) tag).getShort();
      case 3:
        return ((NBTTagInt) tag).getInt();
      case 4:
        return ((NBTTagLong) tag).getLong();
      case 5:
        return ((NBTTagFloat) tag).getFloat();
      case 6:
        return ((NBTTagDouble) tag).getDouble();
      case 7:
        {
          java.util.List<Object> out = new java.util.ArrayList<>();
          for (byte value : ((NBTTagByteArray) tag).getByteArray()) {
            out.add(value);
          }
          return out;
        }
      case 8:
        return ((NBTTagString) tag).getString();
      case 9:
        {
          NBTTagList list = (NBTTagList) tag;
          java.util.List<Object> out = new java.util.ArrayList<>();
          for (int i = 0; i < list.tagCount(); i++) {
            out.add(nbtValue(list.get(i)));
          }
          return out;
        }
      case 10:
        return nbtToMap((NBTTagCompound) tag);
      case 11:
        {
          java.util.List<Object> out = new java.util.ArrayList<>();
          for (int value : ((NBTTagIntArray) tag).getIntArray()) {
            out.add(value);
          }
          return out;
        }
      case 12:
        {
          // The long-array tag's accessor is not in every 1.12.2 build's MCP mapping set, so it is
          // read reflectively; a build without it simply leaves the key out of the map.
          java.util.List<Object> out = new java.util.ArrayList<>();
          try {
            Method longs = tag.getClass().getMethod("getLongArray");
            for (long value : (long[]) longs.invoke(tag)) {
              out.add(value);
            }
          } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
          }
          return out;
        }
      default:
        return null;
    }
  }

  private static GameType gameMode(String name) {
    switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
      case "survival":
        return GameType.SURVIVAL;
      case "adventure":
        return GameType.ADVENTURE;
      case "spectator":
        return GameType.SPECTATOR;
      default:
        return GameType.CREATIVE;
    }
  }

  private static EnumDifficulty difficulty(String name) {
    switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
      case "peaceful":
        return EnumDifficulty.PEACEFUL;
      case "easy":
        return EnumDifficulty.EASY;
      case "hard":
        return EnumDifficulty.HARD;
      default:
        return EnumDifficulty.NORMAL;
    }
  }

  /**
   * Translates a GLFW key code (what {@link net.minecheck.platform.api.ViewWindow} hands over) into
   * the LWJGL 2 key name {@link Keyboard} indexes by. Printable letters and digits map through
   * their own character; the named keys are listed explicitly.
   */
  private static String glfwKeyName(int key) {
    if (key >= 'A' && key <= 'Z') {
      return String.valueOf((char) key);
    }
    if (key >= '0' && key <= '9') {
      return String.valueOf((char) key);
    }
    if (key >= 290 && key <= 301) {
      return "F" + (key - 289);
    }
    switch (key) {
      case 32:
        return "SPACE";
      case 256:
        return "ESCAPE";
      case 257:
        return "RETURN";
      case 258:
        return "TAB";
      case 259:
        return "BACK";
      case 261:
        return "DELETE";
      case 262:
        return "RIGHT";
      case 263:
        return "LEFT";
      case 264:
        return "DOWN";
      case 265:
        return "UP";
      case 266:
        return "PRIOR";
      case 267:
        return "NEXT";
      case 268:
        return "HOME";
      case 269:
        return "END";
      case 340:
        return "LSHIFT";
      case 344:
        return "RSHIFT";
      case 341:
        return "LCONTROL";
      case 345:
        return "RCONTROL";
      case 342:
        return "LMENU";
      case 346:
        return "RMENU";
      default:
        return null;
    }
  }
}
