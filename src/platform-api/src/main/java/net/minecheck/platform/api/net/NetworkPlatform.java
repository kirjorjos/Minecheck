package net.minecheck.platform.api.net;

import java.util.List;
import java.util.function.Consumer;

/**
 * Loader-provided packet transport for the Minecheck channel (design note §5.3).
 *
 * <p>The client and the dedicated server talk over the vanilla connection with {@link
 * MinecheckPacket} frames; there are no files and no extra ports. Fabric/Quilt implement this with
 * {@code fabric-networking-api-v1}'s {@code ClientPlayNetworking}/{@code ServerPlayNetworking},
 * Forge with an FML {@code SimpleChannel}.
 *
 * <p><b>Delivery.</b> Packets arrive on the loader's network thread, so an implementation must
 * schedule them onto the game thread before handing them to the {@link #setReceiver receiver} — the
 * script engine ticks on the game thread and reads the inbox from there.
 */
public interface NetworkPlatform {

  /** The channel name both sides register ({@code minecheck:main}). */
  String CHANNEL = "minecheck:main";

  /**
   * True once the channel is registered in both directions and packets can flow. False when the
   * loader's networking is missing or registration failed; {@code minecheck.sendPacket} then
   * returns false instead of pretending to have sent something.
   */
  default boolean isAvailable() {
    return false;
  }

  /**
   * Sends a packet to the other side: client → server, or server → every connected player. Returns
   * false when the channel is unavailable or the connection is not ready yet (e.g. before join).
   */
  boolean send(String name, List<PacketValue> args);

  /**
   * Server-only: sends a packet to one named player. Returns false when that player is not online
   * (or on the client, where there is no player list to address).
   */
  default boolean sendTo(String playerName, String name, List<PacketValue> args) {
    return false;
  }

  /**
   * Installs the receiver invoked for every decoded Minecheck packet, on the game thread. Called
   * once by the loader at startup, before any script can run.
   */
  void setReceiver(Consumer<MinecheckPacket> receiver);

  /**
   * Opts in to observing another mod's channel, delivering each received payload's raw bytes to
   * {@code receiver} (on the game thread). The payload is opaque here: the script supplies a
   * decoder.
   *
   * <p>Returns false when this channel cannot be observed here — e.g. another handler already
   * receives on it and the loader's networking is single-receiver per channel per connection (both
   * Fabric's play-channel addon and Forge's {@code SimpleChannel} are), which no implementation can
   * share or chain around — or the platform has no such support. Implementations are expected to
   * check the live connection rather than report "installed" for a handler that is shadowed and
   * would never run. Scripts assert on the return value rather than assuming coverage.
   */
  default boolean observeChannel(String channel, Consumer<byte[]> receiver) {
    return false;
  }
}
