package net.minecheck.common.net;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.PacketValue;

/**
 * Thread-safe hand-off from a loader's network thread to the script engine's tick.
 *
 * <p>Packets arrive on the loader's network thread (Fabric's netty event loop, Forge's network
 * thread). The engine ticks on the game thread, so a loader installs a receiver that only <em>
 * enqueues</em> here, and the engine drains the queues once per tick — where it is safe to touch
 * Lua state. Nothing in this class calls into Lua.
 *
 * <p>The loader-facing wiring is installed by {@code MinecheckBootstrap} at startup, so packets
 * that arrive before the configured script's first tick are still delivered to it.
 */
public final class PacketInbox {

  /** A decoded Minecheck packet as it came off the wire. */
  public record Received(String name, List<PacketValue> args) {}

  /** A raw payload captured from another mod's channel (undecoded; the script decodes it). */
  public record Raw(String channel, byte[] payload) {}

  private static final PacketInbox INSTANCE = new PacketInbox();

  /** The process-wide inbox the loaders deliver into and the running script drains. */
  public static PacketInbox instance() {
    return INSTANCE;
  }

  private final ConcurrentLinkedQueue<Received> packets = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<Raw> raw = new ConcurrentLinkedQueue<>();

  public PacketInbox() {}

  /** Called on the network thread for each received Minecheck packet. */
  public void deliver(MinecheckPacket packet) {
    packets.add(new Received(packet.name(), packet.args()));
  }

  /** Called on the network thread for each payload received on an observed foreign channel. */
  public void deliverRaw(String channel, byte[] payload) {
    raw.add(new Raw(channel, payload));
  }

  /** Removes and returns the pending Minecheck packets, oldest first. */
  public List<Received> drain() {
    List<Received> out = new ArrayList<>();
    for (Received r = packets.poll(); r != null; r = packets.poll()) {
      out.add(r);
    }
    return out;
  }

  /** Removes and returns the pending foreign-channel payloads, oldest first. */
  public List<Raw> drainRaw() {
    List<Raw> out = new ArrayList<>();
    for (Raw r = raw.poll(); r != null; r = raw.poll()) {
      out.add(r);
    }
    return out;
  }
}
