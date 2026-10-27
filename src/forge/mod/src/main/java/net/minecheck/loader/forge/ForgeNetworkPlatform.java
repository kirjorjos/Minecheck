package net.minecheck.loader.forge;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Forge transport over an FML {@code SimpleChannel}.
 *
 * <p>One channel carries both directions, with one message index per direction. Unlike Fabric's
 * play-channel receivers, Forge's {@code SimpleChannel} has a single receiver per channel per
 * direction, which is why {@link #observeChannel} cannot piggyback on it: watching another mod's
 * channel needs a lower-level tap (roadmap 4b) — {@link ForgeClientChannelTap} on a client, {@link
 * ForgeServerChannelTap} on a dedicated server.
 *
 * <p>Script packets arrive on the network thread and are re-dispatched with {@code enqueueWork} to
 * the game thread, where the script engine ticks.
 */
public class ForgeNetworkPlatform implements NetworkPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");
  private static final String VERSION = "1";
  private static final ResourceLocation CHANNEL_ID = new ResourceLocation("minecheck", "main");

  private final boolean dedicated;
  private final boolean registered;
  private volatile Consumer<MinecheckPacket> receiver = packet -> {};

  public ForgeNetworkPlatform(boolean dedicated) {
    this.dedicated = dedicated;
    boolean ok;
    try {
      SimpleChannel channel =
          NetworkRegistry.newSimpleChannel(
              CHANNEL_ID,
              () -> VERSION,
              // Tolerate a peer without Minecheck: the channel is for testing, it must not make a
              // vanilla client unable to join this server.
              NetworkRegistry.acceptMissingOr(VERSION),
              NetworkRegistry.acceptMissingOr(VERSION));
      channel.registerMessage(
          0,
          MinecheckC2SMessage.class,
          MinecheckC2SMessage::encode,
          MinecheckC2SMessage::decode,
          this::onC2S,
          Optional.of(NetworkDirection.PLAY_TO_SERVER));
      channel.registerMessage(
          1,
          MinecheckS2CMessage.class,
          MinecheckS2CMessage::encode,
          MinecheckS2CMessage::decode,
          this::onS2C,
          Optional.of(NetworkDirection.PLAY_TO_CLIENT));
      this.channel = channel;
      ok = true;
    } catch (RuntimeException e) {
      LOG.severe(String.format("Minecheck: failed to register the minecheck:main channel: %s", e));
      ok = false;
    }
    this.registered = ok;
  }

  private volatile SimpleChannel channel;

  @Override
  public boolean isAvailable() {
    if (!registered) {
      return false;
    }
    return !dedicated || ServerLifecycleHooks.getCurrentServer() != null;
  }

  @Override
  public boolean send(String name, List<PacketValue> args) {
    if (!isAvailable()) {
      return false;
    }
    byte[] payload = new MinecheckPacket(name, args).encode();
    try {
      if (dedicated) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getPlayerList().getPlayerCount() == 0) {
          return false;
        }
        channel.send(PacketDistributor.ALL.noArg(), new MinecheckS2CMessage(payload));
      } else {
        channel.sendToServer(new MinecheckC2SMessage(payload));
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
    if (!registered || !dedicated) {
      return false;
    }
    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) {
      return false;
    }
    ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
    if (player == null) {
      return false;
    }
    byte[] payload = new MinecheckPacket(name, args).encode();
    try {
      channel.send(PacketDistributor.PLAYER.with(() -> player), new MinecheckS2CMessage(payload));
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
    this.receiver = receiver;
  }

  /**
   * A frame tap is installed instead of a channel receiver, because on Forge the tap sits <em>
   * below</em> the channel: it copies each decoded custom payload's bytes as they pass, so it can
   * observe a channel that another mod (or Minecheck's own transport) already receives. A
   * higher-level receiver cannot do that on any loader — both Fabric's play-channel addon and
   * Forge's {@code SimpleChannel} are single-receiver per direction. The physical client taps the
   * play connection for clientbound custom payloads, a dedicated server taps every player
   * connection for serverbound ones.
   */
  @Override
  public boolean observeChannel(String channel, Consumer<byte[]> observer) {
    try {
      return dedicated
          ? ForgeServerChannelTap.observe(channel, observer)
          : ForgeClientChannelTap.observe(channel, observer);
    } catch (RuntimeException e) {
      LOG.warning(
          String.format("Minecheck: failed to observe channel %s: %s", channel, String.valueOf(e)));
      return false;
    }
  }

  private void onC2S(MinecheckC2SMessage message, Supplier<NetworkEvent.Context> context) {
    NetworkEvent.Context ctx = context.get();
    ctx.enqueueWork(() -> deliver(message.payload()));
    ctx.setPacketHandled(true);
  }

  private void onS2C(MinecheckS2CMessage message, Supplier<NetworkEvent.Context> context) {
    NetworkEvent.Context ctx = context.get();
    ctx.enqueueWork(() -> deliver(message.payload()));
    ctx.setPacketHandled(true);
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

  /** Client → server message: just the encoded {@link MinecheckPacket} frame. */
  public static final class MinecheckC2SMessage {

    private final byte[] payload;

    public MinecheckC2SMessage(byte[] payload) {
      this.payload = payload;
    }

    public byte[] payload() {
      return payload;
    }

    public static void encode(MinecheckC2SMessage message, FriendlyByteBuf buf) {
      buf.writeBytes(message.payload);
    }

    public static MinecheckC2SMessage decode(FriendlyByteBuf buf) {
      byte[] payload = new byte[buf.readableBytes()];
      buf.readBytes(payload);
      return new MinecheckC2SMessage(payload);
    }
  }

  /** Server → client message: just the encoded {@link MinecheckPacket} frame. */
  public static final class MinecheckS2CMessage {

    private final byte[] payload;

    public MinecheckS2CMessage(byte[] payload) {
      this.payload = payload;
    }

    public byte[] payload() {
      return payload;
    }

    public static void encode(MinecheckS2CMessage message, FriendlyByteBuf buf) {
      buf.writeBytes(message.payload);
    }

    public static MinecheckS2CMessage decode(FriendlyByteBuf buf) {
      byte[] payload = new byte[buf.readableBytes()];
      buf.readBytes(payload);
      return new MinecheckS2CMessage(payload);
    }
  }
}
