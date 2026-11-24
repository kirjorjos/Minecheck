package net.minecheck.loader.legacyfabric;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatformBase;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;

/**
 * Legacy Fabric transport over vanilla custom-payload packets.
 *
 * <p>Legacy Fabric ships the loader and Mixin but no Fabric API, so there is no channel API to
 * register on: the mod's own channel travels as an ordinary {@code minecheck:main} custom payload,
 * sent with the packet vanilla already has and received through the two payload mixins. That is the
 * same wire shape the Forge line puts inside its network wrapper, so a client and a server of
 * either loader can exchange the same encoded {@link MinecheckPacket} frames.
 *
 * <p>1.4.7 has one payload packet class for both directions, so both directions are built by {@link
 * LegacyPayloads}. Observing <em>another mod's</em> channel is the same hook: the play handler sees
 * every custom payload, unknown channels included.
 */
public class LegacyFabricNetworkPlatform extends NetworkPlatformBase {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** The mod's own channel, and the one both directions of the packet protocol share. */
  static final String CHANNEL_NAME = "minecheck:main";

  /** The process's one network platform; the mixins deliver into it. */
  private static volatile Consumer<MinecheckPacket> sink = packet -> {};

  /** Watched channels by id, each with its observers. */
  private static final java.util.Map<String, List<Consumer<byte[]>>> OBSERVERS =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final boolean dedicated;

  public LegacyFabricNetworkPlatform(boolean dedicated) {
    this.dedicated = dedicated;
  }

  @Override
  public boolean isAvailable() {
    if (dedicated) {
      return LegacyFabricEntrypoint.server() != null;
    }
    return net.minecraft.client.Minecraft.getMinecraft() != null;
  }

  @Override
  public boolean send(String name, List<PacketValue> args) {
    if (!isAvailable()) {
      return false;
    }
    byte[] payload = new MinecheckPacket(name, args).encode();
    try {
      if (dedicated) {
        net.minecraft.server.MinecraftServer server = LegacyFabricEntrypoint.server();
        if (server == null || server.getPlayerManager().getCurrentPlayerCount() == 0) {
          return false;
        }
        // This line keeps the players in a raw public list rather than behind an accessor.
        for (Object candidate : server.getPlayerManager().players) {
          net.minecraft.entity.player.ServerPlayerEntity player =
              (net.minecraft.entity.player.ServerPlayerEntity) candidate;
          player.field_2823.sendPacket(LegacyPayloads.toClient(payload));
        }
      } else {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getMinecraft();
        net.minecraft.client.network.ClientLoopbackPlayNetworkHandler handler =
            client == null ? null : client.method_2960();
        if (handler == null) {
          return false;
        }
        handler.sendPacket(LegacyPayloads.toServer(payload));
      }
      return true;
    } catch (RuntimeException e) {
      LOG.warning(
          String.format("Minecheck: failed to send packet %s: %s", name, String.valueOf(e)));
      return false;
    }
  }

  @Override
  public boolean sendTo(String playerName, String name, List<PacketValue> args) {
    if (!dedicated) {
      return false;
    }
    net.minecraft.server.MinecraftServer server = LegacyFabricEntrypoint.server();
    if (server == null) {
      return false;
    }
    net.minecraft.entity.player.ServerPlayerEntity player =
        server.getPlayerManager().getPlayer(playerName);
    if (player == null) {
      return false;
    }
    byte[] payload = new MinecheckPacket(name, args).encode();
    try {
      player.field_2823.sendPacket(LegacyPayloads.toClient(payload));
      return true;
    } catch (RuntimeException e) {
      LOG.warning(
          String.format(
              "Minecheck: failed to send packet %s to %s: %s",
              name, playerName, String.valueOf(e)));
      return false;
    }
  }

  @Override
  public void setReceiver(Consumer<MinecheckPacket> receiver) {
    sink = receiver;
  }

  /**
   * Registers {@code observer} for {@code channel}. Unlike the Forge line this needs no
   * per-connection install: the payload mixins are always in the path. A client that is not on a
   * server yet still answers true, because the observer is what is being registered and the
   * connection that matters may come later.
   */
  @Override
  public boolean observeChannel(String channel, Consumer<byte[]> observer) {
    if (ours(channel)) {
      // The mod's own transport consumes this channel in the payload mixin, so a second observer
      // here would be shadowed rather than fed. Reporting it as watched would be a lie.
      return false;
    }
    // Replaced, not appended: one engine owns a channel at a time.
    OBSERVERS.put(
        channel, new CopyOnWriteArrayList<>(java.util.Collections.singletonList(observer)));
    return true;
  }

  /** One decoded custom payload, from the netty thread, re-dispatched by the payload mixins. */
  static void observe(String channel, byte[] payload) {
    List<Consumer<byte[]>> observers = OBSERVERS.get(channel);
    if (observers == null || observers.isEmpty()) {
      return;
    }
    for (Consumer<byte[]> observer : observers) {
      observer.accept(payload);
    }
  }

  /** One payload on the mod's own channel, decoded into the script engine's inbox. */
  static void deliver(byte[] payload) {
    try {
      sink.accept(MinecheckPacket.decode(payload));
    } catch (WireFormatException e) {
      LOG.warning(
          String.format(
              "Minecheck: dropped a malformed packet: %s", String.valueOf(e.getMessage())));
    }
  }

  /** Whether the channel is the mod's own. */
  static boolean ours(String channel) {
    return CHANNEL_NAME.equals(channel);
  }

  /**
   * One clientbound custom payload, on the client thread.
   *
   * <p>This line's client play handler runs on the client thread (there is no netty pipeline in
   * front of it), so the payload is dispatched where it arrives rather than queued.
   */
  public static void onClientPayload(CustomPayloadC2SPacket packet) {
    if (packet == null) {
      return;
    }
    String channel = LegacyPayloads.channelOf(packet);
    byte[] payload = LegacyPayloads.bytesOf(packet);
    if (channel == null || payload == null) {
      return;
    }
    dispatch(channel, payload);
  }

  /**
   * One serverbound custom payload: the server side has no task queue exposed here, so the payload
   * is parked and drained from the server tick, which is where the inbox and the observers run.
   */
  public static void onServerPayload(CustomPayloadC2SPacket packet) {
    if (packet == null) {
      return;
    }
    String channel = LegacyPayloads.channelOf(packet);
    byte[] payload = LegacyPayloads.bytesOf(packet);
    if (channel == null || payload == null) {
      return;
    }
    if (LegacyFabricEntrypoint.server() == null) {
      return;
    }
    SERVER_TASKS.add(() -> dispatch(channel, payload));
  }

  /** Payloads waiting for the server thread, drained at the end of each tick. */
  private static final java.util.Queue<Runnable> SERVER_TASKS =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  /** Runs the payloads that arrived since the last tick, on the server thread. */
  static void drainServerTasks() {
    Runnable task;
    while ((task = SERVER_TASKS.poll()) != null) {
      try {
        task.run();
      } catch (RuntimeException e) {
        LOG.warning("Minecheck: a delivered payload failed: " + e);
      }
    }
  }

  /** Hands one payload to the mod's inbox or to the channel's observers. */
  private static void dispatch(String channel, byte[] payload) {
    if (ours(channel)) {
      deliver(payload);
      return;
    }
    observe(channel, payload);
  }
}
