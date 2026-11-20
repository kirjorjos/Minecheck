package net.minecheck.loader.forge;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.util.Map;
import java.util.logging.Logger;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

/**
 * Shared plumbing for the Forge low-level frame taps ({@link ForgeClientChannelTap} on a physical
 * client, {@link ForgeServerChannelTap} on a dedicated server).
 *
 * <p>Both taps are the same trick: a netty {@code channelRead} handler inserted <em>in front of
 * vanilla's own {@link NetworkManager}</em> sees every already-decoded packet while still passing
 * it on untouched. Anchoring on the handler instance rather than a pipeline name keeps this working
 * across mappings and loader versions, where the handler's name is not stable.
 */
final class ForgeChannelTaps {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Handler name used in the pipeline; also how a repeat install is detected. */
  static final String HANDLER_NAME = "minecheck_tap";

  private ForgeChannelTaps() {}

  /**
   * The channel a custom-payload message travels on, or {@code null} when the message is something
   * else. On this line a mod payload may already have been wrapped into an {@link FMLProxyPacket}
   * by FML's own handler, so both spellings are recognised.
   */
  static String channelOf(Object msg) {
    if (msg instanceof FMLProxyPacket) {
      return ((FMLProxyPacket) msg).channel();
    }
    if (msg instanceof S3FPacketCustomPayload) {
      return ((S3FPacketCustomPayload) msg).func_149169_c();
    }
    if (msg instanceof C17PacketCustomPayload) {
      return ((C17PacketCustomPayload) msg).getChannel();
    }
    return null;
  }

  /** A non-destructive copy of a custom payload's bytes, or {@code null} when it is not one. */
  static byte[] payloadOf(Object msg) {
    if (msg instanceof S3FPacketCustomPayload) {
      return ((S3FPacketCustomPayload) msg).func_149168_d();
    }
    if (msg instanceof C17PacketCustomPayload) {
      return ((C17PacketCustomPayload) msg).getData();
    }
    if (!(msg instanceof FMLProxyPacket)) {
      return null;
    }
    ByteBuf data = ((FMLProxyPacket) msg).payload();
    if (data == null) {
      return null;
    }
    byte[] payload = new byte[data.readableBytes()];
    data.getBytes(data.readerIndex(), payload);
    return payload;
  }

  /**
   * Inserts {@code tap} in front of vanilla's {@link NetworkManager} handler on {@code manager}'s
   * channel — or at the end of the pipeline when that handler is not found (still better than not
   * observing at all). Idempotent per connection. Never throws: a tap is an observer, so failing to
   * install it must not disturb the connection.
   */
  static boolean install(NetworkManager manager, ChannelInboundHandlerAdapter tap, String label) {
    if (manager == null) {
      return false;
    }
    Channel channel = manager.channel();
    if (channel == null) {
      return false;
    }
    ChannelPipeline pipeline = channel.pipeline();
    if (pipeline.get(HANDLER_NAME) != null) {
      return true; // already tapping this connection
    }
    String anchor = null;
    for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
      ChannelHandler handler = entry.getValue();
      // Matched by name as well as by type: the handler in the pipeline is a remapped vanilla
      // class, and a type check alone silently degrades to the blind fallback below when it is not
      // the exact class this module compiles against.
      if (handler instanceof NetworkManager
          || handler.getClass().getName().equals("net.minecraft.network.NetworkManager")) {
        anchor = entry.getKey();
        break;
      }
    }
    try {
      if (anchor != null) {
        pipeline.addBefore(anchor, HANDLER_NAME, tap);
      } else if (pipeline.get("packet_handler") != null) {
        // Vanilla's own name for the NetworkManager stage, used when the type check found nothing.
        anchor = "packet_handler";
        pipeline.addBefore(anchor, HANDLER_NAME, tap);
      } else {
        // Last resort, and blind by construction: a simple-channel handler downstream consumes the
        // payload, so nothing reaches a handler added after it.
        pipeline.addLast(HANDLER_NAME, tap);
        LOG.warning(
            String.format(
                "Minecheck: no NetworkManager handler to anchor the %s channel tap in front of —"
                    + " the tap is blind (pipeline %s)",
                label, pipeline.names()));
        return true;
      }
      LOG.info(
          String.format(
              "Minecheck: installed the %s channel tap before '%s' (%s, pipeline %s)",
              label, anchor, channel.remoteAddress(), pipeline.names()));
      return true;
    } catch (RuntimeException e) {
      LOG.warning(String.format("Minecheck: could not install the %s channel tap: %s", label, e));
      return false;
    }
  }
}
