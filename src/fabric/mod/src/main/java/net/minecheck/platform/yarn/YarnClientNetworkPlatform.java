package net.minecheck.platform.yarn;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Client-side transport over {@code fabric-networking-api-v1}: sends to the connected server
 * (including the integrated server of a singleplayer world) and receives server → client packets.
 */
public class YarnClientNetworkPlatform implements NetworkPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");
  private static final Identifier CHANNEL = new Identifier("minecheck", "main");

  private volatile Consumer<MinecheckPacket> receiver = packet -> {};
  private final boolean registered;

  public YarnClientNetworkPlatform() {
    registered =
        ClientPlayNetworking.registerGlobalReceiver(
            CHANNEL,
            (client, handler, buf, sender) -> {
              byte[] payload = read(buf);
              // Netty event loop -> client thread, where the script engine ticks.
              client.execute(() -> deliver(payload));
            });
    if (!registered) {
      LOG.severe("Minecheck: failed to register the minecheck:main client channel");
    }
  }

  @Override
  public boolean isAvailable() {
    if (!registered || MinecraftClient.getInstance().getNetworkHandler() == null) {
      return false;
    }
    try {
      return ClientPlayNetworking.canSend(CHANNEL);
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
      ClientPlayNetworking.send(CHANNEL, buffer(new MinecheckPacket(name, args).encode()));
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
   * Observes another mod's channel by registering a play-channel receiver for it.
   *
   * <p>Fabric API keeps <em>exactly one</em> handler per channel per connection ({@code
   * putIfAbsent} on the connection's addon; the global table is copied in first, so the two do not
   * stack). That makes a live connection the only place the claim can be proved: a per-connection
   * registration succeeds if and only if nothing — no mod handler, and nothing of Minecheck's own —
   * already receives there. Registering globally first would report success even when an existing
   * per-connection handler silently shadows us, and the script would then sit in {@code
   * waitForPacket} until its timeout for a channel it never actually saw.
   *
   * <p>The global registration is still installed afterwards so the observation survives a
   * reconnect: per-connection handlers are dropped with the connection, while the new addon copies
   * the global table in {@code lateInit}.
   */
  @Override
  public boolean observeChannel(String channel, Consumer<byte[]> observer) {
    Identifier id;
    try {
      id = new Identifier(channel);
    } catch (RuntimeException e) {
      return false;
    }
    ClientPlayNetworking.PlayChannelHandler handler =
        (client, handler0, buf, sender) -> {
          byte[] payload = read(buf);
          client.execute(() -> observer.accept(payload));
        };
    boolean exclusive;
    try {
      exclusive = ClientPlayNetworking.registerReceiver(id, handler);
    } catch (RuntimeException notInGame) {
      // No play connection yet, so there is nothing to prove against: the global table is all we
      // can consult, and it refuses a channel another mod has already registered there. A
      // per-connection handler installed elsewhere can still shadow this one, so say so rather
      // than let it look like a verified watch.
      boolean registered = ClientPlayNetworking.registerGlobalReceiver(id, handler);
      if (registered) {
        LOG.info(
            String.format(
                "Minecheck: watching %s before a play connection exists — it takes effect on join,"
                    + " where whether another handler owns that channel cannot be checked",
                channel));
      }
      return registered;
    }
    if (!exclusive) {
      return false;
    }
    ClientPlayNetworking.registerGlobalReceiver(id, handler);
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
