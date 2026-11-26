package net.minecheck.platform.yarn;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecraft.client.MinecraftClient;

/**
 * Client-side transport over {@code fabric-networking-api-v1}: sends to the connected server
 * (including the integrated server of a singleplayer world) and receives server → client packets.
 */
public class YarnClientNetworkPlatform implements NetworkPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  private volatile Consumer<MinecheckPacket> receiver = packet -> {};
  private final boolean registered;

  public YarnClientNetworkPlatform() {
    registered =
        ClientPlayNetworking.registerGlobalReceiver(
            MinecheckPayload.ID,
            (payload, context) -> {
              byte[] data = payload.data();
              // Netty event loop -> client thread, where the script engine ticks.
              context.client().execute(() -> deliver(data));
            });
    if (!registered) {
      LOG.severe("Minecheck: failed to register the minecheck:main client payload");
    }
  }

  @Override
  public boolean isAvailable() {
    if (!registered || MinecraftClient.getInstance().getNetworkHandler() == null) {
      return false;
    }
    try {
      return ClientPlayNetworking.canSend(MinecheckPayload.ID);
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public boolean send(String name, List<PacketValue> args) {
    if (!isAvailable()) {
      return false;
    }
    try {
      ClientPlayNetworking.send(new MinecheckPayload(new MinecheckPacket(name, args).encode()));
      return true;
    } catch (RuntimeException e) {
      LOG.warning(
          String.format("Minecheck: failed to send packet %s: %s", name, String.valueOf(e)));
      return false;
    }
  }

  @Override
  public void setReceiver(Consumer<MinecheckPacket> receiver) {
    this.receiver = receiver;
  }

  /**
   * Reports that another mod's channel cannot be observed on this line.
   *
   * <p>1.20.5 replaced raw channel buffers with typed payloads: every receiver is registered for a
   * payload {@code Id} with a codec both sides declared before the play phase. Fabric's networking
   * API therefore has no receiver to install for an arbitrary channel name — the payload never
   * reaches the game unless its own mod registered that id. Watching foreign channels remains
   * possible on Forge and NeoForge, whose frame taps read the decoded packet frame; pretending to
   * watch here would leave the script waiting out its timeout for payloads that cannot arrive.
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
