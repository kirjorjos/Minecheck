package net.minecheck.loader.forge;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

public class ForgeClientPlatform implements ClientPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private Minecraft mc() {
    return Minecraft.getInstance();
  }

  private void onClientThread(Runnable r) {
    Minecraft mc = mc();
    if (mc.isSameThread()) {
      r.run();
    } else {
      mc.execute(r);
    }
  }

  /** Runs {@code action} on the client thread — inline when already on it, else on a later tick. */
  @Override
  public void runOnClientThread(Runnable action) {
    onClientThread(action);
  }

  private volatile java.util.concurrent.CompletableFuture<Void> resourceReload;
  private volatile boolean reloadRequested;

  @Override
  public void reloadResources() {
    // Flagged before the dispatch: the caller is not on the client thread, so the reload cannot
    // have started yet, and "settled" must not report true in between.
    reloadRequested = true;
    onClientThread(() -> resourceReload = mc().reloadResourcePacks());
  }

  @Override
  public boolean isResourceReloadSettled() {
    java.util.concurrent.CompletableFuture<Void> reload = resourceReload;
    return !reloadRequested || (reload != null && reload.isDone());
  }

  // ------------------------------------------------------------ screenshots

  /**
   * Encode/write thread for captures: the GL readback stays on the render thread, but the crop, the
   * PNG encode and the file write do not. One daemon thread is enough — captures are sequential in
   * a script and the client must never wait on it.
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

  /**
   * Points the client's clock back at the integrated server's. See {@link
   * net.minecheck.platform.api.ClientPlatform#pinClientClock()} for why the drift is invisible live
   * but not in a capture.
   *
   * <p>Called every client tick from {@code ForgeEntrypoint}, after the level tick (where {@code
   * ClientLevel.tickTime} advanced the day time) and before that frame is rendered. A mismatch is
   * corrected in place; when the two already agree nothing is written, so a world the script froze
   * is not touched.
   */
  @Override
  public void pinClientClock() {
    net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
    net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;
    if (level == null) {
      return;
    }
    net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
    if (server == null) {
      // Remote server: no authoritative value in this process (see pinClientClock's contract).
      return;
    }
    net.minecraft.server.level.ServerLevel overworld = server.overworld();
    if (overworld == null) {
      return;
    }
    long serverTime = overworld.getDayTime();
    if (level.getDayTime() != serverTime) {
      level.setDayTime(serverTime);
    }
  }

  @Override
  public void requestScreenshot(net.minecheck.platform.api.ScreenshotRequest request) {
    onClientThread(() -> captureScreenshot(request));
  }

  /**
   * One attempt at a capture, on the client (render) thread. Reads the main render target into a
   * {@link com.mojang.blaze3d.platform.NativeImage}, then hands the pixels to the writer thread;
   * the script keeps ticking meanwhile.
   */
  private void captureScreenshot(net.minecheck.platform.api.ScreenshotRequest request) {
    Minecraft mc = mc();
    if (!request.prepared()) {
      // Chat lines are the one HUD element a script makes nondeterministic (a message arrives when
      // it arrives), so a capture without chat clears them. Clearing here rather than switching
      // chat off in the options keeps client commands working: the server rejects every command
      // sent while chat visibility is hidden. The read is a tick later, because the frame the read
      // picks up is drawn after this runs.
      if (!request.chatVisible()) {
        mc.gui.getChat().clearMessages(true);
        request.markPrepared();
        request.retry("clearing chat before the capture");
        return;
      }
      request.markPrepared();
    }
    com.mojang.blaze3d.pipeline.RenderTarget target = mc.getMainRenderTarget();
    if (target == null) {
      request.fail("no client framebuffer — is the client in a world?");
      return;
    }
    com.mojang.blaze3d.platform.Window window = mc.getWindow();
    if (window.getWidth() != request.frameWidth() || window.getHeight() != request.frameHeight()) {
      window.setWindowed(request.frameWidth(), request.frameHeight());
      request.retry(
          "the client window is "
              + window.getWidth()
              + "x"
              + window.getHeight()
              + ", not the "
              + request.frameWidth()
              + "x"
              + request.frameHeight()
              + " the screenshot profile pins it to");
      return;
    }

    int frameWidth = window.getWidth();
    int frameHeight = window.getHeight();
    int guiScale = guiScale(window);
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

    com.mojang.blaze3d.platform.NativeImage image;
    try {
      image = net.minecraft.client.Screenshot.takeScreenshot(target);
    } catch (RuntimeException e) {
      request.fail("framebuffer read failed: " + e.getMessage());
      return;
    }
    if (screenshotRenderer == null) {
      screenshotRenderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
    }
    java.nio.file.Path targetPath =
        new java.io.File(mc.gameDirectory, "screenshots")
            .toPath()
            .resolve(request.name() + ".png")
            .toAbsolutePath();
    request.running();
    writeScreenshot(
        request, image, x, y, cropWidth, cropHeight, frameWidth, frameHeight, guiScale, targetPath);
  }

  /**
   * The effective GUI scale: framebuffer pixels per GUI pixel, taken from the window the client
   * laid its HUD out with (so {@code unit = "gui"} regions line up with what a script sees).
   */
  private static int guiScale(com.mojang.blaze3d.platform.Window window) {
    int scaledWidth = window.getGuiScaledWidth();
    if (scaledWidth <= 0) {
      return 1;
    }
    int scale = Math.round((float) window.getWidth() / scaledWidth);
    return Math.max(1, scale);
  }

  /** Crops, downscales, PNG-encodes and writes the capture — all off the render thread. */
  private static void writeScreenshot(
      net.minecheck.platform.api.ScreenshotRequest request,
      com.mojang.blaze3d.platform.NativeImage source,
      int x,
      int y,
      int cropWidth,
      int cropHeight,
      int frameWidth,
      int frameHeight,
      int guiScale,
      java.nio.file.Path target) {
    SCREENSHOT_WRITER.execute(
        () -> {
          com.mojang.blaze3d.platform.NativeImage out = null;
          try {
            int outWidth = Math.max(1, (int) Math.round(cropWidth * request.scale()));
            int outHeight = Math.max(1, (int) Math.round(cropHeight * request.scale()));
            out = new com.mojang.blaze3d.platform.NativeImage(outWidth, outHeight, false);
            for (int dy = 0; dy < outHeight; dy++) {
              int sy = y + (int) ((long) dy * cropHeight / outHeight);
              for (int dx = 0; dx < outWidth; dx++) {
                int sx = x + (int) ((long) dx * cropWidth / outWidth);
                out.setPixelRGBA(dx, dy, source.getPixelRGBA(sx, sy));
              }
            }
            java.nio.file.Path directory = target.getParent();
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Path tmp = directory.resolve(target.getFileName() + ".part");
            out.writeToFile(tmp);
            java.nio.file.Files.move(
                tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            byte[] bytes = java.nio.file.Files.readAllBytes(target);
            net.minecheck.common.screenshot.ScreenshotProfile.write(
                directory,
                frameWidth,
                frameHeight,
                guiScale,
                request.scale(),
                request.chatVisible(),
                screenshotRenderer);
            request.succeed(target, outWidth, outHeight, bytes.length, sha256(bytes));
          } catch (Exception e) {
            request.fail(
                "framebuffer read failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
          } finally {
            if (out != null) {
              out.close();
            }
            source.close();
          }
        });
  }

  /** Lower-case hex SHA-256, the digest a baseline comparison reports. */
  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }

  @Override
  public void openChat(String mode) {
    boolean command = mode != null && mode.equalsIgnoreCase("command");
    onClientThread(
        () -> mc().setScreen(new net.minecraft.client.gui.screens.ChatScreen(command ? "/" : "")));
  }

  @Override
  public void joinLocalWorld(String worldName) {
    Minecraft mc = mc();
    if (!mc.getLevelSource().levelExists(worldName)) {
      throw new IllegalArgumentException("World not found: " + worldName);
    }
    onClientThread(
        () -> {
          mc.createWorldOpenFlows().loadLevel(mc.screen, worldName);
          proceedPastExperimentalWarning(mc);
        });
  }

  /**
   * Accepts the "this world has experimental settings" question Forge puts in the way of a local
   * world load.
   *
   * <p>{@code WorldOpenFlows.loadLevel} is a <em>player</em> flow: it stops on a confirmation
   * whenever the world's settings are not lifecycle stable, which is the case for the worlds saved
   * by this dev client — Forge enables every mod's datapack in the saved {@code DataPacks} list
   * ({@code mod:emi}, {@code mod:jei}, …), so those worlds are not the builtin registries alone.
   * The screen is set synchronously by the join itself, so a script that called {@code
   * joinLocalWorld} would otherwise sit on it until its timeout, with no world, no error and no way
   * to answer it: {@code isOnTitleScreen} is false, {@code isWorldReady} is false, and nothing is
   * connecting.
   *
   * <p>The join request <em>is</em> the confirmation, so press {@code Proceed} — the same answer
   * the screen's own yes-button gives, which re-runs the load with the warning marked as confirmed.
   * Matched on the button label rather than the screen class: {@code BackupConfirmScreen} is also a
   * {@code ConfirmScreen} (the "back up this world first" question for old customized worlds) and
   * that one has no {@code Proceed}, so it is left for a human to answer.
   */
  private static void proceedPastExperimentalWarning(Minecraft mc) {
    if (!(mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen confirm)) {
      return;
    }
    net.minecraft.network.chat.Component proceed =
        net.minecraft.network.chat.CommonComponents.GUI_PROCEED;
    for (net.minecraft.client.gui.components.events.GuiEventListener element : confirm.children()) {
      if (element instanceof net.minecraft.client.gui.components.AbstractWidget widget
          && proceed.equals(widget.getMessage())) {
        LOG.info("joinLocalWorld: confirming the world's experimental settings (Proceed)");
        widget.mouseClicked(
            widget.x + widget.getWidth() / 2.0, widget.y + widget.getHeight() / 2.0, 0);
        return;
      }
    }
  }

  @Override
  public void createWorld(CreateWorldOptions options) {
    GameRules rules = new GameRules();
    if (!options.mobSpawning()) {
      rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
    }
    if (!options.daylightCycle()) {
      rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
    }
    if (!options.weatherCycle()) {
      rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
    }
    if (!options.insomnia()) {
      rules.getRule(GameRules.RULE_DOINSOMNIA).set(false, null);
    }
    LevelSettings levelSettings =
        new LevelSettings(
            options.name(),
            gameMode(options.gamemode()),
            false, // hardcore
            difficulty(options.difficulty()),
            options.allowCheats(),
            rules,
            DataPackConfig.DEFAULT);
    Minecraft mc = mc();
    onClientThread(
        () -> {
          RegistryAccess.Frozen registryAccess = RegistryAccess.BUILTIN.get();
          WorldPreset flat =
              registryAccess
                  .registryOrThrow(Registry.WORLD_PRESET_REGISTRY)
                  .getOrThrow(WorldPresets.FLAT);
          WorldGenSettings generatorOptions =
              flat.createWorldGenSettings(options.seed(), options.structures(), false);
          mc.createWorldOpenFlows()
              .createFreshLevel(options.name(), levelSettings, registryAccess, generatorOptions);
        });
  }

  @Override
  public void joinServer(String address) {
    ServerAddress serverAddress = ServerAddress.parseString(address);
    ServerData info = new ServerData(address, address, false);
    onClientThread(
        () -> {
          try {
            Minecraft mc = mc();
            ConnectScreen.startConnecting(mc.screen, mc, serverAddress, info);
            LOG.info(
                String.format(
                    "joinServer: ConnectScreen.startConnecting(%s:%s)",
                    serverAddress.getHost(), serverAddress.getPort()));
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
          if (mc.level != null) {
            mc.level.disconnect();
          }
          // The no-arg clearLevel() lands on a ProgressScreen ("Saving world"), which is not a
          // title screen, so isOnTitleScreen() would never be true after leaving a world. Use the
          // screen-taking overload so the documented phase terminator (back to the title screen)
          // actually happens.
          mc.clearLevel(new TitleScreen());
        });
  }

  @Override
  public void quit() {
    onClientThread(mc()::stop);
  }

  @Override
  public boolean isInWorld() {
    return mc().player != null;
  }

  @Override
  public boolean isWorldReady() {
    return mc().player != null && mc().screen == null && mc().getOverlay() == null;
  }

  @Override
  public boolean isOnTitleScreen() {
    return mc().screen instanceof TitleScreen;
  }

  @Override
  public boolean isConnecting() {
    return mc().screen instanceof ConnectScreen;
  }

  @Override
  public boolean isLoggingIn() {
    Minecraft mc = mc();
    return mc.player == null && mc.getConnection() != null;
  }

  @Override
  public boolean isConnectFailed() {
    return mc().screen instanceof DisconnectedScreen;
  }

  @Override
  public boolean isBootComplete() {
    return mc().getOverlay() == null;
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer server = mc().getSingleplayerServer();
    if (server == null) {
      LOG.warning("worldGenSettled: not in a local/integrated world; treating as settled");
      return true;
    }
    ServerLevel overworld = server.overworld();
    if (overworld == null) {
      LOG.warning("worldGenSettled: no overworld loaded; treating as settled");
      return true;
    }
    int pending = overworld.getChunkSource().getPendingTasksCount();
    LOG.info(String.format("worldGenSettled: %s pending chunk task(s)", pending));
    return pending == 0;
  }

  private static final int GLFW_PRESS = 1;
  private static final int GLFW_RELEASE = 0;

  /**
   * Resolves a script key name to an {@link InputConstants.Key}. Accepts either a key mapping id
   * ("key.jump") or a GLFW-style key name ("F3"), lower-cased to the "key.keyboard.*" namespace.
   * Returns {@code null} when unresolvable.
   */
  private InputConstants.Key resolveKey(String key) {
    if (key.startsWith("key.")) {
      for (KeyMapping mapping : mc().options.keyMappings) {
        if (mapping.getName().equals(key)) {
          return mapping.getKey();
        }
      }
      return null;
    }
    try {
      return InputConstants.getKey("key.keyboard." + key.toLowerCase(Locale.ROOT));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, String.format("keyDown: unresolvable key name '%s'", key), e);
      return null;
    }
  }

  /** Dispatches a synthetic key event through the vanilla {@link KeyboardHandler} handler. */
  /**
   * Dispatches a synthetic key event through the vanilla handler that owns the resolved key type.
   *
   * <p>A binding can be bound to a mouse button — {@code key.attack} and {@code key.use} are, by
   * default — and {@link KeyboardHandler#keyPress} only ever resolves keyboard codes. Dispatching a
   * mouse-bound binding there indexes an unrelated key, so the binding never toggles and the press
   * is silently lost; route it through the mouse handler instead. (Scan codes go through the
   * keyboard handler as a scan code rather than a key symbol.)
   */
  private void dispatchKey(InputConstants.Key key, int action) {
    if (key.getType() == InputConstants.Type.MOUSE) {
      dispatchMouse(key.getValue(), action);
      return;
    }
    KeyboardHandler keyboard = mc().keyboardHandler;
    long handle = mc().getWindow().getWindow();
    boolean scancode = key.getType() == InputConstants.Type.SCANCODE;
    int code = scancode ? -1 : key.getValue();
    int scan = scancode ? key.getValue() : 0;
    onClientThread(() -> keyboard.keyPress(handle, code, scan, action, 0));
  }

  @Override
  public boolean keyDown(String key) {
    InputConstants.Key resolved = resolveKey(key);
    if (resolved == null) {
      return false;
    }
    dispatchKey(resolved, GLFW_PRESS);
    return true;
  }

  @Override
  public void keyUp(String key) {
    InputConstants.Key resolved = resolveKey(key);
    if (resolved != null) {
      dispatchKey(resolved, GLFW_RELEASE);
    }
  }

  /**
   * Reflects into the private {@link MouseHandler#onPress} callback (the GLFW mouse-button
   * handler), so synthetic clicks flow through the same screen/binding dispatch as real input.
   */
  private void dispatchMouse(int button, int action) {
    MouseHandler mouse = mc().mouseHandler;
    long handle = mc().getWindow().getWindow();
    onClientThread(
        () -> {
          try {
            Method m =
                MouseHandler.class.getDeclaredMethod(
                    "onPress", long.class, int.class, int.class, int.class);
            m.setAccessible(true);
            m.invoke(mouse, handle, button, action, 0);
          } catch (ReflectiveOperationException e) {
            LOG.log(Level.SEVERE, String.format("mouse: failed to dispatch button %s", button), e);
          }
        });
  }

  @Override
  public boolean mouseDown(int button) {
    dispatchMouse(button, GLFW_PRESS);
    return true;
  }

  @Override
  public void mouseUp(int button) {
    dispatchMouse(button, GLFW_RELEASE);
  }

  @Override
  public net.minecheck.platform.api.PlayerInfo getPlayer() {
    Minecraft mc = mc();
    if (mc.player == null) {
      return null;
    }
    net.minecraft.world.entity.player.Player p = mc.player;
    return new net.minecheck.platform.api.PlayerInfo(
        p.getGameProfile().getName(),
        p.getX(),
        p.getY(),
        p.getZ(),
        p.getLevel().dimension().location().toString());
  }

  @Override
  public void setLook(float pitch, float yaw) {
    Minecraft mc = mc();
    onClientThread(
        () -> {
          if (mc.player != null) {
            mc.player.setXRot(pitch);
            mc.player.setYRot(yaw);
          }
        });
  }

  @Override
  public void lookAt(double x, double y, double z) {
    Minecraft mc = mc();
    onClientThread(
        () -> {
          if (mc.player != null) {
            mc.player.lookAt(
                net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                new net.minecraft.world.phys.Vec3(x, y, z));
          }
        });
  }

  @Override
  public net.minecheck.platform.api.BlockPosition getTargetBlock(String entityName) {
    Minecraft mc = mc();
    if (mc.player == null
        || !mc.player.getGameProfile().getName().equals(entityName)
        || mc.level == null) {
      return null;
    }
    net.minecraft.world.phys.HitResult hit = mc.player.pick(5.0, 1.0F, false);
    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
      return null;
    }
    net.minecraft.core.BlockPos pos = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
    return new net.minecheck.platform.api.BlockPosition(pos.getX(), pos.getY(), pos.getZ());
  }

  @Override
  public java.util.List<net.minecheck.platform.api.EntityInfo> findNearbyEntities(String type) {
    Minecraft mc = mc();
    net.minecraft.client.multiplayer.ClientLevel level = mc.level;
    if (level == null) {
      // Not in a world: no entities are known, which is a legitimate answer for a script probing
      // before/after a join rather than an error.
      return java.util.List.of();
    }
    net.minecraft.resources.ResourceLocation typeId =
        type == null ? null : net.minecraft.resources.ResourceLocation.tryParse(type);
    java.util.List<net.minecheck.platform.api.EntityInfo> out = new java.util.ArrayList<>();
    for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
      net.minecraft.resources.ResourceLocation actual =
          Registry.ENTITY_TYPE.getKey(entity.getType());
      if (typeId != null && !actual.equals(typeId)) {
        continue;
      }
      // Scoreboard tags are server-side data: the tag set is empty for every client-side entity
      // (vanilla never syncs it), so this is a faithful copy of what the client actually knows.
      out.add(
          new net.minecheck.platform.api.EntityInfo(
              entity.getId(),
              actual.toString(),
              entity.getX(),
              entity.getY(),
              entity.getZ(),
              new java.util.HashSet<>(entity.getTags())));
    }
    return java.util.List.copyOf(out);
  }

  @Override
  public net.minecheck.platform.api.BlockInfo getBlock(
      net.minecheck.platform.api.BlockPosition pos) {
    Minecraft mc = mc();
    if (mc.level == null) {
      throw new IllegalStateException("not in a world");
    }
    net.minecraft.core.BlockPos blockPos =
        new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z());
    if (!mc.level.isLoaded(blockPos)) {
      return null;
    }
    net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(blockPos);
    net.minecraft.world.level.block.entity.BlockEntity blockEntity =
        mc.level.getBlockEntity(blockPos);
    java.util.Map<String, Object> nbt =
        blockEntity == null ? null : nbtToMap(blockEntity.saveWithoutMetadata());
    return new net.minecheck.platform.api.BlockInfo(
        net.minecraft.core.Registry.BLOCK.getKey(state.getBlock()).toString(), nbt);
  }

  // ------------------------------------------------------------ GUI / inventory

  @Override
  public void useItemOnTarget() {
    Minecraft mc = mc();
    if (mc.player == null || mc.gameMode == null || mc.level == null) {
      throw new IllegalStateException("not in a world");
    }
    net.minecraft.world.phys.HitResult hit = mc.player.pick(5.0, 1.0F, false);
    if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
      mc.gameMode.useItemOn(
          mc.player,
          net.minecraft.world.InteractionHand.MAIN_HAND,
          (net.minecraft.world.phys.BlockHitResult) hit);
    }
  }

  @Override
  public boolean clickSlot(int slot, int button, String actionType) {
    Minecraft mc = mc();
    if (!(mc.screen
        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs)) {
      return false;
    }
    mc.gameMode.handleInventoryMouseClick(
        acs.getMenu().containerId, slot, button, clickType(actionType), mc.player);
    return true;
  }

  private static net.minecraft.world.inventory.ClickType clickType(String type) {
    return switch (type.toLowerCase(java.util.Locale.ROOT)) {
      case "quick_move" -> net.minecraft.world.inventory.ClickType.QUICK_MOVE;
      case "swap" -> net.minecraft.world.inventory.ClickType.SWAP;
      case "clone" -> net.minecraft.world.inventory.ClickType.CLONE;
      case "throw" -> net.minecraft.world.inventory.ClickType.THROW;
      case "quick_craft" -> net.minecraft.world.inventory.ClickType.QUICK_CRAFT;
      case "pickup_all" -> net.minecraft.world.inventory.ClickType.PICKUP_ALL;
      default -> net.minecraft.world.inventory.ClickType.PICKUP;
    };
  }

  @Override
  public boolean clickGui(int button, double x, double y) {
    Minecraft mc = mc();
    net.minecraft.client.gui.screens.Screen screen = mc.screen;
    if (screen == null) {
      return false;
    }
    screen.mouseClicked(x, y, button);
    return true;
  }

  @Override
  public boolean clickGuiElement(String elementId, int button, int index) {
    Minecraft mc = mc();
    net.minecraft.client.gui.screens.Screen screen = mc.screen;
    if (screen == null) {
      return false;
    }
    String wanted = elementId.trim().toLowerCase(java.util.Locale.ROOT);
    if (wanted.isEmpty()) {
      return false;
    }
    // Label matches beat class-name matches: the label is the same string on every loader, the
    // class
    // name is not (`Button` here, `ButtonWidget` on yarn).
    java.util.List<net.minecraft.client.gui.components.AbstractWidget> byLabel =
        new java.util.ArrayList<>();
    java.util.List<net.minecraft.client.gui.components.AbstractWidget> byClass =
        new java.util.ArrayList<>();
    for (net.minecraft.client.gui.components.events.GuiEventListener element : screen.children()) {
      if (!(element instanceof net.minecraft.client.gui.components.AbstractWidget widget)) {
        continue;
      }
      String label = widget.getMessage().getString().trim().toLowerCase(java.util.Locale.ROOT);
      if (label.equals(wanted)) {
        byLabel.add(widget);
      } else if (widget
          .getClass()
          .getSimpleName()
          .toLowerCase(java.util.Locale.ROOT)
          .equals(wanted)) {
        byClass.add(widget);
      }
    }
    java.util.List<net.minecraft.client.gui.components.AbstractWidget> matches =
        byLabel.isEmpty() ? byClass : byLabel;
    int at = index <= 1 ? 0 : index - 1;
    if (at >= matches.size()) {
      return false;
    }
    net.minecraft.client.gui.components.AbstractWidget target = matches.get(at);
    // The centre is inside the widget, so its own isMouseOver/clicked check passes.
    target.mouseClicked(
        target.x + target.getWidth() / 2.0, target.y + target.getHeight() / 2.0, button);
    return true;
  }

  @Override
  public void runCommand(String command) {
    Minecraft mc = mc();
    if (mc.player == null || mc.getConnection() == null) {
      throw new IllegalStateException("not connected to a world/server");
    }
    mc.player.commandUnsigned(command);
  }

  @Override
  public boolean typeText(String text, String target) {
    Minecraft mc = mc();
    String kind = target == null ? "" : target.toLowerCase(java.util.Locale.ROOT);
    // The chat screen must already be open (openChat) — this only types into it.
    net.minecraft.client.gui.screens.Screen screen = mc.screen;
    if (screen == null) {
      return false;
    }
    net.minecraft.client.gui.components.EditBox field = findEditBox(screen);
    if (field == null) {
      return false;
    }
    if (screen.getFocused() != field) {
      screen.setInitialFocus(field);
    }
    if (kind.equals("command") && !field.getValue().startsWith("/")) {
      field.setValue("/" + field.getValue());
    }
    boolean dispatched = false;
    for (int i = 0; i < text.length(); i++) {
      if (screen.charTyped(text.charAt(i), 0)) {
        dispatched = true;
      }
    }
    return dispatched;
  }

  /** The screen's focused edit box, or its first edit box when none is focused. */
  private static net.minecraft.client.gui.components.EditBox findEditBox(
      net.minecraft.client.gui.screens.Screen screen) {
    if (screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox focused) {
      return focused;
    }
    for (net.minecraft.client.gui.components.events.GuiEventListener element : screen.children()) {
      if (element instanceof net.minecraft.client.gui.components.EditBox box) {
        return box;
      }
    }
    return null;
  }

  @Override
  public boolean isScreenOpen(String screenId) {
    net.minecraft.client.gui.screens.Screen screen = mc().screen;
    if (screen == null) {
      return false;
    }
    if (screenId == null || screenId.isEmpty()) {
      return screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
    }
    return switch (screenId) {
      // Creative mode opens CreativeModeInventoryScreen, which does NOT extend InventoryScreen
      // (both extend AbstractContainerScreen), so match either or openScreen("inventory") would
      // time out for a creative player.
      case "inventory" ->
          screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
              || screen
                  instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
      case "chat" -> screen instanceof net.minecraft.client.gui.screens.ChatScreen;
      case "beacon" -> screen instanceof net.minecraft.client.gui.screens.inventory.BeaconScreen;
      case "chest" -> screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen;
      case "crafting" ->
          screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen;
      case "furnace" -> screen instanceof net.minecraft.client.gui.screens.inventory.FurnaceScreen;
      default -> false;
    };
  }

  private net.minecraft.world.inventory.AbstractContainerMenu openMenu(String screenId) {
    net.minecraft.client.gui.screens.Screen screen = mc().screen;
    if (!(screen
        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs)) {
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
    return acs.getMenu();
  }

  @Override
  public int slotCount(String screenId) {
    try {
      return openMenu(screenId).slots.size();
    } catch (IllegalStateException e) {
      return 0;
    }
  }

  @Override
  public net.minecheck.platform.api.SlotInfo getSlot(int index, String screenId) {
    net.minecraft.world.inventory.AbstractContainerMenu menu = openMenu(screenId);
    if (index < 0 || index >= menu.slots.size()) {
      throw new IllegalStateException(
          "slot index " + index + " out of range (0.." + (menu.slots.size() - 1) + ")");
    }
    net.minecraft.world.item.ItemStack stack = menu.slots.get(index).getItem();
    if (stack.isEmpty()) {
      return new net.minecheck.platform.api.SlotInfo(true, null, 0, null);
    }
    net.minecraft.nbt.CompoundTag nbt = stack.getTag();
    return new net.minecheck.platform.api.SlotInfo(
        false,
        net.minecraft.core.Registry.ITEM.getKey(stack.getItem()).toString(),
        stack.getCount(),
        nbt == null ? null : nbtToMap(nbt));
  }

  private static java.util.Map<String, Object> nbtToMap(net.minecraft.nbt.CompoundTag compound) {
    java.util.Map<String, Object> out = new java.util.HashMap<>();
    for (String key : compound.getAllKeys()) {
      out.put(key, nbtValue(compound.get(key)));
    }
    return out;
  }

  private static Object nbtValue(net.minecraft.nbt.Tag tag) {
    if (tag == null) {
      return null;
    }
    if (tag instanceof net.minecraft.nbt.StringTag s) {
      return s.getAsString();
    }
    if (tag instanceof net.minecraft.nbt.NumericTag n) {
      if (tag instanceof net.minecraft.nbt.LongTag l) {
        return l.getAsLong();
      }
      if (tag instanceof net.minecraft.nbt.IntTag i) {
        return i.getAsInt();
      }
      if (tag instanceof net.minecraft.nbt.ByteTag b) {
        return b.getAsByte();
      }
      if (tag instanceof net.minecraft.nbt.ShortTag sh) {
        return sh.getAsShort();
      }
      return n.getAsDouble();
    }
    if (tag instanceof net.minecraft.nbt.CompoundTag c) {
      return nbtToMap(c);
    }
    if (tag instanceof net.minecraft.nbt.ListTag l) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (net.minecraft.nbt.Tag t : l) {
        out.add(nbtValue(t));
      }
      return out;
    }
    if (tag instanceof net.minecraft.nbt.ByteArrayTag b) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (byte v : b.getAsByteArray()) {
        out.add(v);
      }
      return out;
    }
    if (tag instanceof net.minecraft.nbt.IntArrayTag a) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (int v : a.getAsIntArray()) {
        out.add(v);
      }
      return out;
    }
    if (tag instanceof net.minecraft.nbt.LongArrayTag a) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (long v : a.getAsLongArray()) {
        out.add(v);
      }
      return out;
    }
    return null;
  }

  private static GameType gameMode(String name) {

    return switch (name.toLowerCase()) {
      case "survival" -> GameType.SURVIVAL;
      case "adventure" -> GameType.ADVENTURE;
      case "spectator" -> GameType.SPECTATOR;
      default -> GameType.CREATIVE;
    };
  }

  private static Difficulty difficulty(String name) {
    return switch (name.toLowerCase()) {
      case "peaceful" -> Difficulty.PEACEFUL;
      case "easy" -> Difficulty.EASY;
      case "hard" -> Difficulty.HARD;
      default -> Difficulty.NORMAL;
    };
  }
}
