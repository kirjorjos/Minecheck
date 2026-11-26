package net.minecheck.loader.neoforge;

import io.netty.buffer.Unpooled;
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
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Client-side <em>low-level frame tap</em>: the NeoForge-only way to observe another mod's channel
 * (design note §5.7).
 *
 * <p>No loader's play-channel API can hand a second handler the same channel: Fabric's addon and
 * NeoForge's payload registry are both single-handler per direction, and neither can be shared or
 * chained around. The only way to see a channel someone else already owns is therefore to look at
 * the play connection's decoded custom-payload frames, below the channel. This installs one netty
 * {@code channelRead} handler into that pipeline, in front of vanilla's own {@link Connection}, and
 * forwards a <em>copy</em> of the payload's bytes to the script's observers.
 *
 * <p>1.21 play payloads are typed, so a payload object has no byte accessor. NeoForge keeps the
 * codec it registered for each id, which lets the tap re-serialise either Minecheck's own payload
 * or another mod's from the object the packet already carries.
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
final class NeoForgeClientChannelTap extends ChannelInboundHandlerAdapter {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Watched channels by id, each with its observers; shared with the network platform. */
  private static final Map<String, List<Consumer<byte[]>>> OBSERVERS = new ConcurrentHashMap<>();

  private static final NeoForgeClientChannelTap INSTANCE = new NeoForgeClientChannelTap();

  private NeoForgeClientChannelTap() {}

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
    return NeoForgeChannelTaps.install(connection(), INSTANCE, "client");
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
    CustomPacketPayload body = packet.payload();
    if (body == null) {
      return;
    }
    ResourceLocation id = body.type().id();
    List<Consumer<byte[]>> observers = OBSERVERS.get(id.toString());
    if (observers == null || observers.isEmpty()) {
      return;
    }
    byte[] payload = bytes(body, id);
    if (payload == null) {
      return;
    }
    Minecraft client = Minecraft.getInstance();
    for (Consumer<byte[]> observer : observers) {
      client.execute(() -> observer.accept(payload));
    }
  }

  /** Minecheck's own payload carries its bytes; anything else is re-encoded with its codec. */
  private static byte[] bytes(CustomPacketPayload body, ResourceLocation id) {
    if (body instanceof NeoForgeNetworkPlatform.MinecheckPayload own) {
      return own.payload();
    }
    Minecraft client = Minecraft.getInstance();
    if (client.level == null) {
      return null;
    }
    StreamCodec<? super RegistryFriendlyByteBuf, ? extends CustomPacketPayload> codec =
        NetworkRegistry.getCodec(id, ConnectionProtocol.PLAY, PacketFlow.CLIENTBOUND);
    if (codec == null) {
      return null;
    }
    RegistryFriendlyByteBuf buf =
        new RegistryFriendlyByteBuf(Unpooled.buffer(), client.level.registryAccess());
    try {
      @SuppressWarnings({"unchecked", "rawtypes"})
      StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> raw = (StreamCodec) codec;
      raw.encode(buf, body);
      byte[] out = new byte[buf.readableBytes()];
      buf.readBytes(out);
      return out;
    } finally {
      buf.release();
    }
  }
}
