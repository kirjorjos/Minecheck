package net.minecheck.common.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * The script-facing packet surface: kept history of received packets, the {@code onPacket}
 * handlers, and the foreign-channel decoders.
 *
 * <p><b>Threading.</b> {@link #tick()} runs once per engine tick on the game thread (before the
 * script is resumed) and is the only place Lua is touched. Handlers and decoders therefore run on
 * the game thread while the script coroutine is parked: they must be short and must <em>not</em>
 * yield (no {@code sleep}/{@code waitFor} inside a handler) — a handler that yields would park the
 * game thread.
 *
 * <p>History is deliberately <em>not</em> consumed by {@code waitForPacket}: a packet that arrived
 * before the call (e.g. a reply that beat the client's wait loop) still satisfies it. Bounded to
 * the most recent {@link #MAX_HISTORY} packets so a long-running script cannot grow without end.
 */
public final class PacketChannel {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** How many received packets to keep for {@code waitForPacket}/{@code onPacket}. */
  public static final int MAX_HISTORY = 256;

  /** One received packet: its name and the Lua values of its arguments. */
  public record Packet(String name, List<LuaValue> args) {

    /** The Lua view: {@code { name = "…", args = { … } }}. */
    public LuaTable toLua() {
      LuaTable table = new LuaTable();
      table.set("name", LuaValue.valueOf(name));
      LuaTable list = new LuaTable();
      for (int i = 0; i < args.size(); i++) {
        list.set(i + 1, args.get(i));
      }
      table.set("args", list);
      return table;
    }

    /** The arguments as a Lua varargs list, for invoking a predicate or handler. */
    public Varargs varargs() {
      return LuaValue.varargsOf(args.toArray(new LuaValue[0]));
    }
  }

  private final NetworkPlatform network;
  private final PacketInbox inbox;
  private final Consumer<String> failureSink;

  private final List<Packet> history = new ArrayList<>();
  private final Map<String, List<LuaValue>> handlers = new LinkedHashMap<>();
  private final Map<String, LuaValue> decoders = new LinkedHashMap<>();

  public PacketChannel(NetworkPlatform network, PacketInbox inbox, Consumer<String> failureSink) {
    this.network = network;
    this.inbox = inbox;
    this.failureSink = failureSink;
  }

  /** True when the loader registered its channel and packets can flow. */
  public boolean available() {
    return network != null && network.isAvailable();
  }

  /**
   * True when the loader registered a transport at all, whether or not it is up yet.
   *
   * <p>Distinct from {@link #available()} in the one way that matters to a waiter: an unavailable
   * channel can become available later (a client that has not connected yet), so waiting on it is
   * meaningful, whereas no transport can never deliver anything, so waiting on it is guaranteed
   * waste.
   */
  public boolean hasTransport() {
    return network != null;
  }

  public boolean send(String name, List<PacketValue> args) {
    if (!available()) {
      return false;
    }
    return network.send(name, args);
  }

  public boolean sendTo(String player, String name, List<PacketValue> args) {
    if (!available()) {
      return false;
    }
    return network.sendTo(player, name, args);
  }

  /** Registers an {@code onPacket} handler. Several handlers may watch the same name. */
  public void onPacket(String name, LuaValue handler) {
    handlers.computeIfAbsent(name, k -> new ArrayList<>()).add(handler);
  }

  /** Registers the decoder used for an observed foreign channel. */
  public void registerDecoder(String channel, LuaValue decoder) {
    decoders.put(channel, decoder);
  }

  /**
   * Opts in to observing a foreign channel: the loader delivers raw payloads into the inbox, and
   * {@link #tick()} runs the channel's decoder on them.
   */
  public boolean observe(String channel) {
    if (network == null) {
      return false;
    }
    return network.observeChannel(channel, payload -> inbox.deliverRaw(channel, payload));
  }

  /** Received packets with the given name, oldest first (may be empty). */
  public List<Packet> received(String name) {
    List<Packet> out = new ArrayList<>();
    for (Packet p : history) {
      if (p.name().equals(name)) {
        out.add(p);
      }
    }
    return out;
  }

  /**
   * Drains the inbox and dispatches, on the game thread. Handlers and decoders run inline; an error
   * inside either fails the script (via {@code failureSink}) instead of silently dropping packets.
   */
  public void tick() {
    for (PacketInbox.Received received : inbox.drain()) {
      List<LuaValue> args = new ArrayList<>(received.args().size());
      for (PacketValue value : received.args()) {
        args.add(PacketValues.toLua(value));
      }
      record(new Packet(received.name(), args));
    }
    for (PacketInbox.Raw raw : inbox.drainRaw()) {
      LuaValue decoder = decoders.get(raw.channel());
      if (decoder == null) {
        LOG.warning(
            String.format(
                "Minecheck: payload on observed channel %s but no decoder registered (minecheck.registerPacketDecoder)",
                raw.channel()));
        continue;
      }
      try {
        LuaValue decoded = decoder.call(LuaString.valueOf(raw.payload()));
        record(new Packet(raw.channel(), List.of(decoded)));
      } catch (RuntimeException e) {
        failureSink.accept(
            String.format(
                "packet decoder for %s failed: %s", raw.channel(), String.valueOf(e.getMessage())));
      }
    }
  }

  private void record(Packet packet) {
    history.add(packet);
    if (history.size() > MAX_HISTORY) {
      history.remove(0);
    }
    List<LuaValue> watching = handlers.get(packet.name());
    if (watching == null) {
      return;
    }
    for (LuaValue handler : watching) {
      try {
        handler.invoke(packet.varargs());
      } catch (RuntimeException e) {
        failureSink.accept(
            String.format(
                "onPacket(%s) handler failed: %s", packet.name(), String.valueOf(e.getMessage())));
      }
    }
  }

  /** Ignores anything still queued; used by tests. */
  public void clearHistory() {
    history.clear();
  }
}
