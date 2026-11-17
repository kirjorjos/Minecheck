package net.minecheck.loader.forge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.util.Map;
import java.util.logging.Logger;
import net.minecraft.network.Connection;

/**
 * Shared plumbing for the Forge low-level frame taps ({@link ForgeClientChannelTap} on a physical
 * client, {@link ForgeServerChannelTap} on a dedicated server).
 *
 * <p>Both taps are the same trick: a netty {@code channelRead} handler inserted <em>in front of
 * vanilla's own {@link Connection}</em> sees every already-decoded packet while still passing it on
 * untouched. Anchoring on the handler instance rather than a pipeline name keeps this working
 * across mappings and loader versions, where the handler's name is not stable.
 */
final class ForgeChannelTaps {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Handler name used in the pipeline; also how a repeat install is detected. */
  static final String HANDLER_NAME = "minecheck_tap";

  private ForgeChannelTaps() {}

  /**
   * Inserts {@code tap} in front of vanilla's {@link Connection} handler on {@code connection}'s
   * channel — or at the end of the pipeline when that handler is not found (still better than not
   * observing at all). Idempotent per connection. Never throws: a tap is an observer, so failing to
   * install it must not disturb the connection.
   */
  static boolean install(Connection connection, ChannelInboundHandlerAdapter tap, String label) {
    if (connection == null) {
      return false;
    }
    Channel channel = connection.channel();
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
      if (handler instanceof Connection
          || handler.getClass().getName().equals("net.minecraft.network.Connection")) {
        anchor = entry.getKey();
        break;
      }
    }
    try {
      if (anchor != null) {
        pipeline.addBefore(anchor, HANDLER_NAME, tap);
      } else if (pipeline.get("packet_handler") != null) {
        // Vanilla's own name for the Connection stage, used when the type check found nothing.
        anchor = "packet_handler";
        pipeline.addBefore(anchor, HANDLER_NAME, tap);
      } else {
        // Last resort, and blind by construction: a simple-channel handler downstream consumes the
        // payload, so nothing reaches a handler added after it.
        pipeline.addLast(HANDLER_NAME, tap);
        LOG.warning(
            String.format(
                "Minecheck: no Connection handler to anchor the %s channel tap in front of —"
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
