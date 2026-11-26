package net.minecheck.loader.neoforge;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.registration.IPayloadRegistrar;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * NeoForge transport over the 20.2+ payload registry.
 *
 * <p>NeoForge replaced Forge's {@code SimpleChannel} with typed payloads registered per mod. This
 * mod registers one payload that may travel both ways, matching the {@code minecheck:main} name the
 * scripts and the frame tap use. A single handler per direction still cannot be shared, so {@link
 * #observeChannel} cannot piggyback on it: watching another mod's channel needs the lower-level tap
 * ({@link NeoForgeClientChannelTap} on a client, {@link NeoForgeServerChannelTap} on a dedicated
 * server).
 *
 * <p>Payloads arrive on the network thread; the handler hands them to the game thread before the
 * receiver runs, which is where the script engine ticks.
 */
public class NeoForgeNetworkPlatform implements NetworkPlatform {

  private static final Logger LOG = Logger.getLogger("Minecheck");
  // One id for both directions, exactly like the Fabric and Forge transports use: scripts name the
  // channel `minecheck:main` and the frame tap matches on that name, so a per-direction id would
  // make every tap observe a channel that never carries a frame.
  private static final ResourceLocation ID = new ResourceLocation("minecheck", "main");

  private final boolean dedicated;
  private volatile boolean registered;
  private volatile Consumer<MinecheckPacket> receiver = packet -> {};

  public NeoForgeNetworkPlatform(boolean dedicated) {
    this.dedicated = dedicated;
  }

  /**
   * Registers both payloads on the mod event bus. Called from the entrypoint's constructor, which
   * is NeoForge's equivalent of putting a listener on the mod bus.
   */
  public void register(RegisterPayloadHandlerEvent event) {
    try {
      // The registrar argument is the payload namespace, which has to be this mod's id: any other
      // value makes NeoForge reject ids like minecheck:main_c2s. No versioned(...) and no
      // mandatory(): a peer without Minecheck must still be able to join, and the channel is for
      // testing rather than for gameplay state.
      IPayloadRegistrar registrar = event.registrar("minecheck").optional();
      registrar.play(
          ID,
          MinecheckPayload::decode,
          (payload, context) -> context.workHandler().execute(() -> deliver(payload.payload())));
      this.registered = true;
    } catch (RuntimeException e) {
      LOG.severe(String.format("Minecheck: failed to register the minecheck:main payloads: %s", e));
    }
  }

  @Override
  public boolean isAvailable() {
    if (!registered) {
      return false;
    }
    return !dedicated || ServerLifecycleHooks.getCurrentServer() != null;
  }

  @Override
  public boolean send(String name, List<PacketValue> args) {
    if (!isAvailable()) {
      return false;
    }
    byte[] payload = new MinecheckPacket(name, args).encode();
    try {
      if (dedicated) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getPlayerList().getPlayerCount() == 0) {
          return false;
        }
        PacketDistributor.ALL.noArg().send(new MinecheckPayload(payload));
      } else {
        PacketDistributor.SERVER.noArg().send(new MinecheckPayload(payload));
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
    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) {
      return false;
    }
    ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
    if (player == null) {
      return false;
    }
    byte[] payload = new MinecheckPacket(name, args).encode();
    try {
      PacketDistributor.PLAYER.with(player).send(new MinecheckPayload(payload));
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
    this.receiver = receiver;
  }

  /**
   * A frame tap is installed instead of a payload handler, because on NeoForge the tap sits
   * <em>below</em> the payload layer: it copies each decoded custom payload's bytes as they pass,
   * so it can observe a channel that another mod (or Minecheck's own transport) already receives. A
   * higher-level handler cannot do that on any loader — both Fabric's play-channel addon and
   * NeoForge's payload registry are single-handler per direction.
   */
  @Override
  public boolean observeChannel(String channel, Consumer<byte[]> observer) {
    try {
      return dedicated
          ? NeoForgeServerChannelTap.observe(channel, observer)
          : NeoForgeClientChannelTap.observe(channel, observer);
    } catch (RuntimeException e) {
      LOG.warning(
          String.format("Minecheck: failed to observe channel %s: %s", channel, String.valueOf(e)));
      return false;
    }
  }

  private void deliver(byte[] payload) {
    try {
      receiver.accept(MinecheckPacket.decode(payload));
    } catch (WireFormatException e) {
      LOG.warning(
          String.format(
              "Minecheck: dropped a malformed packet: %s", String.valueOf(e.getMessage())));
    }
  }

  /** The encoded {@link MinecheckPacket} frame, carried in either direction on {@link #ID}. */
  public record MinecheckPayload(byte[] payload) implements CustomPacketPayload {

    @Override
    public void write(FriendlyByteBuf buf) {
      buf.writeBytes(payload);
    }

    @Override
    public ResourceLocation id() {
      return ID;
    }

    public static MinecheckPayload decode(FriendlyByteBuf buf) {
      byte[] payload = new byte[buf.readableBytes()];
      buf.readBytes(payload);
      return new MinecheckPayload(payload);
    }
  }
}
