package net.minecheck.loader.forge;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * Dedicated-server <em>low-level frame tap</em>: the Forge-only way to observe another mod's
 * channel on the server.
 *
 * <p>The mirror of {@link ForgeClientChannelTap}. A second handler cannot be given a channel an
 * existing one already receives, so instead one netty {@code channelRead} handler is installed per
 * player connection, in front of vanilla's own {@link NetworkManager}, and forwards a <em>copy</em>
 * of each serverbound payload's bytes to the script's observers.
 *
 * <p>Direction: serverbound only — the script is watching what <em>clients</em> send. Connections
 * are per player, so the tap is installed on every player connection that exists when a channel is
 * first watched and on every connection that appears afterwards ({@code ForgeEntrypoint} installs
 * it from the login event).
 *
 * <p>{@code @Sharable} because the tap holds no per-connection state — its only state is the static
 * observer map — and one instance is installed on <em>every</em> player connection. Netty rejects
 * adding the same handler instance to a second pipeline otherwise, which is why the second and
 * later players used to go untapped.
 */
@Sharable
final class ForgeServerChannelTap extends ChannelInboundHandlerAdapter {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Watched channels by id, each with its observers; shared with the network platform. */
  private static final Map<String, List<Consumer<byte[]>>> OBSERVERS = new ConcurrentHashMap<>();

  private static final ForgeServerChannelTap INSTANCE = new ForgeServerChannelTap();

  private ForgeServerChannelTap() {}

  private static MinecraftServer server() {
    return FMLCommonHandler.instance().getMinecraftServerInstance();
  }

  /**
   * Registers {@code observer} for {@code channel} and installs the tap on every player connection
   * that is already up. Returns false when there is no running server yet (the caller reports the
   * same false to the script); connections that appear later are picked up at login.
   */
  static boolean observe(String channel, Consumer<byte[]> observer) {
    // Replaced, not appended: one engine owns a channel at a time.
    OBSERVERS.put(channel, new CopyOnWriteArrayList<>(Collections.singletonList(observer)));
    MinecraftServer server = server();
    if (server == null) {
      return false;
    }
    // Every loaded world, since a player is only in one dimension's entity list: the tap has to
    // follow them wherever they are.
    for (net.minecraft.world.WorldServer world : server.worlds) {
      if (world == null) {
        continue;
      }
      for (net.minecraft.entity.player.EntityPlayer candidate : world.playerEntities) {
        install((EntityPlayerMP) candidate);
      }
    }
    return true;
  }

  /** Installs the tap on a player's connection, if any channel is actually being watched. */
  static void installFor(EntityPlayerMP player) {
    if (OBSERVERS.isEmpty()) {
      return; // nothing watched: do not pay for (or disturb) a handler on every connection
    }
    install(player);
  }

  private static boolean install(EntityPlayerMP player) {
    if (player == null) {
      return false;
    }
    NetworkManager manager =
        player.connection == null ? null : player.connection.getNetworkManager();
    return ForgeChannelTaps.install(manager, INSTANCE, "server");
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
    String id = ForgeChannelTaps.channelOf(msg);
    if (id == null) {
      return;
    }
    List<Consumer<byte[]>> observers = OBSERVERS.get(id);
    byte[] payload = ForgeChannelTaps.payloadOf(msg);
    if (observers == null || observers.isEmpty() || payload == null) {
      return;
    }
    for (Consumer<byte[]> observer : observers) {
      // Netty event loop -> server thread, through the tick queue this line's transport drains.
      ForgeNetworkPlatform.queueForServerThread(() -> observer.accept(payload));
    }
  }
}
