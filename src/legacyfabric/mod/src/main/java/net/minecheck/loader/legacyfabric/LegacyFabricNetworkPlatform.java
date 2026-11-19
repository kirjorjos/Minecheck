package net.minecheck.loader.legacyfabric;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;

/**
 * Legacy Fabric transport over vanilla custom-payload packets.
 *
 * <p>Legacy Fabric ships the loader and Mixin but no Fabric API, so there is no channel API to
 * register on: the mod's own channel travels as an ordinary {@code minecheck:main} custom payload,
 * sent with the packets vanilla already has and received through the two payload mixins. That is
 * the same wire shape the Forge line puts inside its {@code SimpleNetworkWrapper}, so a client and
 * a server of either loader can exchange the same encoded {@link MinecheckPacket} frames.
 *
 * <p>Observing <em>another mod's</em> channel is the same hook: 1.12.2 hands every custom payload
 * to the play handler, unknown channels included, so the payload mixins see a foreign channel's
 * bytes as they arrive. No netty tap is needed here, and none is installed: a handler in the
 * pipeline below the play handler would be blind by construction.
 */
public class LegacyFabricNetworkPlatform implements NetworkPlatform {

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
    return net.minecraft.client.MinecraftClient.getInstance() != null;
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
        for (net.minecraft.entity.player.ServerPlayerEntity player :
            server.getPlayerManager().getPlayers()) {
          player.networkHandler.sendPacket(LegacyPayloads.toClient(payload));
        }
      } else {
        net.minecraft.client.MinecraftClient client =
            net.minecraft.client.MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
          return false;
        }
        client.getNetworkHandler().sendPacket(LegacyPayloads.toServer(payload));
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
      player.networkHandler.sendPacket(LegacyPayloads.toClient(payload));
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
   * connection that matters may come later — the same reason the modern lines' channel registration
   * does not depend on a live connection.
   */
  @Override
  public boolean observeChannel(String channel, Consumer<byte[]> observer) {
    // Replaced, not appended: one engine owns a channel at a time.
    OBSERVERS.put(channel, new CopyOnWriteArrayList<>(List.of(observer)));
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
   * One clientbound custom payload, straight off the netty thread.
   *
   * <p>Both consumers — the mod's own inbox and a watched channel — expect the game thread, so the
   * payload is re-dispatched onto the client thread before it is handed over.
   */
  public static void onClientPayload(
      net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket packet) {
    if (packet == null) {
      return;
    }
    String channel = packet.getChannel();
    byte[] payload = LegacyPayloads.bytesOf(packet.getPayload());
    if (channel == null || payload == null) {
      return;
    }
    net.minecraft.client.MinecraftClient client =
        net.minecraft.client.MinecraftClient.getInstance();
    if (client == null) {
      return;
    }
    client.execute(
        () -> {
          dispatch(channel, payload);
          return null;
        });
  }

  /**
   * One serverbound custom payload, straight off the netty thread: re-dispatched onto the server
   * thread, where both the inbox and the observers live.
   */
  public static void onServerPayload(
      net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket packet) {
    if (packet == null) {
      return;
    }
    String channel = packet.getChannel();
    byte[] payload = LegacyPayloads.bytesOf(packet.getPayload());
    if (channel == null || payload == null) {
      return;
    }
    net.minecraft.server.MinecraftServer server = LegacyFabricEntrypoint.server();
    if (server == null) {
      return;
    }
    server.submit(() -> dispatch(channel, payload));
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
