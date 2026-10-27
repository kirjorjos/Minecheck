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
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Dedicated-server <em>low-level frame tap</em>: the Forge-only way to observe another mod's
 * channel on the server (design note §5.7).
 *
 * <p>The mirror of {@link ForgeClientChannelTap}. A second handler cannot be given a channel an
 * existing one already receives ({@code SimpleChannel} and Fabric's addon are both single-receiver
 * per direction), so instead one netty {@code channelRead} handler is installed per player
 * connection, in front of vanilla's own {@link Connection}, and forwards a <em>copy</em> of each
 * serverbound payload's bytes to the script's observers.
 *
 * <p>Direction: serverbound only — the script is watching what <em>clients</em> send, exactly as
 * the client tap watches what the server sends. Connections are per player, so the tap is installed
 * on every player connection that exists when a channel is first watched and on every connection
 * that appears afterwards ({@code ForgeEntrypoint} installs it from {@code PlayerLoggedInEvent}).
 *
 * <p>Deliberately defensive, like the client tap: every failure is swallowed, the original message
 * is always forwarded untouched, and nothing is retained.
 *
 * <p>{@code @Sharable} because the tap holds no per-connection state — its only state is the static
 * observer map — and one instance is installed on <em>every</em> player connection. Netty rejects
 * adding the same handler instance to a second pipeline otherwise ({@code is not a @Sharable
 * handler, so can't be added or removed multiple times}), which is why the second and later players
 * used to go untapped.
 */
@Sharable
final class ForgeServerChannelTap extends ChannelInboundHandlerAdapter {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Watched channels by id, each with its observers; shared with the network platform. */
  private static final Map<String, List<Consumer<byte[]>>> OBSERVERS = new ConcurrentHashMap<>();

  private static final ForgeServerChannelTap INSTANCE = new ForgeServerChannelTap();

  private ForgeServerChannelTap() {}

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
    return ForgeChannelTaps.install(connection, INSTANCE, "server");
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
    ResourceLocation id = packet.getIdentifier();
    FriendlyByteBuf data = packet.getData();
    List<Consumer<byte[]>> observers = OBSERVERS.get(id.toString());
    if (observers == null || observers.isEmpty() || data == null) {
      return;
    }
    // Non-destructive read: the packet still has to reach its real handler.
    byte[] payload = new byte[data.readableBytes()];
    data.getBytes(data.readerIndex(), payload);
    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) {
      return;
    }
    for (Consumer<byte[]> observer : observers) {
      // Netty event loop -> server thread, where the script engine ticks.
      server.execute(() -> observer.accept(payload));
    }
  }
}
