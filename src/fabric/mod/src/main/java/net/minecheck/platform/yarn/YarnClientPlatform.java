package net.minecheck.platform.yarn;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.CreateWorldOptions;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;

public class YarnClientPlatform implements ClientPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private MinecraftClient mc() {
    return MinecraftClient.getInstance();
  }

  private void onClientThread(Runnable r) {
    MinecraftClient mc = mc();
    if (mc.isOnThread()) {
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
    onClientThread(() -> resourceReload = mc().reloadResources());
  }

  @Override
  public boolean isResourceReloadSettled() {
    java.util.concurrent.CompletableFuture<Void> reload = resourceReload;
    return !reloadRequested || (reload != null && reload.isDone());
  }

  @Override
  public void openChat(String mode) {
    boolean command = mode != null && mode.equalsIgnoreCase("command");
    onClientThread(
        () -> mc().setScreen(new net.minecraft.client.gui.screen.ChatScreen(command ? "/" : "")));
  }

  @Override
  public void joinLocalWorld(String worldName) {
    MinecraftClient mc = mc();
    LevelStorage storage = mc.getLevelStorage();
    boolean found = false;
    try {
      for (LevelStorage.LevelSave save : storage.getLevelList()) {
        if (save.getRootPath().equals(worldName)) {
          found = true;
          break;
        }
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to read level list: " + e.getMessage(), e);
    }
    if (!found) {
      throw new IllegalArgumentException("World not found: " + worldName);
    }
    IntegratedServerLoader loader = mc.createIntegratedServerLoader();
    onClientThread(() -> loader.start(mc.currentScreen, worldName));
  }

  @Override
  public void createWorld(CreateWorldOptions options) {
    GameRules rules = new GameRules();
    if (!options.mobSpawning()) {
      rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
    }
    if (!options.daylightCycle()) {
      rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
    }
    if (!options.weatherCycle()) {
      rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
    }
    if (!options.insomnia()) {
      rules.get(GameRules.DO_INSOMNIA).set(false, null);
    }
    LevelInfo levelInfo =
        new LevelInfo(
            options.name(),
            gameMode(options.gamemode()),
            false, // hardcore
            difficulty(options.difficulty()),
            options.allowCheats(),
            rules,
            DataPackSettings.SAFE_MODE);
    MinecraftClient mc = mc();
    onClientThread(
        () -> {
          DynamicRegistryManager.Immutable registryManager =
              DynamicRegistryManager.createAndLoad().toImmutable();
          WorldPreset flat =
              registryManager.get(Registry.WORLD_PRESET_KEY).getOrThrow(WorldPresets.FLAT);
          GeneratorOptions generatorOptions =
              flat.createGeneratorOptions(options.seed(), options.structures(), false);
          mc.createIntegratedServerLoader()
              .createAndStart(options.name(), levelInfo, registryManager, generatorOptions);
        });
  }

  @Override
  public void joinServer(String address) {
    ServerAddress serverAddress = ServerAddress.parse(address);
    ServerInfo info = new ServerInfo(address, address, false);
    onClientThread(
        () -> {
          try {
            ConnectScreen.connect(mc().currentScreen, mc(), serverAddress, info);
            LOG.info(
                String.format(
                    "joinServer: ConnectScreen.connect(%s:%s)",
                    serverAddress.getAddress(), serverAddress.getPort()));
          } catch (Throwable t) {
            LOG.log(Level.SEVERE, "joinServer: failed to connect", t);
          }
        });
  }

  @Override
  public void leaveWorld() {
    MinecraftClient mc = mc();
    onClientThread(
        () -> {
          if (mc.world != null) {
            mc.world.disconnect();
          }
          // The no-arg disconnect() lands on a ProgressScreen ("Saving world"), which is not a
          // title screen, so isOnTitleScreen() would never be true after leaving a world. Use the
          // screen-taking overload so the documented phase terminator (back to the title screen)
          // actually happens.
          mc.disconnect(new TitleScreen());
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
    return mc().player != null && mc().currentScreen == null && mc().getOverlay() == null;
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
    return mc().player == null && mc().getNetworkHandler() != null;
  }

  @Override
  public boolean isConnectFailed() {
    return mc().currentScreen instanceof DisconnectedScreen;
  }

  @Override
  public boolean isBootComplete() {
    return mc().getOverlay() == null;
  }

  @Override
  public boolean isWorldGenSettled() {
    MinecraftServer server = mc().getServer();
    if (server == null) {
      LOG.warning("worldGenSettled: not in a local/integrated world; treating as settled");
      return true;
    }
    ServerWorld overworld = server.getWorld(World.OVERWORLD);
    if (overworld == null) {
      LOG.warning("worldGenSettled: no overworld loaded; treating as settled");
      return true;
    }
    int pending = overworld.getChunkManager().getPendingTasks();
    LOG.info(String.format("worldGenSettled: %s pending chunk task(s)", pending));
    return pending == 0;
  }

  private static final int GLFW_PRESS = 1;
  private static final int GLFW_RELEASE = 0;

  /**
   * Resolves a script key name to an {@link InputUtil.Key}. Accepts either a key binding id
   * ("key.jump") or a GLFW-style key name ("F3"), lower-cased to the "key.keyboard.*" namespace.
   * Returns {@code null} when unresolvable.
   */
  private InputUtil.Key resolveKey(String key) {
    if (key.startsWith("key.")) {
      for (KeyBinding binding : mc().options.allKeys) {
        if (binding.getTranslationKey().equals(key)) {
          try {
            String bound = binding.getBoundKeyTranslationKey();
            if (bound == null || binding.isUnbound()) {
              return null;
            }
            return InputUtil.fromTranslationKey(bound);
          } catch (RuntimeException e) {
            LOG.log(Level.WARNING, String.format("keyDown: unresolvable binding '%s'", key), e);
            return null;
          }
        }
      }
      return null;
    }
    try {
      return InputUtil.fromTranslationKey("key.keyboard." + key.toLowerCase(Locale.ROOT));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, String.format("keyDown: unresolvable key name '%s'", key), e);
      return null;
    }
  }

  /**
   * Dispatches a synthetic key event through the vanilla handler that owns the resolved key type.
   *
   * <p>A binding can be bound to a mouse button — {@code key.attack} and {@code key.use} are, by
   * default — and {@link Keyboard#onKey} only ever resolves keyboard codes. Dispatching a
   * mouse-bound binding there indexes an unrelated key, so the binding never toggles and the press
   * is silently lost; route it through the mouse handler instead. (Scan codes go through the
   * keyboard handler as a scan code rather than a key symbol.)
   */
  private void dispatchKey(InputUtil.Key key, int action) {
    if (key.getCategory() == InputUtil.Type.MOUSE) {
      dispatchMouse(key.getCode(), action);
      return;
    }
    Keyboard keyboard = mc().keyboard;
    long handle = mc().getWindow().getHandle();
    boolean scancode = key.getCategory() == InputUtil.Type.SCANCODE;
    int code = scancode ? -1 : key.getCode();
    int scan = scancode ? key.getCode() : 0;
    onClientThread(() -> keyboard.onKey(handle, code, scan, action, 0));
  }

  @Override
  public boolean keyDown(String key) {
    InputUtil.Key resolved = resolveKey(key);
    if (resolved == null) {
      return false;
    }
    dispatchKey(resolved, GLFW_PRESS);
    return true;
  }

  @Override
  public void keyUp(String key) {
    InputUtil.Key resolved = resolveKey(key);
    if (resolved != null) {
      dispatchKey(resolved, GLFW_RELEASE);
    }
  }

  /**
   * Reflects into the private {@link Mouse#onMouseButton} callback (the GLFW mouse-button handler),
   * so synthetic clicks flow through the same screen/binding dispatch as real input.
   */
  private void dispatchMouse(int button, int action) {
    Mouse mouse = mc().mouse;
    long handle = mc().getWindow().getHandle();
    onClientThread(
        () -> {
          try {
            Method m =
                Mouse.class.getDeclaredMethod(
                    "onMouseButton", long.class, int.class, int.class, int.class);
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
    MinecraftClient mc = mc();
    if (mc.player == null) {
      return null;
    }
    return new net.minecheck.platform.api.PlayerInfo(
        mc.player.getEntityName(),
        mc.player.getX(),
        mc.player.getY(),
        mc.player.getZ(),
        mc.player.getEntityWorld().getRegistryKey().getValue().toString());
  }

  @Override
  public void setLook(float pitch, float yaw) {
    MinecraftClient mc = mc();
    onClientThread(
        () -> {
          if (mc.player != null) {
            mc.player.setPitch(pitch);
            mc.player.setYaw(yaw);
          }
        });
  }

  @Override
  public void lookAt(double x, double y, double z) {
    MinecraftClient mc = mc();
    onClientThread(
        () -> {
          if (mc.player != null) {
            mc.player.lookAt(
                net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
                new net.minecraft.util.math.Vec3d(x, y, z));
          }
        });
  }

  @Override
  public net.minecheck.platform.api.BlockPosition getTargetBlock(String entityName) {
    MinecraftClient mc = mc();
    if (mc.player == null || !mc.player.getEntityName().equals(entityName) || mc.world == null) {
      return null;
    }
    net.minecraft.util.hit.HitResult hit = mc.player.raycast(5.0, 1.0F, false);
    if (hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
      return null;
    }
    net.minecraft.util.math.BlockPos pos =
        ((net.minecraft.util.hit.BlockHitResult) hit).getBlockPos();
    return new net.minecheck.platform.api.BlockPosition(pos.getX(), pos.getY(), pos.getZ());
  }

  @Override
  public java.util.List<net.minecheck.platform.api.EntityInfo> findNearbyEntities(String type) {
    MinecraftClient mc = mc();
    net.minecraft.client.world.ClientWorld world = mc.world;
    if (world == null) {
      // Not in a world: no entities are known, which is a legitimate answer for a script probing
      // before/after a join rather than an error.
      return java.util.List.of();
    }
    net.minecraft.util.Identifier typeId =
        type == null ? null : net.minecraft.util.Identifier.tryParse(type);
    java.util.List<net.minecheck.platform.api.EntityInfo> out = new java.util.ArrayList<>();
    for (net.minecraft.entity.Entity entity : world.getEntities()) {
      net.minecraft.util.Identifier actual = Registry.ENTITY_TYPE.getId(entity.getType());
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
              new java.util.HashSet<>(entity.getScoreboardTags())));
    }
    return java.util.List.copyOf(out);
  }

  @Override
  public net.minecheck.platform.api.BlockInfo getBlock(
      net.minecheck.platform.api.BlockPosition pos) {
    MinecraftClient mc = mc();
    if (mc.world == null) {
      throw new IllegalStateException("not in a world");
    }
    net.minecraft.util.math.BlockPos blockPos =
        new net.minecraft.util.math.BlockPos(pos.x(), pos.y(), pos.z());
    if (!mc.world.getChunkManager().isChunkLoaded(blockPos.getX() >> 4, blockPos.getZ() >> 4)) {
      return null;
    }
    net.minecraft.block.BlockState state = mc.world.getBlockState(blockPos);
    net.minecraft.block.entity.BlockEntity blockEntity = mc.world.getBlockEntity(blockPos);
    java.util.Map<String, Object> nbt =
        blockEntity == null ? null : nbtToMap(blockEntity.createNbt());
    return new net.minecheck.platform.api.BlockInfo(
        net.minecraft.util.registry.Registry.BLOCK.getId(state.getBlock()).toString(), nbt);
  }

  // ------------------------------------------------------------ GUI / inventory

  @Override
  public void useItemOnTarget() {
    MinecraftClient mc = mc();
    if (mc.player == null || mc.interactionManager == null || mc.world == null) {
      throw new IllegalStateException("not in a world");
    }
    net.minecraft.util.hit.HitResult hit = mc.player.raycast(5.0, 1.0F, false);
    if (hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
      mc.interactionManager.interactBlock(
          mc.player,
          net.minecraft.util.Hand.MAIN_HAND,
          (net.minecraft.util.hit.BlockHitResult) hit);
    }
  }

  @Override
  public boolean clickSlot(int slot, int button, String actionType) {
    MinecraftClient mc = mc();
    if (!(mc.currentScreen
        instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> handled)) {
      return false;
    }
    net.minecraft.screen.ScreenHandler handler = handled.getScreenHandler();
    mc.interactionManager.clickSlot(
        handler.syncId, slot, button, slotAction(actionType), mc.player);
    return true;
  }

  private static net.minecraft.screen.slot.SlotActionType slotAction(String type) {
    return switch (type.toLowerCase(java.util.Locale.ROOT)) {
      case "quick_move" -> net.minecraft.screen.slot.SlotActionType.QUICK_MOVE;
      case "swap" -> net.minecraft.screen.slot.SlotActionType.SWAP;
      case "clone" -> net.minecraft.screen.slot.SlotActionType.CLONE;
      case "throw" -> net.minecraft.screen.slot.SlotActionType.THROW;
      case "quick_craft" -> net.minecraft.screen.slot.SlotActionType.QUICK_CRAFT;
      case "pickup_all" -> net.minecraft.screen.slot.SlotActionType.PICKUP_ALL;
      default -> net.minecraft.screen.slot.SlotActionType.PICKUP;
    };
  }

  @Override
  public boolean clickGui(int button, double x, double y) {
    MinecraftClient mc = mc();
    net.minecraft.client.gui.screen.Screen screen = mc.currentScreen;
    if (screen == null) {
      return false;
    }
    screen.mouseClicked(x, y, button);
    return true;
  }

  @Override
  public boolean clickGuiElement(String elementId, int button, int index) {
    MinecraftClient mc = mc();
    net.minecraft.client.gui.screen.Screen screen = mc.currentScreen;
    if (screen == null) {
      return false;
    }
    String wanted = elementId.trim().toLowerCase(Locale.ROOT);
    if (wanted.isEmpty()) {
      return false;
    }
    // Label matches beat class-name matches: the label is the same string on every loader, the
    // class
    // name is not (`ButtonWidget` here, `Button` on mojmap).
    java.util.List<net.minecraft.client.gui.widget.ClickableWidget> byLabel =
        new java.util.ArrayList<>();
    java.util.List<net.minecraft.client.gui.widget.ClickableWidget> byClass =
        new java.util.ArrayList<>();
    for (net.minecraft.client.gui.Element element : screen.children()) {
      if (!(element instanceof net.minecraft.client.gui.widget.ClickableWidget widget)) {
        continue;
      }
      String label = widget.getMessage().getString().trim().toLowerCase(Locale.ROOT);
      if (label.equals(wanted)) {
        byLabel.add(widget);
      } else if (widget.getClass().getSimpleName().toLowerCase(Locale.ROOT).equals(wanted)) {
        byClass.add(widget);
      }
    }
    java.util.List<net.minecraft.client.gui.widget.ClickableWidget> matches =
        byLabel.isEmpty() ? byClass : byLabel;
    int at = index <= 1 ? 0 : index - 1;
    if (at >= matches.size()) {
      return false;
    }
    net.minecraft.client.gui.widget.ClickableWidget target = matches.get(at);
    // The centre is inside the widget, so its own isMouseOver/clicked check passes.
    target.mouseClicked(
        target.x + target.getWidth() / 2.0, target.y + target.getHeight() / 2.0, button);
    return true;
  }

  @Override
  public void runCommand(String command) {
    MinecraftClient mc = mc();
    if (mc.player == null || mc.getNetworkHandler() == null) {
      throw new IllegalStateException("not connected to a world/server");
    }
    mc.player.sendCommand(command, net.minecraft.text.Text.empty());
  }

  @Override
  public boolean typeText(String text, String target) {
    MinecraftClient mc = mc();
    String kind = target == null ? "" : target.toLowerCase(Locale.ROOT);
    // The chat screen must already be open (openChat) — this only types into it.
    net.minecraft.client.gui.screen.Screen screen = mc.currentScreen;
    if (screen == null) {
      return false;
    }
    net.minecraft.client.gui.widget.TextFieldWidget field = findTextField(screen);
    if (field == null) {
      return false;
    }
    if (screen.getFocused() != field) {
      screen.setInitialFocus(field);
    }
    if (kind.equals("command") && !field.getText().startsWith("/")) {
      field.setText("/" + field.getText());
    }
    boolean dispatched = false;
    for (int i = 0; i < text.length(); i++) {
      if (screen.charTyped(text.charAt(i), 0)) {
        dispatched = true;
      }
    }
    return dispatched;
  }

  /** The screen's focused text field, or its first text field when none is focused. */
  private static net.minecraft.client.gui.widget.TextFieldWidget findTextField(
      net.minecraft.client.gui.screen.Screen screen) {
    if (screen.getFocused() instanceof net.minecraft.client.gui.widget.TextFieldWidget focused) {
      return focused;
    }
    for (net.minecraft.client.gui.Element element : screen.children()) {
      if (element instanceof net.minecraft.client.gui.widget.TextFieldWidget widget) {
        return widget;
      }
    }
    return null;
  }

  @Override
  public boolean isScreenOpen(String screenId) {
    net.minecraft.client.gui.screen.Screen screen = mc().currentScreen;
    if (screen == null) {
      return false;
    }
    if (screenId == null || screenId.isEmpty()) {
      return screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen;
    }
    return switch (screenId) {
      // Creative mode opens CreativeInventoryScreen, which does NOT extend InventoryScreen
      // (both extend AbstractInventoryScreen), so match either or openScreen("inventory") would
      // time out for a creative player.
      case "inventory" ->
          screen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen
              || screen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
      case "chat" -> screen instanceof net.minecraft.client.gui.screen.ChatScreen;
      case "beacon" -> screen instanceof net.minecraft.client.gui.screen.ingame.BeaconScreen;
      case "chest" ->
          screen instanceof net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
      case "crafting" -> screen instanceof net.minecraft.client.gui.screen.ingame.CraftingScreen;
      case "furnace" -> screen instanceof net.minecraft.client.gui.screen.ingame.FurnaceScreen;
      default -> false;
    };
  }

  private net.minecraft.screen.ScreenHandler openHandler(String screenId) {
    net.minecraft.client.gui.screen.Screen screen = mc().currentScreen;
    if (!(screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> handled)) {
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
    return handled.getScreenHandler();
  }

  @Override
  public int slotCount(String screenId) {
    try {
      return openHandler(screenId).slots.size();
    } catch (IllegalStateException e) {
      return 0;
    }
  }

  @Override
  public net.minecheck.platform.api.SlotInfo getSlot(int index, String screenId) {
    net.minecraft.screen.ScreenHandler handler = openHandler(screenId);
    if (index < 0 || index >= handler.slots.size()) {
      throw new IllegalStateException(
          "slot index " + index + " out of range (0.." + (handler.slots.size() - 1) + ")");
    }
    net.minecraft.item.ItemStack stack = handler.slots.get(index).getStack();
    if (stack.isEmpty()) {
      return new net.minecheck.platform.api.SlotInfo(true, null, 0, null);
    }
    net.minecraft.nbt.NbtCompound nbt = stack.getNbt();
    return new net.minecheck.platform.api.SlotInfo(
        false,
        net.minecraft.util.registry.Registry.ITEM.getId(stack.getItem()).toString(),
        stack.getCount(),
        nbt == null ? null : nbtToMap(nbt));
  }

  private static java.util.Map<String, Object> nbtToMap(net.minecraft.nbt.NbtCompound compound) {
    java.util.Map<String, Object> out = new java.util.HashMap<>();
    for (String key : compound.getKeys()) {
      out.put(key, nbtValue(compound.get(key)));
    }
    return out;
  }

  private static Object nbtValue(net.minecraft.nbt.NbtElement el) {
    if (el == null) {
      return null;
    }
    if (el instanceof net.minecraft.nbt.NbtString s) {
      return s.asString();
    }
    if (el instanceof net.minecraft.nbt.AbstractNbtNumber n) {
      if (el instanceof net.minecraft.nbt.NbtLong l) {
        return l.longValue();
      }
      if (el instanceof net.minecraft.nbt.NbtInt i) {
        return i.intValue();
      }
      if (el instanceof net.minecraft.nbt.NbtByte b) {
        return b.byteValue();
      }
      if (el instanceof net.minecraft.nbt.NbtShort sh) {
        return sh.shortValue();
      }
      return n.doubleValue();
    }
    if (el instanceof net.minecraft.nbt.NbtCompound c) {
      return nbtToMap(c);
    }
    if (el instanceof net.minecraft.nbt.NbtList l) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (net.minecraft.nbt.NbtElement e : l) {
        out.add(nbtValue(e));
      }
      return out;
    }
    if (el instanceof net.minecraft.nbt.NbtByteArray b) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (byte v : b.getByteArray()) {
        out.add(v);
      }
      return out;
    }
    if (el instanceof net.minecraft.nbt.NbtIntArray a) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (int v : a.getIntArray()) {
        out.add(v);
      }
      return out;
    }
    if (el instanceof net.minecraft.nbt.NbtLongArray a) {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (long v : a.getLongArray()) {
        out.add(v);
      }
      return out;
    }
    return null;
  }

  private static GameMode gameMode(String name) {
    return switch (name.toLowerCase()) {
      case "survival" -> GameMode.SURVIVAL;
      case "adventure" -> GameMode.ADVENTURE;
      case "spectator" -> GameMode.SPECTATOR;
      default -> GameMode.CREATIVE;
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
