package net.minecheck.loader.legacyfabric;

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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.Window;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.slot.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResultType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.level.LevelGeneratorType;
import net.minecraft.world.level.LevelInfo;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The client-side half of the Legacy Fabric platform.
 *
 * <p>It is a port of the Forge 1.12.2 platform onto this line's names, with two differences the
 * loader forces: there is no client-setup event and no key-binding registry, so the camera's toggle
 * is polled rather than registered, and there is no event bus, so the input and sound hooks are
 * mixins. Every user-visible behaviour — world entry, input, commands, screens, slots, captures —
 * is the same, which is what the shared suites check.
 */
public final class LegacyFabricClientPlatform implements ClientPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Client ticks between frames pushed to the view window (~10 fps at 20 ticks a second). */
  private static final int VIEW_WINDOW_TICK_INTERVAL = 2;

  private final LegacyFabricCameraPlatform cameraPlatform;
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

  LegacyFabricClientPlatform(Path configDir) {
    this.configDir = configDir;
    this.cameraPlatform = new LegacyFabricCameraPlatform(configDir);
  }

  /** The running client, or {@code null} before one exists. */
  public static MinecraftClient client() {
    return MinecraftClient.getInstance();
  }

  private static MinecraftClient mc() {
    return MinecraftClient.getInstance();
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
    mc().options.pauseOnLostFocus = false;
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
        "Minecheck: hideGameWindow is on, but 1.7.10's LWJGL 2 has no hidden window — the game"
            + " window stays mapped (pause-on-focus-loss is still off)");
  }

  private static void onClientThread(Runnable r) {
    MinecraftClient client = mc();
    if (client == null) {
      return;
    }
    if (isClientThread()) {
      r.run();
    } else {
      client.execute(
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
          mc().reloadResources();
          reloadTicksRemaining = 2;
        });
  }

  @Override
  public boolean isResourceReloadSettled() {
    return reloadTicksRemaining <= 0;
  }

  // ------------------------------------------------------------ world entry

  @Override
  public void joinLocalWorld(String worldName) {
    File save = new File(new File(mc().runDirectory, "saves"), worldName + "/level.dat");
    if (!save.isFile()) {
      throw new IllegalArgumentException("World not found: " + worldName);
    }
    onClientThread(
        () ->
            mc().startIntegratedServer(
                    worldName,
                    worldName,
                    new LevelInfo(
                        0L, GameMode.SURVIVAL, false, false, LevelGeneratorType.DEFAULT)));
  }

  @Override
  public void createWorld(CreateWorldOptions options) {
    LevelInfo info =
        new LevelInfo(
            options.seed(),
            gameMode(options.gamemode()),
            options.structures(),
            false,
            LevelGeneratorType.FLAT);
    info.setGeneratorOptions(LegacyFlatWorldSettings.declaration());
    if (options.allowCheats()) {
      info.enableCommands();
    }
    pendingWorldOptions = options;
    onClientThread(() -> mc().startIntegratedServer(options.name(), options.name(), info));
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
          MinecraftClient client = mc();
          client.setScreen(new ConnectScreen(client.currentScreen, client, finalHost, finalPort));
          LOG.info(String.format("joinServer: ConnectScreen(%s:%s)", finalHost, finalPort));
        });
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
    MinecraftClient client = mc();
    if (!isClientThread()) {
      return; // the tick is the only place this can run; anything else retries next tick
    }
    leaveRequested = false;
    // A held clock parks the server thread, and the teardown waits for that thread to finish — so
    // the clock is released first. Left held, the leave deadlocked the client in the integrated
    // server's own shutdown, which is the one state a script cannot wait its way out of.
    net.minecheck.common.tick.TickClock.get().resume();
    // Only for a server this client does not own. On an integrated world the disconnect below
    // stops the server itself; calling stop directly from this thread waits on a task the same
    // thread has to drain.
    if (client.world != null
        && !client.isIntegratedServerRunning()
        && client.getNetworkHandler() != null) {
      client.getNetworkHandler().clearWorld();
    }
    // What vanilla's own "Save and Quit to Title" does: it drops the world and stops an integrated
    // server through the client's own connection path.
    client.connect(null);
    client.setScreen(new TitleScreen());
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
    MinecraftClient client = mc();
    if (!isClientThread()) {
      return; // the tick is the only place this can run; anything else retries next tick
    }
    quitRequested = false;
    // A held clock parks the server thread, and the save runs there.
    net.minecheck.common.tick.TickClock.get().resume();
    if (client.world != null && client.isIntegratedServerRunning()) {
      net.minecraft.server.MinecraftServer server = LegacyFabricEntrypoint.server();
      if (server != null) {
        for (net.minecraft.server.world.ServerWorld world : server.worlds) {
          if (world == null) {
            continue;
          }
          // Written here rather than left to the shutdown save: that pass only takes the chunks it
          // happens to unload, and the chunk a script just wrote stays loaded, so the marker was
          // never on disk to hand over.
          world.getChunkProvider().saveChunks(true, null);
          world.getChunkProvider().flushChunks();
        }
      }
      // Vanilla's own "Save and Quit to Title": dropping the world through the client's connection
      // path is what makes the integrated server write its chunks before it stops.
      client.connect(null);
      client.setScreen(new TitleScreen());
      // The chunks were written above, so there is nothing left to wait for: on this line the
      // integrated server ticks on the client thread itself, and waiting here for it to stop would
      // wait for the thread that is doing the waiting.
    }
    client.scheduleStop();
  }

  @Override
  public boolean isInWorld() {
    return mc().player != null && mc().world != null;
  }

  @Override
  public boolean isWorldReady() {
    ClientPlayerEntity player = mc().player;
    // Standing, too: this line puts the player a couple of blocks above the surface while the
    // client starts the integrated world, and a script that read its position before the fall had
    // finished saw a height no block under it agreed with. `onGround` is the settled state every
    // later line reaches before its world is called ready.
    return player != null && mc().currentScreen == null && player.onGround;
  }

  @Override
  public boolean isOnTitleScreen() {
    return mc().currentScreen instanceof TitleScreen;
  }

  @Override
  public boolean isConnecting() {
    return mc().currentScreen instanceof ConnectScreen;
  }

  @Override
  public boolean isLoggingIn() {
    MinecraftClient client = mc();
    if (client.player != null) {
      return false;
    }
    ClientPlayNetworkHandler handler = client.getNetworkHandler();
    if (handler == null) {
      return false;
    }
    ClientConnection connection = connectionOf(handler);
    return connection != null && connection.isOpen();
  }

  /** The play handler's connection, found by type: the field name is mapping-dependent. */
  private static ClientConnection connectionOf(ClientPlayNetworkHandler handler) {
    for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (ClientConnection.class.isAssignableFrom(field.getType())) {
          try {
            field.setAccessible(true);
            return (ClientConnection) field.get(handler);
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
    return mc().currentScreen instanceof DisconnectedScreen;
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
    IntegratedServer server = mc().getServer();
    if (server == null) {
      LOG.warning("worldGenSettled: not in a local/integrated world; treating as settled");
      return true;
    }
    net.minecraft.server.world.ServerWorld overworld = server.getWorld(0);
    if (overworld == null) {
      LOG.warning("worldGenSettled: no overworld loaded; treating as settled");
      return true;
    }
    BlockPos spawn = overworld.getSpawnPos();
    return overworld.getChunkProvider().chunkExists(spawn.getX() >> 4, spawn.getZ() >> 4);
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
                  Screen screen = mc().currentScreen;
                  if (screen != null) {
                    // A real press reaches the open screen, not the world bindings: this line
                    // closes a container from the screen's own key handler.
                    invokeScreen(
                        screen, "keyPressed", new Class<?>[] {char.class, int.class}, 0, code);
                    if (mc().currentScreen != screen) {
                      // The screen handled the key and closed itself.
                      return;
                    }
                    // Escape and the inventory key close a screen from the client's own key
                    // handling on this line, not from the screen's: without this a container that
                    // ignored the key stayed up, and every press meant for the world was spent as
                    // a slot click instead.
                    if (code == Keyboard.KEY_ESCAPE || code == inventoryKeyCode()) {
                      mc().setScreen(null);
                      return;
                    }
                    // Enter on the chat screen is the same story, and the submission has to be
                    // made here too: the screen keeps the field, but sending it is the client's
                    // job, so closing without it dropped the command the script had just typed.
                    if (code == Keyboard.KEY_RETURN && screen instanceof ChatScreen) {
                      TextFieldWidget field = findTextField(screen);
                      String text = field == null ? "" : field.getText();
                      if (!text.isEmpty()
                          && mc().player != null
                          && mc().getNetworkHandler() != null) {
                        // A typed command goes through the same rewrite as a scripted one: `@s` and
                        // `/fill` are not syntax this line has, wherever the command came from.
                        ClientPlayerEntity player = mc().player;
                        for (String line :
                            text.startsWith("/")
                                ? LegacyCommandShim.expandForLegacy(
                                    text.substring(1),
                                    player.getGameProfile().getName(),
                                    player.x,
                                    LegacyEntitySpace.feetY(player),
                                    player.z)
                                : java.util.Collections.singletonList(text)) {
                          mc().getNetworkHandler()
                              .sendPacket(
                                  new ChatMessageC2SPacket(line.equals(text) ? line : "/" + line));
                        }
                      }
                      mc().setScreen(null);
                    }
                    return;
                  }
                  pressKeyCode(code, true);
                }));
    return true;
  }

  @Override
  public void keyUp(String key) {
    int code = resolveKey(key);
    if (code != Keyboard.KEY_NONE) {
      onClientThread(() -> InjectedInput.run(() -> pressKeyCode(code, false)));
    }
  }

  private static void pressKeyCode(int code, boolean down) {
    KeyBinding.setKeyPressed(code, down);
    if (down) {
      KeyBinding.onKeyPressed(code);
    }
  }

  private static void pressMouseCode(int button, boolean down) {
    int code = -100 + button;
    KeyBinding.setKeyPressed(code, down);
    if (down) {
      KeyBinding.onKeyPressed(code);
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
      for (KeyBinding binding : keyBindings(mc().options)) {
        if (key.equals(binding.getTranslationKey())) {
          return binding.getCode();
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
    for (KeyBinding binding : keyBindings(mc().options)) {
      if ("key.inventory".equals(binding.getTranslationKey())) {
        return binding.getCode();
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
    MinecraftClient client = mc();
    ClientPlayerEntity player = client.player;
    if (player == null || client.world == null) {
      return null;
    }
    // The feet, from the bounding box: this line's y field sits an eye height above them (see
    // LegacyEntitySpace).
    return new PlayerInfo(
        player.getGameProfile().getName(),
        player.x,
        LegacyEntitySpace.feetY(player),
        player.z,
        dimensionName(player));
  }

  /**
   * The player's dimension as a registry-style id.
   *
   * <p>Read from the entity's dimension number rather than from the dimension object: this line's
   * {@code Dimension} carries no name of its own, while the number is what the whole 1.12.2 world
   * model is keyed by.
   */
  private static String dimensionName(ClientPlayerEntity player) {
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
          ClientPlayerEntity player = mc().player;
          if (player != null) {
            player.pitch = pitch;
            player.yaw = yaw;
          }
        });
  }

  @Override
  public void lookAt(double x, double y, double z) {
    onClientThread(
        () -> {
          ClientPlayerEntity player = mc().player;
          if (player == null) {
            return;
          }
          double dx = x - player.x;
          double dy = y - LegacyEntitySpace.eyeY(player);
          double dz = z - player.z;
          double horizontal = Math.sqrt(dx * dx + dz * dz);
          player.yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
          player.pitch = (float) -(Math.atan2(dy, horizontal) * 180.0 / Math.PI);
        });
  }

  @Override
  public BlockPosition getTargetBlock(String entityName) {
    MinecraftClient client = mc();
    if (client.player == null
        || client.world == null
        || !client.player.getGameProfile().getName().equals(entityName)) {
      return null;
    }
    return getCrosshairBlock();
  }

  @Override
  public BlockPosition getCrosshairBlock() {
    MinecraftClient client = mc();
    if (client.world == null || client.player == null) {
      return null;
    }
    // This line's own mouse-over is refreshed by the frame loop, and a script asks right after it
    // turned the player — before the next frame — so the pick is made here, from the look the
    // player has now, at the reach vanilla gives the crosshair.
    ClientPlayerEntity player = client.player;
    Vec3d start = new Vec3d(player.x, LegacyEntitySpace.eyeY(player), player.z);
    Vec3d direction = directionOf(player);
    Vec3d end = start.add(direction.x * 5.0, direction.y * 5.0, direction.z * 5.0);
    BlockHitResult hit = client.world.rayTrace(start, end);
    if (hit == null || hit.hitResult != HitResultType.BLOCK) {
      return null;
    }
    return new BlockPosition(
        hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ());
  }

  /** The unit vector a player is looking along; this line has no accessor for it. */
  private static Vec3d directionOf(ClientPlayerEntity player) {
    float pitch = (float) Math.toRadians(player.pitch);
    float yaw = (float) Math.toRadians(player.yaw);
    return new Vec3d(
        -Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
  }

  @Override
  public List<EntityInfo> findNearbyEntities(String type) {
    MinecraftClient client = mc();
    ClientWorld level = client.world;
    if (level == null) {
      return java.util.Collections.emptyList();
    }
    Identifier typeId = type == null ? null : new Identifier(type.toLowerCase(Locale.ROOT));
    List<EntityInfo> out = new ArrayList<>();
    for (Object candidate : level.loadedEntities) {
      Entity entity = (Entity) candidate;
      Identifier actual = entityTypeId(entity);
      if (typeId != null && !typeId.equals(actual)) {
        continue;
      }
      // Scoreboard tags are server-side data on this line: the set is empty client-side, which is
      // a faithful copy of what the client actually knows.
      out.add(
          new EntityInfo(
              entity.getEntityId(),
              actual == null ? "" : actual.toString(),
              entity.x,
              entity.y,
              entity.z,
              new java.util.HashSet<>(LegacyCommandShim.tagsOf(entity))));
    }
    return java.util.Collections.unmodifiableList(out);
  }

  /** The 1.13-style id of a client-side entity, from this line's CamelCase type name. */
  private static Identifier entityTypeId(Entity entity) {
    String legacy = EntityType.getEntityName(entity);
    return legacy == null
        ? new Identifier("minecraft", "player")
        : new Identifier(LegacyFabricNbt.modernEntityId(legacy));
  }

  // ------------------------------------------------------------ commands and use

  @Override
  public void runCommand(String command) {
    MinecraftClient client = mc();
    ClientPlayerEntity player = client.player;
    if (player == null || client.getNetworkHandler() == null) {
      throw new IllegalStateException("not connected to a world/server");
    }
    // `@s` and `/fill` are 1.8 syntax, so a command that uses either becomes the commands this
    // line has — possibly more than one — before it goes anywhere.
    List<String> lines =
        LegacyCommandShim.expandForLegacy(
            LegacyCommandShim.translate(command.startsWith("/") ? command.substring(1) : command),
            player.getGameProfile().getName(),
            player.x,
            LegacyEntitySpace.feetY(player),
            player.z);
    IntegratedServer server = client.getServer();
    ServerPlayerEntity self =
        server == null
            ? null
            : server.getPlayerManager().getPlayer(player.getGameProfile().getName());
    if (self == null) {
      for (String line : lines) {
        client.getNetworkHandler().sendPacket(new ChatMessageC2SPacket("/" + line));
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
      net.minecraft.server.MinecraftServer server, ServerPlayerEntity self, String cmd) {
    net.minecraft.server.world.ServerWorld world = server.getWorld(0);
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
      server.getCommandManager().execute(self, cmd);
    }
  }

  @Override
  public void useItemOnTarget() {
    MinecraftClient client = mc();
    if (client.player == null || client.interactionManager == null || client.world == null) {
      throw new IllegalStateException("not in a world");
    }
    // The frame loop refreshes this line's own mouse-over, and a script clicks right after it
    // turned the player — before the next frame — so the pick is made here from the look the
    // player has now, the same way {@link #getCrosshairBlock()} answers the wait that precedes this
    // click. Clicking a stale pick aims at whatever the previous frame held and vanilla drops the
    // attempt silently, leaving the container shut.
    ClientPlayerEntity player = client.player;
    Vec3d start = new Vec3d(player.x, LegacyEntitySpace.eyeY(player), player.z);
    Vec3d direction = directionOf(player);
    Vec3d end = start.add(direction.x * 5.0, direction.y * 5.0, direction.z * 5.0);
    BlockHitResult hit = client.world.rayTrace(start, end);
    if (hit == null || hit.hitResult != HitResultType.BLOCK) {
      return;
    }
    client.interactionManager.onRightClick(
        player, client.world, player.getStackInHand(), hit.getBlockPos(), hit.direction, hit.pos);
  }

  @Override
  public boolean openSignEditor(BlockPosition pos) {
    MinecraftClient client = mc();
    if (client.world == null) {
      throw new IllegalStateException("not in a world");
    }
    BlockEntity blockEntity =
        client.world.getBlockEntity(
            new net.minecraft.util.math.BlockPos(pos.x(), pos.y(), pos.z()));
    if (!(blockEntity instanceof SignBlockEntity)) {
      return false;
    }
    onClientThread(() -> client.setScreen(new SignEditScreen((SignBlockEntity) blockEntity)));
    return true;
  }

  // ------------------------------------------------------------ GUI

  @Override
  public boolean clickGui(int button, double x, double y) {
    Screen screen = mc().currentScreen;
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
    Screen screen = mc().currentScreen;
    if (screen == null) {
      return false;
    }
    String wanted = elementId.trim().toLowerCase(Locale.ROOT);
    if (wanted.isEmpty()) {
      return false;
    }
    List<ButtonWidget> byLabel = new ArrayList<>();
    List<ButtonWidget> byClass = new ArrayList<>();
    for (ButtonWidget widget : buttons(screen)) {
      String label = labelOf(widget).trim().toLowerCase(Locale.ROOT);
      if (label.equals(wanted)) {
        byLabel.add(widget);
      } else if (widget.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains(wanted)) {
        byClass.add(widget);
      }
    }
    List<ButtonWidget> matches = byLabel.isEmpty() ? byClass : byLabel;
    int at = index <= 1 ? 0 : index - 1;
    if (at >= matches.size()) {
      return false;
    }
    ButtonWidget target = matches.get(at);
    int clickX = target.x + intField(target, "width") / 2;
    int clickY = target.y + intField(target, "height") / 2;
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
  private static List<ButtonWidget> buttons(Screen screen) {
    List<ButtonWidget> out = new ArrayList<>();
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
            if (element instanceof ButtonWidget) {
              out.add((ButtonWidget) element);
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
  private static String labelOf(ButtonWidget widget) {
    try {
      Method message = ButtonWidget.class.getMethod("getMessage");
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
        // This line's buttons keep their label in a plain String field (`message`), while the
        // newer lines hold a Text; both are read, and only the label-looking names are trusted so
        // an unrelated string field cannot answer as the label.
        boolean isText = Text.class.isAssignableFrom(field.getType());
        boolean isLabelString =
            field.getType() == String.class
                && (field.getName().equals("message") || field.getName().equals("label"));
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
    Screen screen = mc().currentScreen;
    if (screen == null) {
      return false;
    }
    String kind = target == null ? "" : target.toLowerCase(Locale.ROOT);
    TextFieldWidget field = findTextField(screen);
    if (field == null) {
      // Screens that own their text editing have no field — the sign editor is one — so the
      // characters are dispatched at the screen instead.
      boolean typed = false;
      for (int i = 0; i < text.length(); i++) {
        typed |=
            invokeScreen(
                screen, "keyPressed", new Class<?>[] {char.class, int.class}, text.charAt(i), 0);
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
              screen, "keyPressed", new Class<?>[] {char.class, int.class}, text.charAt(i), 0);
    }
    return dispatched;
  }

  /** The screen's focused text field, or its first visible one when none is focused. */
  private static TextFieldWidget findTextField(Screen screen) {
    TextFieldWidget first = null;
    for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (!TextFieldWidget.class.isAssignableFrom(field.getType())) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object value = field.get(screen);
          if (!(value instanceof TextFieldWidget)) {
            continue;
          }
          TextFieldWidget box = (TextFieldWidget) value;
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

  /** Invokes a protected {@link Screen} hook, answering whether it reported success. */
  private static boolean invokeScreen(
      Screen screen, String name, Class<?>[] parameterTypes, Object... args) {
    try {
      Method method = Screen.class.getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      Object result = method.invoke(screen, args);
      return !(result instanceof Boolean) || (Boolean) result;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  @Override
  public boolean clickSlot(int slot, int button, String actionType) {
    MinecraftClient client = mc();
    if (!(client.currentScreen instanceof HandledScreen)) {
      return false;
    }
    HandledScreen screen = (HandledScreen) client.currentScreen;
    client.interactionManager.clickSlot(
        screen.screenHandler.syncId, slot, button, itemAction(actionType), client.player);
    return true;
  }

  /**
   * The click mode as this line numbers it: 1.7.10's slot click takes the mode as an int and has no
   * {@code ItemAction} enum, and the numbers are the wire values the protocol has always used.
   */
  private static int itemAction(String type) {
    switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
      case "quick_move":
        return 1;
      case "swap":
        return 2;
      case "clone":
        return 3;
      case "throw":
        return 4;
      case "quick_craft":
        return 5;
      case "pickup_all":
        return 6;
      default:
        return 0;
    }
  }

  @Override
  public void openChat(String mode) {
    boolean command = mode != null && mode.equalsIgnoreCase("command");
    onClientThread(() -> mc().setScreen(new ChatScreen(command ? "/" : "")));
  }

  @Override
  public boolean isScreenOpen(String screenId) {
    Screen screen = mc().currentScreen;
    if (screen == null) {
      return false;
    }
    if (screenId == null || screenId.isEmpty()) {
      return screen instanceof HandledScreen;
    }
    // Matched by class name: the class names differ per mapping, and the id space is shared with
    // the other lines, so the portable form is a name match.
    String name = screen.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    switch (screenId) {
      case "inventory":
        return name.contains("inventoryscreen");
      case "chat":
        return screen instanceof ChatScreen;
      case "beacon":
        return name.contains("beacon");
      case "sign":
        return name.contains("signedit");
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

  private static HandledScreen openScreen(String screenId) {
    Screen screen = mc().currentScreen;
    if (!(screen instanceof HandledScreen)) {
      throw new IllegalStateException("no container screen is open");
    }
    return (HandledScreen) screen;
  }

  @Override
  public int slotCount(String screenId) {
    try {
      return openScreen(screenId).screenHandler.slots.size();
    } catch (IllegalStateException e) {
      return 0;
    }
  }

  @Override
  public SlotInfo getSlot(int index, String screenId) {
    List<Slot> slots = openScreen(screenId).screenHandler.slots;
    if (index < 0 || index >= slots.size()) {
      throw new IllegalStateException(
          "slot index " + index + " out of range (0.." + (slots.size() - 1) + ")");
    }
    ItemStack stack = slots.get(index).getStack();
    // This line's stack has a count field and no emptiness test, and an empty slot holds null.
    if (stack == null || stack.count <= 0) {
      return new SlotInfo(true, null, 0, null);
    }
    // Its registry answers a name string here, not an identifier.
    Object rawItemId = Item.REGISTRY.getIdentifier(stack.getItem());
    String itemId = rawItemId == null ? null : rawItemId.toString();
    NbtCompound nbt = stack.getNbt();
    return new SlotInfo(
        false,
        LegacyFabricNbt.modernId(itemId == null ? "" : itemId),
        stack.count,
        nbt == null ? null : LegacyFabricNbt.toMap(nbt));
  }

  // ------------------------------------------------------------ world reads

  @Override
  public BlockInfo getBlock(BlockPosition pos) {
    MinecraftClient client = mc();
    if (client.world == null) {
      throw new IllegalStateException("not in a world");
    }
    BlockPos blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
    if (!isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      return null;
    }
    // No block states here: the block, placed with its metadata, is what the newer lines' state
    // resolves to, and the id a script reads is the block's own.
    Block block = client.world.getBlockState(blockPos).getBlock();
    BlockEntity blockEntity = client.world.getBlockEntity(blockPos);
    Object rawBlockId = Block.REGISTRY.getIdentifier(block);
    String blockId = rawBlockId == null ? null : rawBlockId.toString();
    NbtCompound blockEntityTag = new NbtCompound();
    if (blockEntity != null) {
      // This line writes a block entity's NBT into a compound and answers nothing.
      blockEntity.toNbt(blockEntityTag);
    }
    return new BlockInfo(
        LegacyFabricNbt.modernId(blockId == null ? "minecraft:air" : blockId),
        blockEntity == null ? null : LegacyFabricNbt.toMap(blockEntityTag));
  }

  @Override
  public Integer groundHeight(int x, int z) {
    MinecraftClient client = mc();
    if (client.world == null) {
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
    for (int y = client.world.getEffectiveHeight() - 1; y >= 0; y--) {
      if (!client.world.isAir(new net.minecraft.util.math.BlockPos(x, y, z))) {
        return y + 1;
      }
    }
    return 0;
  }

  @Override
  public boolean isChunkLoaded(int chunkX, int chunkZ) {
    MinecraftClient client = mc();
    return client.world != null && client.world.getChunkProvider().chunkExists(chunkX, chunkZ);
  }

  // ------------------------------------------------------------ clock and render

  @Override
  public void pinClientClock() {
    MinecraftClient client = mc();
    applyPendingWorldSettings(client);
    ClientWorld level = client.world;
    if (level == null) {
      return;
    }
    IntegratedServer server = client.getServer();
    if (server == null) {
      // Remote server: no authoritative value in this process.
      return;
    }
    net.minecraft.server.world.ServerWorld overworld = server.getWorld(0);
    if (overworld == null) {
      return;
    }
    long serverTime = overworld.getTimeOfDay();
    level.setTimeOfDay(serverTime);
  }

  /** Applies the rules and difficulty a {@code createWorld} call asked for. */
  private void applyPendingWorldSettings(MinecraftClient client) {
    CreateWorldOptions options = pendingWorldOptions;
    if (options == null) {
      return;
    }
    IntegratedServer server = client.getServer();
    if (server == null) {
      return;
    }
    pendingWorldOptions = null;
    net.minecraft.server.world.ServerWorld overworld = server.getWorld(0);
    if (overworld != null) {
      if (!options.mobSpawning()) {
        overworld.getGameRules().setGameRule("doMobSpawning", "false");
      }
      if (!options.daylightCycle()) {
        overworld.getGameRules().setGameRule("doDaylightCycle", "false");
      }
      if (!options.weatherCycle()) {
        overworld.getGameRules().setGameRule("doWeatherCycle", "false");
      }
    }
    // No insomnia rule to set: 1.12.2 has no such game rule (it arrives in 1.15).
    server.setDifficulty(difficulty(options.difficulty()));
  }

  private static GameMode gameMode(String name) {
    switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
      case "survival":
        return GameMode.SURVIVAL;
      case "adventure":
        return GameMode.ADVENTURE;
      case "spectator":
        // 1.7.10 predates spectator mode; adventure is the closest mode it has — no flight and no
        // ability to place blocks, which is what a spectator fixture relies on.
        return GameMode.ADVENTURE;
      default:
        return GameMode.CREATIVE;
    }
  }

  private static Difficulty difficulty(String name) {
    switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
      case "peaceful":
        return Difficulty.PEACEFUL;
      case "easy":
        return Difficulty.EASY;
      case "hard":
        return Difficulty.HARD;
      default:
        return Difficulty.NORMAL;
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
      for (Field field : MinecraftClient.class.getDeclaredFields()) {
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
      int width = framebuffer.textureWidth;
      int height = framebuffer.textureHeight;
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
    int width = framebuffer.textureWidth;
    int height = framebuffer.textureHeight;
    ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
    int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer.fbo);
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
        new File(mc().runDirectory, "screenshots")
            .toPath()
            .resolve(request.name() + ".png")
            .toAbsolutePath());
  }

  /** One attempt at a capture, on the client thread. */
  private void captureScreenshot(ScreenshotRequest request, boolean write) {
    MinecraftClient client = mc();
    boolean clearChat = !request.chatVisible() && client.world != null;
    if (clearChat && !request.prepared()) {
      client.inGameHud.getChatHud().clear();
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
    int frameWidth = framebuffer.textureWidth;
    int frameHeight = framebuffer.textureHeight;
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

    int guiScale = new Window(client, Display.getWidth(), Display.getHeight()).getScaleFactor();
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
        new File(client.runDirectory, "screenshots")
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
