package net.minecheck.platform.yarn;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Dedicated-server transport over {@code fabric-networking-api-v1}: receives client → server
 * packets and sends server → client ones.
 *
 * <p>Kept free of client classes on purpose: this class is loaded on a dedicated server, where
 * {@code net.minecraft.client.*} is not on the classpath. The client counterpart is {@link
 * YarnClientNetworkPlatform}.
 */
public class YarnServerNetworkPlatform implements NetworkPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");
  private static final Identifier CHANNEL = new Identifier("minecheck", "main");

  private volatile MinecraftServer server;
  private volatile Consumer<MinecheckPacket> receiver = packet -> {};
  private final boolean registered;

  public YarnServerNetworkPlatform() {
    registered =
        ServerPlayNetworking.registerGlobalReceiver(
            CHANNEL,
            (server, player, handler, buf, sender) -> {
              byte[] payload = read(buf);
              // Netty event loop -> server thread, where the script engine ticks.
              server.execute(() -> deliver(payload));
            });
    if (!registered) {
      LOG.severe("Minecheck: failed to register the minecheck:main server channel");
    }
  }

  /** Bound from the server tick event, like the server platform's own server reference. */
  public void setServer(MinecraftServer server) {
    this.server = server;
  }

  @Override
  public boolean isAvailable() {
    return registered && server != null;
  }

  @Override
  public boolean send(String name, List<PacketValue> args) {
    MinecraftServer s = server;
    if (!registered || s == null) {
      return false;
    }
    List<ServerPlayerEntity> players = s.getPlayerManager().getPlayerList();
    if (players.isEmpty()) {
      return false;
    }
    byte[] payload = new MinecheckPacket(name, args).encode();
    try {
      for (ServerPlayerEntity player : players) {
        ServerPlayNetworking.send(player, CHANNEL, buffer(payload));
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
    MinecraftServer s = server;
    if (!registered || s == null) {
      return false;
    }
    byte[] payload = new MinecheckPacket(name, args).encode();
    for (ServerPlayerEntity player : s.getPlayerManager().getPlayerList()) {
      if (!player.getEntityName().equals(playerName)) {
        continue;
      }
      try {
        ServerPlayNetworking.send(player, CHANNEL, buffer(payload));
        return true;
      } catch (RuntimeException e) {
        LOG.warning(
            String.format(
                "Minecheck: failed to send packet %s to %s: %s",
                name, playerName, String.valueOf(e)));
        return false;
      }
    }
    return false;
  }

  @Override
  public void setReceiver(Consumer<MinecheckPacket> receiver) {
    this.receiver = receiver;
  }

  /**
   * Observes another mod's channel by registering a play-channel receiver for it.
   *
   * <p>Same one-handler-per-channel rule as the client ({@code putIfAbsent} on each player's
   * connection addon), so each online player is asked for the channel individually: if any of them
   * already receives there, the channel is not observable and this reports {@code false} rather
   * than waiting out a script's timeout. The global registration afterwards covers players who log
   * in later.
   *
   * <p>The handler is registered on the players <em>before</em> the loop knows whether all of them
   * accepted it, so it forwards nothing until {@code active} is set: a partly-claimed channel must
   * not feed payloads into an inbox whose script was told "no".
   */
  @Override
  public boolean observeChannel(String channel, Consumer<byte[]> observer) {
    Identifier id;
    try {
      id = new Identifier(channel);
    } catch (RuntimeException e) {
      return false;
    }
    AtomicBoolean active = new AtomicBoolean(false);
    ServerPlayNetworking.PlayChannelHandler handler =
        (srv, player, handler0, buf, sender) -> {
          if (!active.get()) {
            return;
          }
          byte[] payload = read(buf);
          srv.execute(() -> observer.accept(payload));
        };
    MinecraftServer s = server;
    if (s == null) {
      // Nothing to prove against yet; the global table still refuses an already-registered channel.
      return ServerPlayNetworking.registerGlobalReceiver(id, handler);
    }
    for (ServerPlayerEntity player : s.getPlayerManager().getPlayerList()) {
      if (!ServerPlayNetworking.registerReceiver(player.networkHandler, id, handler)) {
        return false;
      }
    }
    ServerPlayNetworking.registerGlobalReceiver(id, handler);
    active.set(true);
    return true;
  }

  private void deliver(byte[] payload) {
    try {
      receiver.accept(MinecheckPacket.decode(payload));
    } catch (WireFormatException e) {
      LOG.warning(
          String.format(
              "Minecheck: dropped a malformed packet: %s", String.valueOf(e.getMessage())));
    }
  }

  private static byte[] read(PacketByteBuf buf) {
    byte[] bytes = new byte[buf.readableBytes()];
    buf.readBytes(bytes);
    return bytes;
  }

  private static PacketByteBuf buffer(byte[] payload) {
    PacketByteBuf buf = PacketByteBufs.create();
    buf.writeBytes(payload);
    return buf;
  }
}
