package net.minecheck.loader.forge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.IPacketHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.common.network.Player;
import cpw.mods.fml.relauncher.Side;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatformBase;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.server.MinecraftServer;

/**
 * Forge transport on a 1.4.7 custom-payload channel.
 *
 * <p>This line has no SimpleNetworkWrapper and no netty: a mod channel is a {@link
 * Packet250CustomPayload} registered with {@link NetworkRegistry}, and every registered handler for
 * a channel receives every payload on it. That last part is what makes {@link #observeChannel}
 * possible without a pipeline tap: an observer is just another handler on the channel, registered
 * beside whichever mod already owns it.
 *
 * <p>1.4.7 processes packets on the game thread itself — the client's read loop runs inside the
 * client tick and the server's inside the server tick — so delivery needs no marshaling. The queue
 * below is only a safety net for a handler invoked from a network thread during login.
 */
public class ForgeNetworkPlatform extends NetworkPlatformBase {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** 1.4.7 caps a custom-payload channel name at 20 bytes; this one is 14. */
  static final String CHANNEL_NAME = "minecheck:main";

  /** The process's one network platform; the registered handler reaches it through this. */
  private static volatile Consumer<MinecheckPacket> sink = packet -> {};

  /** Frames waiting for the game thread, drained at the end of each server tick. */
  private static final ConcurrentLinkedQueue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<>();

  private final boolean dedicated;
  private volatile boolean registered;

  public ForgeNetworkPlatform(boolean dedicated) {
    this.dedicated = dedicated;
  }

  /**
   * Registers the channel.
   *
   * <p>Called when the receiver is bound, because a channel nothing registered cannot receive: the
   * first script to touch the channel on a client is usually a {@code waitForPacket}, not a send,
   * and registering only on send meant the client was not listening when the dedicated server's cue
   * went out. Both halves then waited on each other until their timeouts.
   *
   * <p>The registration used to be deferred because FML exchanges REGISTER packets as a world
   * loads, and 1.4.7's client-side handler casts the client player to FML's {@code Player}
   * interface, which only the server player implemented — the load died with a ClassCastException.
   * That interface is now added to both player classes (see {@code addInterface} in the build), so
   * the exchange is safe.
   */
  private synchronized boolean register() {
    if (registered) {
      return true;
    }
    try {
      NetworkRegistry registry = NetworkRegistry.instance();
      registry.registerChannel(new Receiver(), CHANNEL_NAME, dedicated ? Side.SERVER : Side.CLIENT);
      registered = true;
    } catch (RuntimeException e) {
      LOG.severe(
          String.format("Minecheck: failed to register the %s channel: %s", CHANNEL_NAME, e));
    }
    return registered;
  }

  private static MinecraftServer server() {
    return FMLCommonHandler.instance().getMinecraftServerInstance();
  }

  @Override
  public boolean isAvailable() {
    if (!register()) {
      return false;
    }
    if (dedicated) {
      return server() != null;
    }
    return Minecraft.getMinecraft() != null;
  }

  @Override
  public boolean send(String name, List<PacketValue> args) {
    if (!isAvailable()) {
      return false;
    }
    byte[] payload = envelope(new MinecheckPacket(name, args).encode());
    try {
      Packet packet = PacketDispatcher.getPacket(CHANNEL_NAME, payload);
      if (dedicated) {
        MinecraftServer current = server();
        if (current == null || current.getConfigurationManager().getCurrentPlayerCount() == 0) {
          return false;
        }
        current.getConfigurationManager().sendPacketToAllPlayers(packet);
      } else {
        PacketDispatcher.sendPacketToServer(packet);
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
    if (!register() || !dedicated) {
      return false;
    }
    MinecraftServer current = server();
    if (current == null) {
      return false;
    }
    EntityPlayerMP player = current.getConfigurationManager().getPlayerForUsername(playerName);
    if (player == null) {
      return false;
    }
    byte[] payload = envelope(new MinecheckPacket(name, args).encode());
    try {
      player.playerNetServerHandler.sendPacketToPlayer(
          PacketDispatcher.getPacket(CHANNEL_NAME, payload));
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
    sink = receiver;
    // Bound at startup by the composition root, on both sides: this is the earliest point at which
    // packets are meant to arrive, so it is where the channel has to be live.
    register();
  }

  /**
   * Adds a handler beside whichever mod already owns {@code channel}. 1.4.7 keeps handlers in a
   * multimap and hands every payload to all of them, so an observer never steals the channel.
   */
  @Override
  public boolean observeChannel(String channel, Consumer<byte[]> observer) {
    try {
      return ForgeChannelTaps.observe(channel, observer, dedicated);
    } catch (RuntimeException e) {
      LOG.warning(
          String.format("Minecheck: failed to observe channel %s: %s", channel, String.valueOf(e)));
      return false;
    }
  }

  /**
   * Puts the frame behind the leading message index a Forge payload carries.
   *
   * <p>SimpleChannel writes a VarInt message index before the message, so anything watching a Forge
   * channel — a frame tap, another mod's handler — reads that index first and then the frame. This
   * line has no SimpleChannel: a custom payload is whatever the sender put in it, which made the
   * 1.4.7 wire one byte shorter than the same frame on 1.7.10 and a tap decoding it came out one
   * byte off (it read the frame's first discriminator byte as the index). Writing the index this
   * line never uses — zero — keeps the frame identical to see on both lines; it costs one byte per
   * packet and {@link Receiver} removes it again.
   */
  private static byte[] envelope(byte[] frame) {
    byte[] payload = new byte[frame.length + 1];
    System.arraycopy(frame, 0, payload, 1, frame.length);
    return payload;
  }

  private static void deliver(byte[] payload) {
    try {
      sink.accept(MinecheckPacket.decode(payload));
    } catch (WireFormatException e) {
      LOG.warning(
          String.format(
              "Minecheck: dropped a malformed packet: %s", String.valueOf(e.getMessage())));
    }
  }

  /** Parks work for the server thread, which this line has no task queue to hand it to. */
  static void queueForServerThread(Runnable task) {
    SERVER_TASKS.add(task);
  }

  /** Runs the work that arrived since the last tick, on the server thread. */
  static void drainServerTasks() {
    Runnable task;
    while ((task = SERVER_TASKS.poll()) != null) {
      try {
        task.run();
      } catch (RuntimeException e) {
        LOG.warning("Minecheck: a delivered packet failed: " + e);
      }
    }
  }

  /** The receiving end of Minecheck's own channel. */
  public static final class Receiver implements IPacketHandler {

    @Override
    public void onPacketData(
        INetworkManager manager, Packet250CustomPayload packet, Player player) {
      if (packet == null || packet.data == null) {
        return;
      }
      final byte[] payload = packet.data;
      // The leading message index belongs to the envelope, not to the frame (see envelope()).
      deliver(payload.length == 0 ? payload : Arrays.copyOfRange(payload, 1, payload.length));
    }
  }
}
