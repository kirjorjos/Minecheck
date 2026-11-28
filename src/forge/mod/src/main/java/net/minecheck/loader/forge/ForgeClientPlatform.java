package net.minecheck.loader.forge;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.common.config.GameWindowPolicy;
import net.minecheck.common.screenshot.Digests;
import net.minecheck.common.screenshot.ScreenshotProfile;
import net.minecheck.platform.api.BlockInfo;
import net.minecheck.platform.api.BlockPosition;
import net.minecheck.platform.api.CameraPlatform;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecheck.platform.api.EntityInfo;
import net.minecheck.platform.api.InjectedInput;
import net.minecheck.platform.api.PlayerInfo;
import net.minecheck.platform.api.ScreenshotRequest;
import net.minecheck.platform.api.SlotInfo;
import net.minecheck.platform.api.ViewWindow;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.CPacketChatMessage;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The client-side half of the Legacy Fabric platform.
 *
 * <p>It is the same platform the Legacy Fabric module carries, on this line's names, with two
 * differences Forge forces: there is a client-setup event, so the camera's key binding is
 * registered there, and the input and sound hooks are Forge events rather than mixins. Every
 * user-visible behaviour — world entry, input, commands, screens, slots, captures — is the same,
 * which is what the shared suites check.
 *
 * <p>The original 1.12.2 Forge platform had two differences the loader forces: there is no
 * client-setup event and no key-binding registry, so the camera's toggle is polled rather than
 * registered, and there is no event bus, so the input and sound hooks are mixins. Every
 * user-visible behaviour — world entry, input, commands, screens, slots, captures — is the same,
 * which is what the shared suites check.
 */
