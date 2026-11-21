package net.minecheck.loader.forge;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Forge transport over an FML {@code SimpleNetworkWrapper}.
 *
 * <p>One channel carries both directions, with one message index per direction. FML has a single
 * receiver per channel per direction, which is why {@link #observeChannel} cannot piggyback on it:
 * watching another mod's channel needs a lower-level tap — {@link ForgeClientChannelTap} on a
 * client, {@link ForgeServerChannelTap} on a dedicated server.
 *
 * <p>Messages arrive on the netty thread. This line's server has no task queue to hand work to, so
 * an inbound frame is parked and drained from the server tick by {@link #drainServerTasks()}, the
 * same marshaling the Legacy Fabric transport uses. The client does have one ({@code
 * Minecraft.addScheduledTask}) and uses it.
 */
public class ForgeNetworkPlatform implements NetworkPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");
  private static final String CHANNEL_NAME = "minecheck:main";

  /** The process's one network platform; FML's message handlers are instantiated by reflection. */
  private static volatile Consumer<MinecheckPacket> sink = packet -> {};

  /** Frames waiting for the server thread, drained at the end of each server tick. */
  private static final ConcurrentLinkedQueue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<>();

  private final boolean dedicated;
  private final boolean registered;
  private volatile SimpleNetworkWrapper channel;

  public ForgeNetworkPlatform(boolean dedicated) {
    this.dedicated = dedicated;
    boolean ok;
    try {
      SimpleNetworkWrapper net = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
      net.registerMessage(HandlerC2S.class, MinecheckC2SMessage.class, 0, Side.SERVER);
      net.registerMessage(HandlerS2C.class, MinecheckS2CMessage.class, 1, Side.CLIENT);
      this.channel = net;
      ok = true;
    } catch (RuntimeException e) {
      LOG.severe(
          String.format("Minecheck: failed to register the %s channel: %s", CHANNEL_NAME, e));
      ok = false;
    }
    this.registered = ok;
  }

  private static MinecraftServer server() {
    return FMLCommonHandler.instance().getMinecraftServerInstance();
  }

  @Override
  public boolean isAvailable() {
    if (!registered) {
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
    byte[] payload = new MinecheckPacket(name, args).encode();
    try {
      if (dedicated) {
        MinecraftServer server = server();
        if (server == null || server.getPlayerList().getCurrentPlayerCount() == 0) {
          return false;
        }
        channel.sendToAll(new MinecheckS2CMessage(payload));
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
    MinecraftServer server = server();
    if (server == null) {
      return false;
    }
    EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(playerName);
    if (player == null) {
      return false;
    }
    byte[] payload = new MinecheckPacket(name, args).encode();
    try {
      channel.sendTo(new MinecheckS2CMessage(payload), player);
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
  }

  /**
   * A frame tap is installed instead of a channel receiver, because on Forge the tap sits
   * <em>below</em> the channel: it copies each decoded custom payload's bytes as they pass, so it
   * can observe a channel that another mod (or Minecheck's own transport) already receives.
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

  /** Server-side receiver: netty thread into the server thread, which has no task queue here. */
  public static final class HandlerC2S implements IMessageHandler<MinecheckC2SMessage, IMessage> {

    @Override
    public IMessage onMessage(MinecheckC2SMessage message, MessageContext ctx) {
      final byte[] payload = message.payload();
      SERVER_TASKS.add(() -> deliver(payload));
      return null;
    }
  }

  /** Client-side receiver: netty thread into the client thread. */
  public static final class HandlerS2C implements IMessageHandler<MinecheckS2CMessage, IMessage> {

    @Override
    public IMessage onMessage(MinecheckS2CMessage message, MessageContext ctx) {
      final byte[] payload = message.payload();
      Minecraft client = Minecraft.getMinecraft();
      if (client == null) {
        return null;
      }
      client.addScheduledTask(() -> deliver(payload));
      return null;
    }
  }

  /** Client → server message: just the encoded {@link MinecheckPacket} frame. */
  public static final class MinecheckC2SMessage implements IMessage {

    private byte[] payload;

    public MinecheckC2SMessage() {}

    public MinecheckC2SMessage(byte[] payload) {
      this.payload = payload;
    }

    public byte[] payload() {
      return payload;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
      payload = new byte[buf.readableBytes()];
      buf.readBytes(payload);
    }

    @Override
    public void toBytes(ByteBuf buf) {
      buf.writeBytes(payload);
    }
  }

  /** Server → client message: just the encoded {@link MinecheckPacket} frame. */
  public static final class MinecheckS2CMessage implements IMessage {

    private byte[] payload;

    public MinecheckS2CMessage() {}

    public MinecheckS2CMessage(byte[] payload) {
      this.payload = payload;
    }

    public byte[] payload() {
      return payload;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
      payload = new byte[buf.readableBytes()];
      buf.readBytes(payload);
    }

    @Override
    public void toBytes(ByteBuf buf) {
      buf.writeBytes(payload);
    }
  }
}
