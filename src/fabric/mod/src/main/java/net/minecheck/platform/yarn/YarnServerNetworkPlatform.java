package net.minecheck.platform.yarn;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

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

  private volatile MinecraftServer server;
  private volatile Consumer<MinecheckPacket> receiver = packet -> {};
  private final boolean registered;

  public YarnServerNetworkPlatform() {
    registered =
        ServerPlayNetworking.registerGlobalReceiver(
            MinecheckPayload.ID,
            (payload, context) -> {
              byte[] data = payload.data();
              // Netty event loop -> server thread, where the script engine ticks.
              context.server().execute(() -> deliver(data));
            });
    if (!registered) {
      LOG.severe("Minecheck: failed to register the minecheck:main server payload");
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
    MinecheckPayload payload = new MinecheckPayload(new MinecheckPacket(name, args).encode());
    try {
      for (ServerPlayerEntity player : players) {
        ServerPlayNetworking.send(player, payload);
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
    MinecheckPayload payload = new MinecheckPayload(new MinecheckPacket(name, args).encode());
    for (ServerPlayerEntity player : s.getPlayerManager().getPlayerList()) {
      if (!player.getGameProfile().getName().equals(playerName)) {
        continue;
      }
      try {
        ServerPlayNetworking.send(player, payload);
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
   * Reports that another mod's channel cannot be observed on this line.
   *
   * <p>1.20.5 replaced raw channel buffers with typed payloads, so a play receiver only exists for
   * a payload id whose codec its owning mod registered. Fabric's networking API offers no generic
   * channel receiver to install instead. Watching foreign channels stays available on Forge and
   * NeoForge through their frame taps.
   */
  @Override
  public boolean observeChannel(String channel, Consumer<byte[]> observer) {
    LOG.warning(
        String.format(
            "Minecheck: cannot watch %s — 1.21 play payloads are typed, so a channel needs a codec"
                + " registered by the mod that owns it",
            channel));
    return false;
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
}