public final class ForgeClientPlatform implements ClientPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Client ticks between frames pushed to the view window (~10 fps at 20 ticks a second). */
  private static final int VIEW_WINDOW_TICK_INTERVAL = 2;

  private final ForgeCameraPlatform cameraPlatform;
  private final Path configDir;

  /** Set once this line's pause-on-focus-loss has been turned off for the run. */
  private boolean unfocusedPlayAllowed;

  /** Set once the window handling has been settled (or the config said not to). */
  private boolean windowSettled;

  /** Set by {@link #leaveWorld()} and applied by the client tick. */
  private volatile boolean leaveRequested;

  /** The options a {@code createWorld} call asked for, applied once the server exists. */
  private volatile CreateWorldOptions pendingWorldOptions;

  /** Ticks still to elapse before a reload is reported settled; negative when none is pending. */
  private volatile int reloadTicksRemaining = -1;

  private volatile boolean vanillaCameraRestored;

  private volatile ViewWindow viewWindow;

  private int viewWindowTicks;

  /** Frames the client has finished drawing, counted by the loader's frame hook. */
  private static final java.util.concurrent.atomic.AtomicLong DRAWN_FRAMES =
      new java.util.concurrent.atomic.AtomicLong();

  /** The frame at which the pending capture cleared the chat, in {@link #DRAWN_FRAMES} terms. */
  private long chatClearedAtFrame;

  /** GL_RENDERER of the client, recorded in profile.json so renderer drift is diagnosable. */
  private static volatile String screenshotRenderer;

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

  ForgeClientPlatform(Path configDir) {
    this.configDir = configDir;
    this.cameraPlatform = new ForgeCameraPlatform(this);
  }

  /** The running client, or {@code null} before one exists. */
  public static Minecraft client() {
    return Minecraft.getMinecraft();
  }

  private static Minecraft mc() {
    return Minecraft.getMinecraft();
  }

  /** Records that the client finished drawing a frame. */
  public static void notifyFrameDrawn() {
    markClientThread();
    DRAWN_FRAMES.incrementAndGet();
  }

  /** The thread the client ticks and draws on, learned from the tick and frame hooks. */
  private static volatile Thread clientThread;

  /**
   * Marks the calling thread as the client's.
   *
   * <p>Needed because this line has no {@code isOnThread} and the client is not itself a thread to
   * compare against. The tick hook is the reliable caller: the frame hook only fires once the
   * renderer is up, and a leave or quit before the first frame would otherwise never apply.
   */
  static void markClientThread() {
    clientThread = Thread.currentThread();
  }

  private static boolean isClientThread() {
    return clientThread == Thread.currentThread();
  }

  @Override
  public CameraPlatform camera() {
    return cameraPlatform;
  }

  /** Key bindings are a client concern; the entrypoint calls this on the first client tick. */
  void installCameraBindings() {
    cameraPlatform.registerBindings();
  }

  /** Turns off this line's pause-on-focus-loss, which a headless run would otherwise trip. */
  void allowUnfocusedPlay() {
    if (unfocusedPlayAllowed) {
      return;
    }
    mc().gameSettings.pauseOnLostFocus = false;
    unfocusedPlayAllowed = true;
  }

  /** Reports the one window difference this line has: LWJGL 2 cannot hide a window. */
  void hideWindowIfConfigured() {
    if (windowSettled) {
      return;
    }
    windowSettled = true;
    if (!GameWindowPolicy.hide(configDir)) {
      return;
    }
    LOG.warning(
        "Minecheck: hideGameWindow is on, but this line's LWJGL 2 has no hidden window — the game"
            + " window stays mapped (pause-on-focus-loss is still off)");
  }

  private static void onClientThread(Runnable r) {
    Minecraft client = mc();
    if (client == null) {
      return;
    }
    if (isClientThread()) {
      r.run();
    } else {
      client.addScheduledTask(
          () -> {
            r.run();
            return null;
          });
    }
  }

  @Override
  public void runOnClientThread(Runnable action) {
    onClientThread(action);
  }

  // ------------------------------------------------------------ resource reload

  @Override
  public void reloadResources() {
    onClientThread(
        () -> {
          mc().refreshResources();
          reloadTicksRemaining = 2;
        });
  }

  @Override
  public boolean isResourceReloadSettled() {
    return reloadTicksRemaining <= 0;
  }

  // ------------------------------------------------------------ world entry

  /**
   * The worlds under the game's own {@code saves/}, which is where {@code joinLocalWorld} looks for
   * a world and where a script creates them.
   */
  @Override
  public net.minecheck.platform.api.WorldStorage worlds() {
    return new net.minecheck.common.world.SavesWorldStorage(
        new java.io.File(mc().mcDataDir, "saves").toPath());
  }

  @Override
  public Long worldTime() {
    Minecraft client = mc();
    return client.theWorld == null ? null : client.theWorld.getWorldTime() % 24000L;
  }

  @Override
  public boolean setWorldTime(long ticks) {
    return integratedCommand("time set " + ticks);
  }

  @Override
  public String weather() {
    Minecraft client = mc();
    if (client.theWorld == null) {
      return null;
    }
    if (client.theWorld.isThundering()) {
      return "thunder";
    }
    return client.theWorld.isRaining() ? "rain" : "clear";
  }

  @Override
  public boolean setWeather(String weather) {
    return integratedCommand("weather " + weather);
  }

  /**
   * Queues a command on the integrated server this client hosts, and reports whether there was one
   * to queue it on. A client playing on somebody else's server owns neither the time nor the
   * weather, so it answers false rather than pretending the change happened.
   */
  private boolean integratedCommand(String command) {
    net.minecraft.server.MinecraftServer server = mc().getIntegratedServer();
    if (server == null) {
      return false;
    }
    server.addScheduledTask(() -> server.getCommandManager().executeCommand(server, command));
    return true;
  }

  @Override
  public void joinLocalWorld(String worldName) {
    File save = new File(new File(mc().mcDataDir, "saves"), worldName + "/level.dat");
    if (!save.isFile()) {
      throw new IllegalArgumentException("World not found: " + worldName);
    }
    onClientThread(
        () ->
            mc().launchIntegratedServer(
                    worldName,
                    worldName,
                    new WorldSettings(
                        0L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT)));
  }

  @Override
  public void createWorld(CreateWorldOptions options) {
    WorldSettings info =
        new WorldSettings(
            options.seed(),
            gameMode(options.gamemode()),
            options.structures(),
            false,
            WorldType.FLAT);
    applyFlatGeneratorOptions(info, FlatWorldSettings.declaration());
    if (options.allowCheats()) {
      info.enableCommands();
    }
    pendingWorldOptions = options;
    onClientThread(() -> mc().launchIntegratedServer(options.name(), options.name(), info));
  }

  /**
   * Puts the flat world's declaration into the settings this line builds a world from.
   *
   * <p>1.7.10's {@code WorldSettings} keeps its generator options in a private field and exposes
   * only the getter, so the declaration is written through the field itself. The field is found by
   * its type: it is the only string the settings hold, and a name would not survive the mapping.
   */
  private static void applyFlatGeneratorOptions(WorldSettings settings, String declaration) {
    for (Field field : WorldSettings.class.getDeclaredFields()) {
      if (field.getType() != String.class) {
        continue;
      }
      try {
        field.setAccessible(true);
        field.set(settings, declaration);
        return;
      } catch (ReflectiveOperationException | RuntimeException e) {
        LOG.warning("Minecheck: could not set the flat world's generator options: " + e);
        return;
      }
    }
  }

  @Override
  public void joinServer(String address) {
    String host = address;
    int port = 25565;
    int colon = address.lastIndexOf(':');
    if (colon > 0 && address.indexOf(']') < colon) {
      host = address.substring(0, colon);
      try {
        port = Integer.parseInt(address.substring(colon + 1));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("invalid server port in: " + address, e);
      }
    }
    String finalHost = host;
    int finalPort = port;
    onClientThread(
        () -> {
          Minecraft client = mc();
          armHandshakeLatch();
          client.displayGuiScreen(
              new GuiConnecting(client.currentScreen, client, finalHost, finalPort));
          LOG.info(String.format("joinServer: GuiConnecting(%s:%s)", finalHost, finalPort));
        });
  }

  /**
   * Creates the latch FML's handshake waits on, before the client connects.
   *
   * <p>FML's own connect path ({@code FMLClientHandler.connectToServer}) is what normally sets it;
   * a {@link GuiConnecting} built here does not. Without it the handshake died with a
   * NullPointerException in {@code FMLClientHandler.waitForPlayClient}, so the play client was
   * never installed and the channel-registration packet then hit FML's own NetHandlerLoginClient to
   * NetHandlerPlayClient cast.
   */
  private static void armHandshakeLatch() {
    try {
      Field field = FMLClientHandler.class.getDeclaredField("playClientBlock");
      if (!java.util.concurrent.CountDownLatch.class.isAssignableFrom(field.getType())) {
        LOG.warning("joinServer: FML's handshake latch is not the expected count-down latch");
        return;
      }
      field.setAccessible(true);
      field.set(FMLClientHandler.instance(), new java.util.concurrent.CountDownLatch(1));
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.log(Level.WARNING, "joinServer: could not arm FML's handshake latch", e);
    }
  }

  @Override
  public void leaveWorld() {
    // Deliberately not a scheduled task: the teardown queues a client task and waits for it, so a
    // leave that ran inside that queue waited for itself. The tick hook applies it instead.
    leaveRequested = true;
  }

  /** Applies a pending {@link #leaveWorld()} from the client tick, outside the task queue. */
  void applyPendingLeave() {
    if (!leaveRequested) {
      return;
    }
    Minecraft client = mc();
    if (!isClientThread()) {
      return; // the tick is the only place this can run; anything else retries next tick
    }
    leaveRequested = false;
    // A held clock parks the server thread, and the teardown waits for that thread to finish — so
    // the clock is released first. Left held, the leave deadlocked the client in the integrated
    // server's own shutdown, which is the one state a script cannot wait its way out of.
    net.minecheck.common.tick.TickClock.get().resume();
    // What vanilla's own "Save and Quit to Title" does, in the same order: the quitting packet is
    // what tells the server the player left. Dropping the world alone closed the socket without
    // it, and the server kept the player in the game until the connection reset showed up as a
    // lost connection instead of a logout.
    if (client.theWorld != null && !client.isSingleplayer()) {
      client.theWorld.sendQuittingDisconnectingPacket();
    }
    // Dropping the world is also what stops an integrated server through the client's own
    // connection path.
    client.loadWorld(null);
    client.displayGuiScreen(new GuiMainMenu());
  }

  /** Set by {@link #quit()} and applied from the client tick. */
  private volatile boolean quitRequested;

  @Override
  public void quit() {
    // Applied from the tick, like a leave: the teardown queues a client task and waits for it, so a
    // quit that ran inside that queue waited for itself.
    quitRequested = true;
  }

  /**
   * Applies a pending {@link #quit()} from the client tick.
   *
   * <p>Dropping the world is what makes the integrated server write it. Clearing the client's
   * running flag on its own left the save to a shutdown the process outran: the staged world held
   * region files with no marker chunk in them, so the handoff server generated a world of its own
   * and never saw the block the client had placed.
   */
  void applyPendingQuit() {
    if (!quitRequested) {
      return;
    }
    Minecraft client = mc();
    if (!isClientThread()) {
      return; // the tick is the only place this can run; anything else retries next tick
    }
    quitRequested = false;
    // A held clock parks the server thread, and the save runs there.
    net.minecheck.common.tick.TickClock.get().resume();
    if (client.theWorld != null && client.isSingleplayer()) {
      net.minecraft.server.MinecraftServer server = ForgeEntrypoint.server();
      if (server != null) {
        for (net.minecraft.world.WorldServer world : server.worldServers) {
          if (world == null) {
            continue;
          }
          // Written here rather than left to the shutdown save: that pass only takes the chunks it
          // happens to unload, and the chunk a script just wrote stays loaded, so the marker was
          // never on disk to hand over. The full pass is the one that writes a chunk modified this
          // tick: the provider's own `saveChunks` asks each chunk whether it needs saving and
          // dropped the one the handoff script had just written.
          try {
            world.saveAllChunks(true, null);
            world.getChunkProvider().saveExtraData();
          } catch (net.minecraft.world.MinecraftException e) {
            throw new IllegalStateException("could not save the world before quitting", e);
          }
        }
      }
      // Vanilla's own "Save and Quit to Title": dropping the world through the client's connection
      // path is what makes the integrated server write its chunks before it stops.
      client.loadWorld(null);
      client.displayGuiScreen(new GuiMainMenu());
      // The chunks were written above, so there is nothing left to wait for: on this line the
      // integrated server ticks on the client thread itself, and waiting here for it to stop would
      // wait for the thread that is doing the waiting.
    }
    client.shutdown();
  }

  @Override
  public boolean isInWorld() {
    return mc().thePlayer != null && mc().theWorld != null;
  }

  @Override
  public boolean isWorldReady() {
    EntityPlayer player = mc().thePlayer;
    // Standing, too: this line puts the player a couple of blocks above the surface while the
    // client starts the integrated world, and a script that read its position before the fall had
    // finished saw a height no block under it agreed with. `onGround` is the settled state every
    // later line reaches before its world is called ready.
    return player != null && mc().currentScreen == null && player.onGround;
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
    Minecraft client = mc();
    if (client.thePlayer != null) {
      return false;
    }
    NetHandlerPlayClient handler = client.getConnection();
    if (handler == null) {
      return false;
    }
    NetworkManager connection = connectionOf(handler);
    return connection != null && connection.isChannelOpen();
  }

  /** The play handler's connection, found by type: the field name is mapping-dependent. */
  private static NetworkManager connectionOf(NetHandlerPlayClient handler) {
    for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (NetworkManager.class.isAssignableFrom(field.getType())) {
          try {
            field.setAccessible(true);
            return (NetworkManager) field.get(handler);
          } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
          }
        }
      }
    }
    return null;
  }

  @Override
  public boolean isConnectFailed() {
    return mc().currentScreen instanceof GuiDisconnected;
  }

  @Override
  public boolean isBootComplete() {
    return true;
  }

  @Override
  public float worldLoadProgress() {
    return -1f;
  }

  @Override
  public boolean isWorldGenSettled() {
    IntegratedServer server = mc().getIntegratedServer();
    if (server == null) {
      LOG.warning("worldGenSettled: not in a local/integrated world; treating as settled");
      return true;
    }
    net.minecraft.world.WorldServer overworld = server.worldServerForDimension(0);
    if (overworld == null) {
      LOG.warning("worldGenSettled: no overworld loaded; treating as settled");
      return true;
    }
    net.minecraft.util.math.BlockPos spawn = overworld.getSpawnPoint();
    return ((net.minecraft.world.gen.ChunkProviderServer) overworld.getChunkProvider())
        .chunkExists(spawn.getX() >> 4, spawn.getZ() >> 4);
  }

  // ------------------------------------------------------------ input

  @Override
  public boolean keyDown(String key) {
    int code = resolveKey(key);
    if (code == Keyboard.KEY_NONE) {
      return false;
    }
    onClientThread(
        () ->
            InjectedInput.run(
                () -> {
                  GuiScreen screen = mc().currentScreen;
                  if (screen != null) {
                    // A real press reaches the open screen, not the world bindings: this line
                    // closes a container from the screen's own key handler.
                    invokeScreen(
                        screen, "keyTyped", new Class<?>[] {char.class, int.class}, 0, code);
                    if (mc().currentScreen != screen) {
                      // The screen handled the key and closed itself.
                      return;
                    }
                    // Escape and the inventory key close a screen from the client's own key
                    // handling on this line, not from the screen's: without this a container that
                    // ignored the key stayed up, and every press meant for the world was spent as
                    // a slot click instead.
                    if (code == Keyboard.KEY_ESCAPE || code == inventoryKeyCode()) {
                      mc().displayGuiScreen(null);
                      return;
                    }
                    // Enter on the chat screen is the same story, and the submission has to be
                    // made here too: the screen keeps the field, but sending it is the client's
                    // job, so closing without it dropped the command the script had just typed.
                    if (code == Keyboard.KEY_RETURN && screen instanceof GuiChat) {
                      GuiTextField field = findTextField(screen);
                      String text = field == null ? "" : field.getText();
                      if (!text.isEmpty()
                          && mc().thePlayer != null
                          && mc().getConnection() != null) {
                        // A typed command goes through the same rewrite as a scripted one.
                        EntityPlayer player = mc().thePlayer;
                        for (String line :
                            text.startsWith("/")
                                ? LegacyCommandShim.expandForLegacy(
                                    text.substring(1), player.getName())
                                : java.util.Collections.singletonList(text)) {
                          mc().getConnection()
                              .sendPacket(
                                  new CPacketChatMessage(line.equals(text) ? line : "/" + line));
                        }
                      }
                      mc().displayGuiScreen(null);
                    }
                    return;
                  }
                  // The inventory key opens its screen here. This line's own key handling only
                  // reacts to keys it read itself, and the press time a script can set is read on
                  // the next tick only when that loop advanced it, so a tap opened the inventory
                  // on some runs and not others. This is the same screen that path opens.
                  if (code == inventoryKeyCode() && mc().thePlayer != null) {
                    mc().displayGuiScreen(
                            new net.minecraft.client.gui.inventory.GuiInventory(mc().thePlayer));
                    return;
                  }
                  // A swing goes through the player's own attack, at the entity the crosshair is
                  // on from the look it has this instant. This line's own pick is objectMouseOver,
                  // one frame behind, so a script that aims and swings in the same breath swung at
                  // whatever the previous aim held and the mob never took damage.
                  net.minecraft.entity.Entity swingTarget =
                      code == keyCodeNamed("key.attack") ? pickedEntity(4.5) : null;
                  if (swingTarget != null) {
                    mc().playerController.attackEntity(mc().thePlayer, swingTarget);
                    mc().thePlayer.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
                    return;
                  }
                  pressKeyCode(code, true);
                  lastPressTick = clientTicks;
                }));
    return true;
  }

  @Override
  public void keyUp(String key) {
    int code = resolveKey(key);
    if (code == Keyboard.KEY_NONE) {
      return;
    }
    pendingRelease = code;
    // A tap went up in the same tick it went down, so no tick has read the press yet: it is held
    // for one more tick's end before the release lands. A real hold has already been read.
    pendingReleaseTicks = clientTicks - lastPressTick <= 1 ? 2 : 1;
  }

  /** Ticks the client has run, counted at the end each one; a tap is held across one of them. */
  private static int clientTicks;

  /** The tick the last world key went down on. */
  private static int lastPressTick = -1;

  private static int pendingReleaseTicks;

  /**
   * Releases a script's key at the top of the tick after it went up.
   *
   * <p>A tap presses and releases inside one client task, and this line's {@code runTick} only sees
   * a binding through the press time a held key builds up: the release cleared it before any tick
   * ran, so {@code keyPress("key.inventory")} never opened the inventory. Deferring the release by
   * one tick leaves the press standing for the tick that reads it; a real hold is unaffected, since
   * its release lands on the next tick either way.
   */
  static void applyPendingInput() {
    clientTicks++;
    if (pendingRelease == Keyboard.KEY_NONE || --pendingReleaseTicks > 0) {
      return;
    }
    int code = pendingRelease;
    pendingRelease = Keyboard.KEY_NONE;
    InjectedInput.run(
        () -> {
          setKeyPressTime(code, 0);
          pressKeyCode(code, false);
        });
  }

  /** The key whose release is waiting for the next client tick. */
  private static int pendingRelease = Keyboard.KEY_NONE;

  /** The code bound to a binding id, or {@code Keyboard.KEY_NONE} when nothing is bound to it. */
  private static int keyCodeNamed(String id) {
    for (KeyBinding binding : keyBindings(mc().gameSettings)) {
      if (id.equals(binding.getKeyDescription())) {
        return binding.getKeyCode();
      }
    }
    return Keyboard.KEY_NONE;
  }

  /**
   * The entity the crosshair is on right now, or {@code null}.
   *
   * <p>Picked from the look the player has this instant rather than from the renderer's mouse-over,
   * which is a frame behind.
   */
  private static net.minecraft.entity.Entity pickedEntity(double reach) {
    Minecraft client = mc();
    EntityPlayer player = client.thePlayer;
    if (player == null || client.theWorld == null) {
      return null;
    }
    Vec3d start = new Vec3d(player.posX, eyeY(player), player.posZ);
    Vec3d direction = directionOf(player);
    Vec3d end =
        start.addVector(
            direction.xCoord * reach, direction.yCoord * reach, direction.zCoord * reach);
    AxisAlignedBB area =
        player
            .getEntityBoundingBox()
            .addCoord(direction.xCoord * reach, direction.yCoord * reach, direction.zCoord * reach)
            .expand(1.0, 1.0, 1.0);
    net.minecraft.entity.Entity best = null;
    double bestDistance = reach;
    for (Object candidate : client.theWorld.getEntitiesWithinAABBExcludingEntity(player, area)) {
      net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity) candidate;
      if (!entity.canBeCollidedWith()) {
        continue;
      }
      RayTraceResult hit =
          entity.getEntityBoundingBox().expand(0.3, 0.3, 0.3).calculateIntercept(start, end);
      if (hit == null) {
        continue;
      }
      double distance = start.distanceTo(hit.hitVec);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = entity;
      }
    }
    return best;
  }

  private static void pressKeyCode(int code, boolean down) {
    KeyBinding.setKeyBindState(code, down);
    if (down) {
      KeyBinding.onTick(code);
      // The client's own key handling is what normally advances this, and it never sees an
      // injected key: without the press time a binding reads as pressed, so no tap opened the
      // inventory even though the key state was down.
      setKeyPressTime(code, 1);
    }
  }

  /** Sets the press time of every binding bound to {@code code}, which this line keeps private. */
  private static void setKeyPressTime(int code, int value) {
    Field field;
    try {
      field = KeyBinding.class.getDeclaredField("pressTime");
      field.setAccessible(true);
    } catch (NoSuchFieldException | RuntimeException e) {
      return;
    }
    for (KeyBinding binding : keyBindings(mc().gameSettings)) {
      if (binding.getKeyCode() != code) {
        continue;
      }
      try {
        field.setInt(binding, value);
      } catch (IllegalAccessException | RuntimeException ignored) {
        // A binding this module cannot write is simply not pressed.
      }
    }
  }

  private static void pressMouseCode(int button, boolean down) {
    int code = -100 + button;
    KeyBinding.setKeyBindState(code, down);
    if (down) {
      KeyBinding.onTick(code);
    }
  }

  @Override
  public boolean mouseDown(int button) {
    onClientThread(() -> InjectedInput.run(() -> pressMouseCode(button, true)));
    return true;
  }

  @Override
  public void mouseUp(int button) {
    onClientThread(() -> InjectedInput.run(() -> pressMouseCode(button, false)));
  }

  /**
   * Resolves a script key name: a binding id ({@code key.jump}) through the client's bindings, or a
   * key name ({@code F3}) through LWJGL.
   */
  private static int resolveKey(String key) {
    if (key.startsWith("key.")) {
      for (KeyBinding binding : keyBindings(mc().gameSettings)) {
        if (key.equals(binding.getKeyDescription())) {
          return binding.getKeyCode();
        }
      }
      LOG.warning(String.format("keyDown: unknown binding '%s'", key));
      return Keyboard.KEY_NONE;
    }
    int code = Keyboard.getKeyIndex(key.toUpperCase(Locale.ROOT));
    if (code == Keyboard.KEY_NONE && key.equalsIgnoreCase("enter")) {
      // LWJGL calls the key RETURN, so a script's "enter" would otherwise resolve to nothing.
      code = Keyboard.KEY_RETURN;
    }
    if (code == Keyboard.KEY_NONE) {
      LOG.warning(String.format("keyDown: unresolvable key name '%s'", key));
    }
    return code;
  }

  /** The key code of the client's own inventory binding, or {@code -1} when it cannot be read. */
  private static int inventoryKeyCode() {
    for (KeyBinding binding : keyBindings(mc().gameSettings)) {
      if ("key.inventory".equals(binding.getKeyDescription())) {
        return binding.getKeyCode();
      }
    }
    return -1;
  }

  /** Every binding the options object holds, whether a single field or an array of them. */
  private static List<KeyBinding> keyBindings(Object options) {
    List<KeyBinding> out = new ArrayList<>();
    for (Class<?> type = options.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        try {
          field.setAccessible(true);
          Object value = field.get(options);
          if (value instanceof KeyBinding) {
            out.add((KeyBinding) value);
          } else if (value instanceof KeyBinding[]) {
            for (KeyBinding binding : (KeyBinding[]) value) {
              if (binding != null) {
                out.add(binding);
              }
            }
          }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // A field this module cannot read is simply not a candidate.
        }
      }
    }
    return out;
  }

  // ------------------------------------------------------------ player and look

  @Override
  public PlayerInfo getPlayer() {
    Minecraft client = mc();
    EntityPlayer player = client.thePlayer;
    if (player == null || client.theWorld == null) {
      return null;
    }
    // The feet, from the bounding box: on this line {@code posY} is the eye position, a full eye
    // height above the feet.
    return new PlayerInfo(
        player.getName(), player.posX, feetY(player), player.posZ, dimensionName(player));
  }

  /**
   * The player's feet.
   *
   * <p>This line's {@code posY} is the <em>eye</em> position — the bounding box starts an eye
   * height below it — so every feet read goes through the box. Reading {@code posY} as the feet put
   * every scripted coordinate 1.62 blocks too high: relative {@code /fill}s landed above the ground
   * and the crosshair never landed on the block the player stood on.
   */
  private static double feetY(EntityPlayer player) {
    return player.getEntityBoundingBox().minY;
  }

  /**
   * The player's dimension as a registry-style id.
   *
   * <p>Read from the entity's dimension number rather than from the dimension object: this line's *
   * {@code Dimension} carries no name of its own, while the number is what the whole 1.7.10 world
   * model is keyed by.
   */
  private static String dimensionName(EntityPlayer player) {
    int id = 0;
    try {
      Field field = Entity.class.getDeclaredField("dimension");
      field.setAccessible(true);
      id = field.getInt(player);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Unreadable: fall through to the overworld, which is where every suite runs.
    }
    switch (id) {
      case -1:
        return "minecraft:the_nether";
      case 1:
        return "minecraft:the_end";
      case 0:
        return "minecraft:overworld";
      default:
        return "minecraft:dimension_" + id;
    }
  }

  @Override
  public void setLook(float pitch, float yaw) {
    onClientThread(
        () -> {
          EntityPlayer player = mc().thePlayer;
          if (player != null) {
            player.rotationPitch = pitch;
            player.rotationYaw = yaw;
          }
        });
  }

  @Override
  public void lookAt(double x, double y, double z) {
    onClientThread(
        () -> {
          EntityPlayer player = mc().thePlayer;
          if (player == null) {
            return;
          }
          double dx = x - player.posX;
          // The angles are the eye's: `posY` is the feet here, and aiming from them puts a
          // same-height target on the horizon instead of on its top face.
          double dy = y - eyeY(player);
          double dz = z - player.posZ;
          double horizontal = Math.sqrt(dx * dx + dz * dz);
          player.rotationYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
          player.rotationPitch = (float) -(Math.atan2(dy, horizontal) * 180.0 / Math.PI);
        });
  }

  @Override
  public BlockPosition getTargetBlock(String entityName) {
    Minecraft client = mc();
    // Against the command-sender name, which is the name {@link #getPlayer()} reports: the game
    // profile's name is empty in an offline dev session, so comparing against it answered null for
    // every script (the crosshair test's whole first expectation).
    if (client.thePlayer == null
        || client.theWorld == null
        || !client.thePlayer.getName().equals(entityName)) {
      return null;
    }
    return getCrosshairBlock();
  }

  @Override
  public BlockPosition getCrosshairBlock() {
    Minecraft client = mc();
    if (client.theWorld == null || client.thePlayer == null) {
      return null;
    }
    // This line's own mouse-over is refreshed by the frame loop, and a script asks right after it
    // turned the player — before the next frame — so the pick is made here, from the look the
    // player has now, at the reach vanilla gives the crosshair.
    EntityPlayer player = client.thePlayer;
    // The crosshair starts at the eyes, which this line reports separately from `posY` (the feet).
    Vec3d start = new Vec3d(player.posX, eyeY(player), player.posZ);
    Vec3d direction = directionOf(player);
    Vec3d end =
        start.addVector(direction.xCoord * 5.0, direction.yCoord * 5.0, direction.zCoord * 5.0);
    RayTraceResult hit = client.theWorld.rayTraceBlocks(start, end);
    if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
      return null;
    }
    return new BlockPosition(
        hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ());
  }

  /** The eye position the crosshair starts from: this line's {@code posY} is the player's feet. */
  private static double eyeY(EntityPlayer player) {
    return player.posY + player.getEyeHeight();
  }

  /** The unit vector a player is looking along; this line has no accessor for it. */
  private static Vec3d directionOf(EntityPlayer player) {
    float pitch = (float) Math.toRadians(player.rotationPitch);
    float yaw = (float) Math.toRadians(player.rotationYaw);
    return new Vec3d(
        -Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
  }

  @Override
  public List<EntityInfo> findNearbyEntities(String type) {
    Minecraft client = mc();
    WorldClient level = client.theWorld;
    if (level == null) {
      return java.util.Collections.emptyList();
    }
    ResourceLocation typeId =
        type == null ? null : new ResourceLocation(type.toLowerCase(Locale.ROOT));
    List<EntityInfo> out = new ArrayList<>();
    for (Object candidate : level.loadedEntityList) {
      Entity entity = (Entity) candidate;
      // A killed entity stays in this line's list until the world ticks it out, so a script that
      // waits for its target to be gone would see the corpse forever.
      if (entity.isDead) {
        continue;
      }
      ResourceLocation actual = entityTypeId(entity);
      if (typeId != null && !typeId.equals(actual)) {
        continue;
      }
      // Scoreboard tags are server-side data on this line: the set is empty client-side, which is
      // a faithful copy of what the client actually knows.
      out.add(
          new EntityInfo(
              entity.getEntityId(),
              actual == null ? "" : actual.toString(),
              entity.posX,
              entity.posY,
              entity.posZ,
              new java.util.HashSet<>(LegacyCommandShim.tagsOf(entity))));
    }
    return java.util.Collections.unmodifiableList(out);
  }

  /** The 1.13-style id of a client-side entity, from this line's CamelCase type name. */
  private static ResourceLocation entityTypeId(Entity entity) {
    String legacy = EntityList.getEntityString(entity);
    return legacy == null
        ? new ResourceLocation("minecraft", "player")
        : new ResourceLocation(MinecheckNames.modernEntityId(legacy));
  }

  // ------------------------------------------------------------ commands and use

  @Override
  public void runCommand(String command) {
    Minecraft client = mc();
    EntityPlayer player = client.thePlayer;
    if (player == null || client.getConnection() == null) {
      throw new IllegalStateException("not connected to a world/server");
    }
    // 1.13's selector and root syntax is translated before the command goes anywhere.
    List<String> lines =
        LegacyCommandShim.expandForLegacy(
            LegacyCommandShim.translate(command.startsWith("/") ? command.substring(1) : command),
            player.getName());
    IntegratedServer server = client.getIntegratedServer();
    EntityPlayerMP self =
        server == null ? null : server.getPlayerList().getPlayerByUsername(player.getName());
    if (self == null) {
      for (String line : lines) {
        client.getConnection().sendPacket(new CPacketChatMessage("/" + line));
      }
      return;
    }
    // This line's integrated server ticks on the client thread, so the command runs there: the
    // newer lines' `submit` has no equivalent here, and the world is only safe to touch on that
    // thread.
    onClientThread(
        () -> {
          for (String line : lines) {
            runIntegratedCommand(server, self, line);
          }
        });
  }

  /** One command on the integrated server, through the shim first. */
  private static void runIntegratedCommand(
      net.minecraft.server.MinecraftServer server, EntityPlayerMP self, String cmd) {
    net.minecraft.world.WorldServer world = server.worldServerForDimension(0);
    Integer rule = LegacyCommandShim.gameruleValue(world, cmd);
    Integer entity = rule == null ? LegacyCommandShim.executeIfEntity(world, cmd) : null;
    Integer data =
        rule == null && entity == null ? LegacyCommandShim.dataGetEntity(world, cmd) : null;
    Integer tagged =
        rule == null && entity == null && data == null
            ? LegacyCommandShim.tagCommand(world, cmd)
            : null;
    Integer conditional =
        rule == null && entity == null && data == null && tagged == null
            ? LegacyCommandShim.executeIfBlock(world, cmd)
            : null;
    if (rule == null && entity == null && data == null && tagged == null && conditional == null) {
      server.getCommandManager().executeCommand(self, cmd);
    }
  }

  @Override
  public void useItemOnTarget() {
    Minecraft client = mc();
    if (client.thePlayer == null || client.playerController == null || client.theWorld == null) {
      throw new IllegalStateException("not in a world");
    }
    // Picked from the look the player has this instant: a script aims and clicks in the same
    // breath, and this line's own pick (`objectMouseOver`) is a frame behind — clicking it aims at
    // whatever the previous look held and vanilla drops the attempt silently, leaving the
    // container shut.
    EntityPlayer player = client.thePlayer;
    Vec3d start = new Vec3d(player.posX, eyeY(player), player.posZ);
    Vec3d direction = directionOf(player);
    Vec3d end =
        start.addVector(direction.xCoord * 5.0, direction.yCoord * 5.0, direction.zCoord * 5.0);
    RayTraceResult hit = client.theWorld.rayTraceBlocks(start, end);
    if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
      return;
    }
    // The held stack, the side the hit reports and the hand are what the item's use-on-block
    // callback takes.
    client.playerController.processRightClickBlock(
        client.thePlayer,
        client.theWorld,
        client.thePlayer.getHeldItemMainhand(),
        hit.getBlockPos(),
        hit.sideHit,
        hit.hitVec,
        net.minecraft.util.EnumHand.MAIN_HAND);
  }

  @Override
  public boolean openSignEditor(BlockPosition pos) {
    Minecraft client = mc();
    if (client.theWorld == null) {
      throw new IllegalStateException("not in a world");
    }
    TileEntity blockEntity =
        client.theWorld.getTileEntity(
            new net.minecraft.util.math.BlockPos(pos.x(), pos.y(), pos.z()));
    if (!(blockEntity instanceof TileEntitySign)) {
      return false;
    }
    onClientThread(() -> client.displayGuiScreen(new GuiEditSign((TileEntitySign) blockEntity)));
    return true;
  }

  // ------------------------------------------------------------ GUI

  @Override
  public boolean clickGui(int button, double x, double y) {
    GuiScreen screen = mc().currentScreen;
    if (screen == null) {
      return false;
    }
    return invokeScreen(
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
    List<GuiButton> byLabel = new ArrayList<>();
    List<GuiButton> byClass = new ArrayList<>();
    for (GuiButton widget : buttons(screen)) {
      String label = labelOf(widget).trim().toLowerCase(Locale.ROOT);
      if (label.equals(wanted)) {
        byLabel.add(widget);
      } else if (widget.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains(wanted)) {
        byClass.add(widget);
      }
    }
    List<GuiButton> matches = byLabel.isEmpty() ? byClass : byLabel;
    int at = index <= 1 ? 0 : index - 1;
    if (at >= matches.size()) {
      return false;
    }
    GuiButton target = matches.get(at);
    int clickX = target.xPosition + target.width / 2;
    int clickY = target.yPosition + target.height / 2;
    // A press alone leaves the button down: this line runs a button's action on release, so the
    // screen is given the whole click the way a real one arrives.
    invokeScreen(
        screen,
        "mouseClicked",
        new Class<?>[] {int.class, int.class, int.class},
        clickX,
        clickY,
        0);
    invokeScreen(
        screen,
        "mouseReleased",
        new Class<?>[] {int.class, int.class, int.class},
        clickX,
        clickY,
        0);
    return true;
  }

  /** Every button the screen holds, read out of its own fields by element type. */
  private static List<GuiButton> buttons(GuiScreen screen) {
    List<GuiButton> out = new ArrayList<>();
    for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (!List.class.isAssignableFrom(field.getType())) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object value = field.get(screen);
          if (!(value instanceof List)) {
            continue;
          }
          for (Object element : (List<?>) value) {
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

  /** A button's displayed text, whichever accessor this mapping has. */
  private static String labelOf(GuiButton widget) {
    try {
      Method message = GuiButton.class.getMethod("getMessage");
      Object value = message.invoke(widget);
      String text = textOf(value);
      if (text != null) {
        return text;
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // Fall through to the field.
    }
    for (Class<?> type = widget.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        // This line's buttons keep their label in a plain String field (`displayString`, from
        // before `message`), while the newer lines hold a Text; both are read, and only the
        // label-looking names are trusted so an unrelated string field cannot answer as the label.
        boolean isText = ITextComponent.class.isAssignableFrom(field.getType());
        boolean isLabelString =
            field.getType() == String.class
                && (field.getName().equals("displayString")
                    || field.getName().equals("message")
                    || field.getName().equals("label"));
        if (!isText && !isLabelString) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object value = field.get(widget);
          if (value instanceof String) {
            return (String) value;
          }
          String text = textOf(value);
          if (text != null) {
            return text;
          }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // A field this module cannot read is simply not a candidate.
        }
      }
    }
    return "";
  }

  /** An int field a mapping may keep private (a widget's own size, for one). */
  private static int intField(Object holder, String name) {
    for (Class<?> type = holder.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(holder);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // The field lives further up, or this mapping does not have it.
      }
    }
    return 0;
  }

  /** A boolean field a mapping may keep private (a widget's visibility, for one). */
  private static boolean booleanField(Object holder, String name) {
    for (Class<?> type = holder.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(holder);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // The field lives further up, or this mapping does not have it.
      }
    }
    // A widget whose visibility cannot be read is treated as visible, which is the answer that
    // keeps typing into it possible.
    return true;
  }

  /** A text's plain content, whichever accessor this mapping has. */
  private static String textOf(Object value) {
    if (value == null) {
      return null;
    }
    for (String name : new String[] {"getString", "asString", "getText", "asFormattedString"}) {
      try {
        Method method = value.getClass().getMethod(name);
        Object text = method.invoke(value);
        if (text instanceof String) {
          return (String) text;
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // Try the next accessor.
      }
    }
    return null;
  }

  @Override
  public boolean typeText(String text, String target) {
    GuiScreen screen = mc().currentScreen;
    if (screen == null) {
      return false;
    }
    String kind = target == null ? "" : target.toLowerCase(Locale.ROOT);
    GuiTextField field = findTextField(screen);
    if (field == null) {
      // Screens that own their text editing have no field — the sign editor is one — so the
      // characters are dispatched at the screen instead.
      boolean typed = false;
      for (int i = 0; i < text.length(); i++) {
        typed |=
            invokeScreen(
                screen, "keyTyped", new Class<?>[] {char.class, int.class}, text.charAt(i), 0);
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
      dispatched |=
          invokeScreen(
              screen, "keyTyped", new Class<?>[] {char.class, int.class}, text.charAt(i), 0);
    }
    return dispatched;
  }

  /** The screen's focused text field, or its first visible one when none is focused. */
  private static GuiTextField findTextField(GuiScreen screen) {
    GuiTextField first = null;
    for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (!GuiTextField.class.isAssignableFrom(field.getType())) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object value = field.get(screen);
          if (!(value instanceof GuiTextField)) {
            continue;
          }
          GuiTextField box = (GuiTextField) value;
          if (!booleanField(box, "visible")) {
            continue;
          }
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

  /** Invokes a protected {@link GuiScreen} hook, answering whether it reported success. */
  private static boolean invokeScreen(
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

  @Override
  public boolean clickSlot(int slot, int button, String actionType) {
    Minecraft client = mc();
    if (!(client.currentScreen instanceof GuiContainer)) {
      return false;
    }
    GuiContainer screen = (GuiContainer) client.currentScreen;
    // 1.7.10 applies the local half of a click to the player's open container and names the
    // screen's
    // window in the packet. If the two disagree (a screen that outlived the container the client
    // was told to open), the click is applied to another container, the packet carries that slot's
    // stack, and the server silently drops it: nothing moves and nothing errors.
    if (client.thePlayer.openContainer != screen.inventorySlots) {
      client.thePlayer.openContainer = screen.inventorySlots;
    }
    client.playerController.windowClick(
        screen.inventorySlots.windowId, slot, button, itemAction(actionType), client.thePlayer);
    return true;
  }

  /** The click mode as this line names it: 1.9's slot click takes a {@link ClickType}. */
  private static ClickType itemAction(String type) {
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
  public void openChat(String mode) {
    boolean command = mode != null && mode.equalsIgnoreCase("command");
    onClientThread(() -> mc().displayGuiScreen(new GuiChat(command ? "/" : "")));
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
    // Matched by class name: the class names differ per mapping, and the id space is shared with
    // the other lines, so the portable form is a name match.
    String name = screen.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    switch (screenId) {
      case "inventory":
        // This line's class is GuiInventory — "guiinventory", not the newer "inventoryscreen" —
        // and a creative client gets GuiContainerCreative for the same key, so both are the
        // player's inventory screen.
        return name.contains("inventory") || name.contains("creative");
      case "chat":
        return screen instanceof GuiChat;
      case "beacon":
        return name.contains("beacon");
      case "sign":
        // This line's editor is GuiEditSign — "guieditsign", not the newer "signeditscreen".
        return name.contains("editsign") || name.contains("signedit");
      case "chest":
        return name.contains("chest");
      case "crafting":
        return name.contains("crafting");
      case "furnace":
        return name.contains("furnace");
      default:
        return false;
    }
  }

  private static GuiContainer openScreen(String screenId) {
    GuiScreen screen = mc().currentScreen;
    if (!(screen instanceof GuiContainer)) {
      throw new IllegalStateException("no container screen is open");
    }
    return (GuiContainer) screen;
  }

  @Override
  public int slotCount(String screenId) {
    try {
      return openScreen(screenId).inventorySlots.inventorySlots.size();
    } catch (IllegalStateException e) {
      return 0;
    }
  }

  @Override
  public SlotInfo getSlot(int index, String screenId) {
    List<Slot> slots = openScreen(screenId).inventorySlots.inventorySlots;
    if (index < 0 || index >= slots.size()) {
      throw new IllegalStateException(
          "slot index " + index + " out of range (0.." + (slots.size() - 1) + ")");
    }
    ItemStack stack = slots.get(index).getStack();
    // This line's stack has a count field and no emptiness test, and an empty slot holds null.
    if (stack == null || stack.stackSize <= 0) {
      return new SlotInfo(true, null, 0, null);
    }
    // Its registry answers a name string here, not an identifier.
    Object rawItemId = Item.REGISTRY.getNameForObject(stack.getItem());
    String itemId = rawItemId == null ? "" : rawItemId.toString();
    NBTTagCompound nbt = stack.getTagCompound();
    return new SlotInfo(
        false,
        MinecheckNames.modernId(itemId == null ? "" : itemId),
        stack.stackSize,
        nbt == null ? null : MinecheckNames.toMap(nbt));
  }

  // ------------------------------------------------------------ world reads

  @Override
  public BlockInfo getBlock(BlockPosition pos) {
    Minecraft client = mc();
    if (client.theWorld == null) {
      throw new IllegalStateException("not in a world");
    }
    if (!isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      return null;
    }
    // No block states here: the block, placed with its metadata, is what the newer lines' state
    // resolves to, and the id a script reads is the block's own.
    Block block =
        client
            .theWorld
            .getBlockState(new net.minecraft.util.math.BlockPos(pos.x(), pos.y(), pos.z()))
            .getBlock();
    TileEntity blockEntity =
        client.theWorld.getTileEntity(
            new net.minecraft.util.math.BlockPos(pos.x(), pos.y(), pos.z()));
    Object rawBlockId = Block.REGISTRY.getNameForObject(block);
    String blockId = rawBlockId == null ? null : rawBlockId.toString();
    NBTTagCompound blockEntityTag = new NBTTagCompound();
    if (blockEntity != null) {
      // This line writes a block entity's NBT into a compound and answers nothing.
      blockEntity.writeToNBT(blockEntityTag);
    }
    return new BlockInfo(
        MinecheckNames.modernId(blockId == null ? "minecraft:air" : blockId),
        blockEntity == null ? null : MinecheckNames.toMap(blockEntityTag));
  }

  @Override
  public Integer groundHeight(int x, int z) {
    Minecraft client = mc();
    if (client.theWorld == null) {
      throw new IllegalStateException("not in a world");
    }
    if (!isChunkLoaded(x >> 4, z >> 4)) {
      return null;
    }
    // This line's top position of a column is already the first air block above the surface — the
    // height a player standing there stands at — so it is returned as it is, matching the server
    // platform and the newer lines' MOTION_BLOCKING read.
    // This line has no column-top query: the column is scanned down from the build limit and the
    // first block that is not air is the surface, whose height is one above it.
    for (int y = client.theWorld.getHeight() - 1; y >= 0; y--) {
      if (!client.theWorld.isAirBlock(new net.minecraft.util.math.BlockPos(x, y, z))) {
        return y + 1;
      }
    }
    return 0;
  }

  @Override
  public boolean isChunkLoaded(int chunkX, int chunkZ) {
    Minecraft client = mc();
    return client.theWorld != null
        && ((net.minecraft.client.multiplayer.ChunkProviderClient)
                    client.theWorld.getChunkProvider())
                .getLoadedChunk(chunkX, chunkZ)
            != null;
  }

  // ------------------------------------------------------------ clock and render

  /**
   * Whether a script's view window is the run's output, whose keys are the human's by rule. The
   * camera asks this before it lets a real mouse steer.
   */
  boolean viewWindowVisible() {
    return viewWindow != null;
  }

  @Override
  public void pinClientClock() {
    Minecraft client = mc();
    applyPendingWorldSettings(client);
    WorldClient level = client.theWorld;
    if (level == null) {
      return;
    }
    IntegratedServer server = client.getIntegratedServer();
    if (server == null) {
      // Remote server: no authoritative value in this process.
      return;
    }
    if (server.worldServers == null || server.worldServers.length == 0) {
      // The integrated server exists before it has prepared a world — the handoff client ticks in
      // that window — so there is no time of day to re-assert yet.
      return;
    }
    net.minecraft.world.WorldServer overworld = server.worldServerForDimension(0);
    if (overworld == null) {
      return;
    }
    long serverTime = overworld.getWorldTime();
    level.setWorldTime(serverTime);
  }

  /** Applies the rules and difficulty a {@code createWorld} call asked for. */
  private void applyPendingWorldSettings(Minecraft client) {
    CreateWorldOptions options = pendingWorldOptions;
    if (options == null) {
      return;
    }
    IntegratedServer server = client.getIntegratedServer();
    if (server == null) {
      return;
    }
    if (server.worldServers == null || server.worldServers.length == 0) {
      // Keep the request pending: the world these rules belong to has not been prepared yet.
      return;
    }
    pendingWorldOptions = null;
    net.minecraft.world.WorldServer overworld = server.worldServerForDimension(0);
    if (overworld != null) {
      if (!options.mobSpawning()) {
        overworld.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
      }
      if (!options.daylightCycle()) {
        overworld.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
      }
      if (!options.weatherCycle()) {
        overworld.getGameRules().setOrCreateGameRule("doWeatherCycle", "false");
      }
    }
    // No insomnia rule to set: 1.7.10 has no such game rule (it arrives in 1.15).
    server.setDifficultyForAllWorlds(difficulty(options.difficulty()));
  }

  private static WorldSettings.GameType gameMode(String name) {
    switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
      case "survival":
        return WorldSettings.GameType.SURVIVAL;
      case "adventure":
        return WorldSettings.GameType.ADVENTURE;
      case "spectator":
        // 1.7.10 predates spectator mode; adventure is the closest mode it has — no flight and no
        // ability to place blocks, which is what a spectator fixture relies on.
        return WorldSettings.GameType.ADVENTURE;
      default:
        return WorldSettings.GameType.CREATIVE;
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

  /** Set once the pinned tick tracker is installed, or once installation has been given up on. */
  private static volatile boolean renderTickDeltaPinned;

  private static volatile boolean renderTickDeltaUnavailable;

  @Override
  public void pinRenderTickDelta() {
    if (vanillaCameraRestored || renderTickDeltaPinned || renderTickDeltaUnavailable) {
      return;
    }
    try {
      for (Field field : Minecraft.class.getDeclaredFields()) {
        if (!field.getType().getSimpleName().equals("ClientTickTracker")) {
          continue;
        }
        field.setAccessible(true);
        Object tracker = field.get(mc());
        for (Field inner : tracker.getClass().getDeclaredFields()) {
          if (inner.getType() != float.class) {
            continue;
          }
          inner.setAccessible(true);
          inner.setFloat(tracker, 1.0f);
          renderTickDeltaPinned = true;
          LOG.info("Minecheck: pinned the render tick delta to 1");
          return;
        }
      }
      renderTickDeltaUnavailable = true;
      LOG.warning("Minecheck: no render tick tracker — captures keep the vanilla partial tick");
    } catch (ReflectiveOperationException | RuntimeException e) {
      renderTickDeltaUnavailable = true;
      LOG.log(Level.WARNING, "Minecheck: could not pin the render tick delta", e);
    }
  }

  @Override
  public void setVanillaCameraRestored(boolean restored) {
    vanillaCameraRestored = restored;
    if (restored) {
      LOG.warning(
          "Minecheck: restoreVanillaCamera is on — the camera/render path is left untouched and"
              + " every capture fails: "
              + ClientPlatform.SCREENSHOTS_UNAVAILABLE_VANILLA_CAMERA);
    }
  }

  @Override
  public void setViewWindow(ViewWindow window) {
    viewWindow = window;
  }

  @Override
  public void pumpViewWindow() {
    if (reloadTicksRemaining > 0) {
      reloadTicksRemaining--;
    }
    ViewWindow window = viewWindow;
    if (window == null || !window.visible()) {
      return;
    }
    if (viewWindowTicks++ % VIEW_WINDOW_TICK_INTERVAL != 0) {
      return;
    }
    try {
      Framebuffer framebuffer = mc().getFramebuffer();
      int width = framebuffer.framebufferTextureWidth;
      int height = framebuffer.framebufferTextureHeight;
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

  // ------------------------------------------------------------ captures

  /**
   * Reads the main framebuffer into ARGB pixels, top row first.
   *
   * <p>This line has no {@code RenderTarget}/{@code NativeImage} pair to delegate to, so the
   * readback is done here: bind the framebuffer, {@code glReadPixels} the RGBA bytes, then repack
   * them (GL's origin is the bottom-left).
   */
  private static int[] readFramebuffer() {
    Framebuffer framebuffer = mc().getFramebuffer();
    int width = framebuffer.framebufferTextureWidth;
    int height = framebuffer.framebufferTextureHeight;
    ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
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

  @Override
  public void requestScreenshot(ScreenshotRequest request) {
    if (vanillaCameraRestored) {
      request.fail(ClientPlatform.SCREENSHOTS_UNAVAILABLE_VANILLA_CAMERA);
      return;
    }
    onClientThread(() -> captureScreenshot(request, true));
  }

  @Override
  public void requestFrame(ScreenshotRequest request) {
    if (vanillaCameraRestored) {
      request.fail(ClientPlatform.SCREENSHOTS_UNAVAILABLE_VANILLA_CAMERA);
      return;
    }
    onClientThread(() -> captureScreenshot(request, false));
  }

  @Override
  public void writeFrame(ScreenshotRequest request) {
    if (vanillaCameraRestored) {
      request.fail(ClientPlatform.SCREENSHOTS_UNAVAILABLE_VANILLA_CAMERA);
      return;
    }
    request.running();
    encodeFrame(
        request,
        new File(mc().mcDataDir, "screenshots")
            .toPath()
            .resolve(request.name() + ".png")
            .toAbsolutePath());
  }

  /** One attempt at a capture, on the client thread. */
  private void captureScreenshot(ScreenshotRequest request, boolean write) {
    Minecraft client = mc();
    boolean clearChat = !request.chatVisible() && client.theWorld != null;
    if (clearChat && !request.prepared()) {
      client.ingameGUI.getChatGUI().clearChatMessages();
      chatClearedAtFrame = DRAWN_FRAMES.get();
      request.markPrepared();
      request.retry("clearing chat before the capture");
      return;
    }
    if (clearChat && DRAWN_FRAMES.get() == chatClearedAtFrame) {
      request.retry("waiting for the first frame drawn after the chat was cleared");
      return;
    }
    Framebuffer framebuffer = client.getFramebuffer();
    int frameWidth = framebuffer.framebufferTextureWidth;
    int frameHeight = framebuffer.framebufferTextureHeight;
    if (frameWidth <= 0 || frameHeight <= 0) {
      request.fail("no client framebuffer — is the client in a world?");
      return;
    }
    if (frameWidth != request.frameWidth() || frameHeight != request.frameHeight()) {
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

    int guiScale = new net.minecraft.client.gui.ScaledResolution(client).getScaleFactor();
    int x = 0;
    int y = 0;
    int cropWidth = frameWidth;
    int cropHeight = frameHeight;
    if (request.hasRegion()) {
      long unit = request.unit().equals(ScreenshotRequest.UNIT_GUI) ? guiScale : 1;
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
    Path targetPath =
        new File(client.mcDataDir, "screenshots")
            .toPath()
            .resolve(request.name() + ".png")
            .toAbsolutePath();
    writeScreenshot(request, frame, frameWidth, x, y, cropWidth, cropHeight, guiScale, targetPath);
  }

  /** Crops, downscales and digests the capture in memory — off the client thread. */
  private static void readFrame(
      ScreenshotRequest request,
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
            request.succeedFrame(outWidth, outHeight, pixels, Digests.sha256(pixels), guiScale);
          } catch (RuntimeException e) {
            request.fail(
                "framebuffer read failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
          }
        });
  }

  /** Encodes a frame captured in memory and writes it — off the client thread. */
  private static void encodeFrame(ScreenshotRequest request, Path target) {
    SCREENSHOT_WRITER.execute(
        () -> {
          try {
            writePng(
                request,
                request.pixels(),
                request.outWidth(),
                request.outHeight(),
                target,
                request.guiScale());
          } catch (Exception e) {
            request.fail(
                "framebuffer read failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
          }
        });
  }

  /** Crops, downscales, PNG-encodes and writes the capture — all off the client thread. */
  private static void writeScreenshot(
      ScreenshotRequest request,
      int[] source,
      int frameWidth,
      int x,
      int y,
      int cropWidth,
      int cropHeight,
      int guiScale,
      Path target) {
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
            writePng(request, pixels, outWidth, outHeight, target, guiScale);
          } catch (Exception e) {
            request.fail(
                "framebuffer read failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
          }
        });
  }

  /** Encodes packed ARGB pixels as a PNG and reports the file through {@code request}. */
  private static void writePng(
      ScreenshotRequest request, int[] pixels, int width, int height, Path target, int guiScale)
      throws java.io.IOException {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, width, height, pixels, 0, width);
    Path directory = target.getParent();
    java.nio.file.Files.createDirectories(directory);
    Path tmp = directory.resolve(target.getFileName() + ".part");
    javax.imageio.ImageIO.write(image, "png", tmp.toFile());
    java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    byte[] bytes = java.nio.file.Files.readAllBytes(target);
    ScreenshotProfile.write(
        directory,
        request.frameWidth(),
        request.frameHeight(),
        request.guiScale(),
        request.scale(),
        request.chatVisible(),
        screenshotRenderer);
    request.succeed(target, width, height, bytes.length, Digests.sha256(bytes));
  }

  /**
   * Translates a GLFW key code (what {@link ViewWindow} hands over) into the LWJGL 2 key name
   * {@link Keyboard} indexes by.
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
      case 340:
        return "LSHIFT";
      case 344:
        return "RSHIFT";
      case 341:
        return "LCONTROL";
      case 345:
        return "RCONTROL";
      default:
        return null;
    }
  }
}
