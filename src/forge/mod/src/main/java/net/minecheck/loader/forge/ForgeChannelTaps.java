package net.minecheck.loader.forge;

import cpw.mods.fml.common.network.IPacketHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.Player;
import cpw.mods.fml.relauncher.Side;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet250CustomPayload;

/**
 * Observing another mod's channel on 1.4.7.
 *
 * <p>{@link NetworkRegistry} keeps its handlers in a multimap, so registering one more handler for
 * a channel does not take the channel away from the mod that owns it: every handler registered for
 * the channel is handed the payload. Watching a channel is therefore a registration, not a pipeline
 * tap, and it works on a client and a dedicated server the same way.
 *
 * <p>The handler has to be registered <em>for this side</em>. The two-argument registration only
 * fills the universal map, and this line's dispatcher reads the side's own map — which is the one
 * Minecheck's transport registers in, so an observer registered the other way was never called and
 * the tap looked blind on both sides while the transport worked.
 *
 * <p>The handler is registered once per channel and dispatches to the current observer list, so a
 * second engine instance replacing the first cannot accumulate duplicate deliveries.
 */
final class ForgeChannelTaps {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Watched channels by id, each with its observer; shared with the network platform. */
  private static final Map<String, List<Consumer<byte[]>>> OBSERVERS = new ConcurrentHashMap<>();

  /** Channels already registered, so repeated watches do not add a second handler. */
  private static final Set<String> REGISTERED =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  private static final IPacketHandler HANDLER =
      new IPacketHandler() {
        @Override
        public void onPacketData(
            INetworkManager manager, Packet250CustomPayload packet, Player player) {
          if (packet == null || packet.channel == null || packet.data == null) {
            return;
          }
          List<Consumer<byte[]>> observers = OBSERVERS.get(packet.channel);
          if (observers == null || observers.isEmpty()) {
            return;
          }
          byte[] payload = packet.data.clone();
          for (Consumer<byte[]> observer : observers) {
            try {
              observer.accept(payload);
            } catch (RuntimeException e) {
              // An observer must never disturb the connection it observes.
              LOG.warning(String.format("Minecheck: channel tap error (ignored): %s", e));
            }
          }
        }
      };

  private ForgeChannelTaps() {}

  /** Registers {@code observer} for {@code channel}, adding the handler on first watch. */
  static boolean observe(String channel, Consumer<byte[]> observer, boolean dedicated) {
    // Replaced, not appended: one engine owns a channel at a time.
    OBSERVERS.put(channel, new CopyOnWriteArrayList<>(Collections.singletonList(observer)));
    if (REGISTERED.add(channel)) {
      NetworkRegistry.instance()
          .registerChannel(HANDLER, channel, dedicated ? Side.SERVER : Side.CLIENT);
      LOG.info(String.format("Minecheck: observing channel %s", channel));
    }
    return true;
  }
}
