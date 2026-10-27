package net.minecheck.loader.forge;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side <em>low-level frame tap</em>: the Forge-only way to observe another mod's channel
 * (design note §5.7).
 *
 * <p>No loader's play-channel API can hand a second handler the same channel: Fabric's addon and
 * FML's {@code SimpleChannel} are both single-receiver per channel per connection, and neither can
 * be shared or chained around. The only way to see a channel someone else already owns is therefore
 * to look at the play connection's decoded custom-payload frames, below the channel. This installs
 * one netty {@code channelRead} handler into that pipeline, in front of vanilla's own {@link
 * Connection}, and forwards a <em>copy</em> of the payload's bytes to the script's observers.
 *
 * <p>Deliberately defensive: every failure is swallowed (logged at most), the original message is
 * always forwarded untouched, and nothing is retained, so a bug here cannot break the connection.
 * This class references client classes and is therefore only loaded on a physical client.
 *
 * <p>{@code @Sharable} because the tap is stateless (its only state is the static observer map) and
 * the same instance is installed on each new play connection; Netty refuses to add one handler
 * instance to a second pipeline otherwise.
 */
@Sharable
final class ForgeClientChannelTap extends ChannelInboundHandlerAdapter {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Watched channels by id, each with its observers; shared with the network platform. */
  private static final Map<String, List<Consumer<byte[]>>> OBSERVERS = new ConcurrentHashMap<>();

  private static final ForgeClientChannelTap INSTANCE = new ForgeClientChannelTap();

  private ForgeClientChannelTap() {}

  /**
   * Registers {@code observer} for {@code channel}, installing the tap on the current play
   * connection. Returns false when there is no play connection yet (the caller reports the same
   * false to the script).
   */
  static boolean observe(String channel, Consumer<byte[]> observer) {
    OBSERVERS.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>()).add(observer);
    return install();
  }

  private static boolean install() {
    return ForgeChannelTaps.install(connection(), INSTANCE, "client");
  }

  private static Connection connection() {
    ClientPacketListener listener = Minecraft.getInstance().getConnection();
    return listener == null ? null : listener.getConnection();
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    try {
      capture(msg);
    } catch (Throwable t) {
      // A tap must never disturb the connection it observes.
      LOG.warning(String.format("Minecheck: channel tap error (ignored): %s", t));
    }
    ctx.fireChannelRead(msg);
  }

  private static void capture(Object msg) {
    // Clientbound only: the tap is about what *another mod* sent us. Echoing our own outbound
    // frames back into the script's history would be noise, not observation.
    if (!(msg instanceof ClientboundCustomPayloadPacket packet)) {
      return;
    }
    ResourceLocation id = packet.getIdentifier();
    FriendlyByteBuf data = packet.getData();
    List<Consumer<byte[]>> observers = OBSERVERS.get(id.toString());
    if (observers == null || observers.isEmpty() || data == null) {
      return;
    }
    // Non-destructive read: the packet still has to reach its real handler.
    byte[] payload = new byte[data.readableBytes()];
    data.getBytes(data.readerIndex(), payload);
    Minecraft client = Minecraft.getInstance();
    for (Consumer<byte[]> observer : observers) {
      client.execute(() -> observer.accept(payload));
    }
  }
}
