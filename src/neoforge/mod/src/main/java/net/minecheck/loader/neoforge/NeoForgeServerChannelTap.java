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
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Dedicated-server <em>low-level frame tap</em>: the NeoForge-only way to observe another mod's
 * channel on the server (design note §5.7).
 *
 * <p>The mirror of {@link NeoForgeClientChannelTap}. A second handler cannot be given a channel an
 * existing one already receives, so instead one netty {@code channelRead} handler is installed per
 * player connection, in front of vanilla's own {@link Connection}, and forwards a <em>copy</em> of
 * each serverbound payload's bytes to the script's observers.
 *
 * <p>Direction: serverbound only — the script is watching what <em>clients</em> send, exactly as
 * the client tap watches what the server sends. Connections are per player, so the tap is installed
 * on every player connection that exists when a channel is first watched and on every connection
 * that appears afterwards ({@code NeoForgeEntrypoint} installs it from {@code
 * PlayerLoggedInEvent}).
 *
 * <p>Deliberately defensive, like the client tap: every failure is swallowed, the original message
 * is always forwarded untouched, and nothing is retained.
 *
 * <p>{@code @Sharable} because the tap holds no per-connection state — its only state is the static
 * observer map — and one instance is installed on <em>every</em> player connection.
 */
@Sharable
final class NeoForgeServerChannelTap extends ChannelInboundHandlerAdapter {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Watched channels by id, each with its observers; shared with the network platform. */
  private static final Map<String, List<Consumer<byte[]>>> OBSERVERS = new ConcurrentHashMap<>();

  private static final NeoForgeServerChannelTap INSTANCE = new NeoForgeServerChannelTap();

  private NeoForgeServerChannelTap() {}

  /**
   * Registers {@code observer} for {@code channel} and installs the tap on every player connection
   * that is already up. Returns false when there is no running server yet (the caller reports the
   * same false to the script); connections that appear later are picked up at login.
   */
  static boolean observe(String channel, Consumer<byte[]> observer) {
    OBSERVERS.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>()).add(observer);
    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) {
      return false;
    }
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      install(player);
    }
    return true;
  }

  /** Installs the tap on a player's connection, if any channel is actually being watched. */
  static void installFor(ServerPlayer player) {
    if (OBSERVERS.isEmpty()) {
      return; // nothing watched: do not pay for (or disturb) a handler on every connection
    }
    install(player);
  }

  private static boolean install(ServerPlayer player) {
    if (player == null) {
      return false;
    }
    Connection connection = player.connection == null ? null : player.connection.getConnection();
    return NeoForgeChannelTaps.install(connection, INSTANCE, "server");
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    try {
      capture(msg);
    } catch (Throwable t) {
      // A tap must never disturb the connection it observes.
      LOG.warning(String.format("Minecheck: server channel tap error (ignored): %s", t));
    }
    ctx.fireChannelRead(msg);
  }

  private static void capture(Object msg) {
    if (!(msg instanceof ServerboundCustomPayloadPacket packet)) {
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
    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) {
      return;
    }
    byte[] payload = bytes(body, id, server);
    if (payload == null) {
      return;
    }
    for (Consumer<byte[]> observer : observers) {
      // Netty event loop -> server thread, where the script engine ticks.
      server.execute(() -> observer.accept(payload));
    }
  }

  /** Minecheck's own payload carries its bytes; anything else is re-encoded with its codec. */
  private static byte[] bytes(
      CustomPacketPayload body, ResourceLocation id, MinecraftServer server) {
    if (body instanceof NeoForgeNetworkPlatform.MinecheckPayload own) {
      return own.payload();
    }
    StreamCodec<? super RegistryFriendlyByteBuf, ? extends CustomPacketPayload> codec =
        NetworkRegistry.getCodec(id, ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND);
    if (codec == null) {
      return null;
    }
    RegistryFriendlyByteBuf buf =
        new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
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
