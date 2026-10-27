package net.minecheck.common.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecheck.common.config.ConfigData;
import net.minecheck.common.net.PacketInbox;
import net.minecheck.platform.api.ClientPlatform;
import net.minecheck.platform.api.ConfigDirProvider;
import net.minecheck.platform.api.PlatformServices;
import net.minecheck.platform.api.ServerPlatform;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.NetworkPlatform;
import net.minecheck.platform.api.net.PacketValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Lua packet surface ({@code sendPacket}/{@code waitForPacket}/{@code onPacket}/
 * {@code watchPacketChannel}/{@code reader}) against a fake transport.
 *
 * <p>These run with no client and no server: packet actions are side-agnostic, which is exactly
 * what lets one script body coordinate a client and a dedicated server.
 */
class PacketActionsTest {

  @TempDir Path tempDir;

  /**
   * Fake transport: records what a script sends and can be driven as if the loader received data.
   */
  static final class FakeNetwork implements NetworkPlatform {
    final List<String> sent = new ArrayList<>();
    final List<String> sentTo = new ArrayList<>();
    final List<String> observed = new ArrayList<>();
    Consumer<MinecheckPacket> receiver = packet -> {};
    Consumer<byte[]> observer;
    boolean available = true;
    boolean observeSupported = true;

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public boolean send(String name, List<PacketValue> args) {
      if (!available) {
        return false;
      }
      sent.add(name + "|" + describe(args));
      return true;
    }

    @Override
    public boolean sendTo(String playerName, String name, List<PacketValue> args) {
      if (!available) {
        return false;
      }
      sentTo.add(playerName + "|" + name + "|" + describe(args));
      return true;
    }

    @Override
    public void setReceiver(Consumer<MinecheckPacket> receiver) {
      this.receiver = receiver;
    }

    @Override
    public boolean observeChannel(String channel, Consumer<byte[]> observer) {
      if (!observeSupported) {
        return false;
      }
      observed.add(channel);
      this.observer = observer;
      return true;
    }
  }

  /** {@code <tag>:<value>} per argument, so tests can assert the exact wire types. */
  private static String describe(List<PacketValue> args) {
    StringBuilder sb = new StringBuilder();
    for (PacketValue arg : args) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(PacketValue.tagName(arg.tag())).append(':');
      if (arg instanceof PacketValue.Int i) {
        sb.append(i.value());
      } else if (arg instanceof PacketValue.Long l) {
        sb.append(l.value());
      } else if (arg instanceof PacketValue.Double d) {
        sb.append(d.value());
      } else if (arg instanceof PacketValue.Bool b) {
        sb.append(b.value());
      } else if (arg instanceof PacketValue.Str s) {
        sb.append(s.value());
      } else if (arg instanceof PacketValue.Bytes b) {
        sb.append(b.value().length);
      }
    }
    return sb.toString();
  }

  @BeforeEach
  void clearInbox() {
    PacketInbox.instance().drain();
    PacketInbox.instance().drainRaw();
  }

  private ScriptEngine engine(NetworkPlatform network, String script, AtomicLong clock)
      throws Exception {
    Path scriptFile = tempDir.resolve("packet-script.lua");
    Files.writeString(scriptFile, script);
    PlatformServices services =
        new PlatformServices() {
          @Override
          public String minecraftVersion() {
            return "1.19.2";
          }

          @Override
          public ConfigDirProvider configDir() {
            return () -> tempDir;
          }

          @Override
          public ClientPlatform client() {
            return null;
          }

          @Override
          public ServerPlatform server() {
            return null;
          }

          @Override
          public boolean dedicatedServer() {
            return false;
          }

          @Override
          public NetworkPlatform network() {
            return network;
          }
        };
    return new ScriptEngine(services, ConfigData.defaults(), scriptFile, clock::get);
  }

  private static void runUntilFinished(ScriptEngine engine, int maxTicks) {
    for (int i = 0; i < maxTicks && !engine.isFinished(); i++) {
      engine.tick();
    }
  }

  /** Runs the engine until the script finishes, advancing the fake clock past any timeout. */
  private static void runWithTimeouts(ScriptEngine engine, AtomicLong clock) {
    for (int i = 0; i < 20 && !engine.isFinished(); i++) {
      engine.tick();
      clock.addAndGet(5000);
    }
  }

  @Test
  void sendPacketMarshalsLuaValues() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(
            network,
            "local ok = minecheck.sendPacket('hello', 1, 'two', true, 3.5, minecheck.bytes('\\0\\255'))\n"
                + "minecheck.expect(ok, 'sendPacket returns true')\n",
            new AtomicLong());
    runUntilFinished(engine, 10);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
    assertEquals(List.of("hello|i32:1,string:two,bool:true,f64:3.5,bytes:2"), network.sent);
  }

  /**
   * The wire type of an integral Lua number turns over at ±2^53, the last integer a double holds
   * exactly. Past it, sending i64 would assert bits the number never had, so the value goes as f64
   * (documented in `docs/lua-api.md`); {@code minecheck.int64} is the exact route.
   */
  @Test
  void sendPacketTurnsIntegersOverToF64PastDoubleExactness() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(
            network,
            // 2^53: the cutoff is inclusive, and this value is exact.
            "minecheck.sendPacket('edge', 9007199254740992)\n"
                // 2^54: past the cutoff, so it must not masquerade as an integer.
                + "minecheck.sendPacket('edge', 9007199254740992 * 2)\n"
                // The same digits as a string stay exact all 64 bits the way.
                + "minecheck.sendPacket('edge', minecheck.int64('9007199254740993'))\n"
                // ...whereas the literal 2^53+1 is already 2^53 by the time Lua hands it over —
                // which is exactly why int64 takes a string for values that matter.
                + "minecheck.sendPacket('edge', 9007199254740993)\n",
            new AtomicLong());
    runUntilFinished(engine, 10);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
    assertEquals(
        List.of(
            "edge|i64:9007199254740992",
            "edge|f64:1.8014398509481984E16",
            "edge|i64:9007199254740993",
            "edge|i64:9007199254740992"),
        network.sent);
  }

  @Test
  void sendPacketMarshalsBigIntegersAsI64() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(network, "minecheck.sendPacket('big', 5000000000)\n", new AtomicLong());
    runUntilFinished(engine, 10);
    assertEquals(List.of("big|i64:5000000000"), network.sent);
  }

  @Test
  void sendPacketAndSendPacketToReturnFalseWhenUnavailable() throws Exception {
    FakeNetwork network = new FakeNetwork();
    network.available = false;
    ScriptEngine engine =
        engine(
            network,
            "minecheck.expect(minecheck.sendPacket('x', 1) == false, 'send false')\n"
                + "minecheck.expect(minecheck.sendPacketTo('Steve', 'x') == false, 'sendTo false')\n",
            new AtomicLong());
    runUntilFinished(engine, 10);
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
    assertTrue(network.sent.isEmpty());
  }

  @Test
  void sendPacketToTargetsOnePlayer() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(network, "minecheck.sendPacketTo('Steve', 'hi', 7)\n", new AtomicLong());
    runUntilFinished(engine, 10);
    assertEquals(List.of("Steve|hi|i32:7"), network.sentTo);
  }

  @Test
  void waitForPacketReturnsAPacketThatArrivedFirst() throws Exception {
    FakeNetwork network = new FakeNetwork();
    PacketInbox.instance()
        .deliver(
            new MinecheckPacket(
                "reply", List.of(new PacketValue.Str("hi"), new PacketValue.Int(3))));

    ScriptEngine engine =
        engine(
            network,
            "local p = minecheck.waitForPacket('reply', 5)\n"
                + "minecheck.expect(p ~= false, 'got a reply')\n"
                + "minecheck.expect(p.name == 'reply', 'name is the packet name')\n"
                + "minecheck.expect(p.args[1] == 'hi' and p.args[2] == 3, 'args decoded')\n",
            new AtomicLong());
    runUntilFinished(engine, 10);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void receivedPacketInt64ArrivesExactlyAndCanBeForwarded() throws Exception {
    // An i64 that arrived on the wire is worth no less than one read from a payload: it used to be
    // rounded into a double on the way into Lua.
    FakeNetwork network = new FakeNetwork();
    PacketInbox.instance()
        .deliver(new MinecheckPacket("seed", List.of(new PacketValue.Long(72623859790382856L))));

    ScriptEngine engine =
        engine(
            network,
            "local p = minecheck.waitForPacket('seed', 5)\n"
                + "assert(p ~= false, 'got the packet')\n"
                + "local seed = p.args[1]\n"
                + "assert(tostring(seed) == '72623859790382856', 'exact: ' .. tostring(seed))\n"
                + "assert(seed == minecheck.int64('72623859790382856'), 'equals the same value')\n"
                + "minecheck.sendPacket('echo', seed)\n",
            new AtomicLong());
    runUntilFinished(engine, 10);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
    assertEquals(List.of("echo|i64:72623859790382856"), network.sent);
  }

  @Test
  void waitForPacketPredicateGatesAndTimeoutReturnsFalse() throws Exception {
    FakeNetwork network = new FakeNetwork();
    PacketInbox.instance().deliver(new MinecheckPacket("num", List.of(new PacketValue.Int(1))));
    AtomicLong clock = new AtomicLong();

    ScriptEngine engine =
        engine(
            network,
            "local first = minecheck.waitForPacket('num')\n"
                + "minecheck.expect(first ~= false, 'unfiltered match')\n"
                + "local gated = minecheck.waitForPacket('num', function(a) return a > 100 end, 1)\n"
                + "minecheck.expect(gated == false, 'predicate timeout returns false')\n",
            clock);
    runWithTimeouts(engine, clock);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void onPacketHandlerRunsWithThePacketArguments() throws Exception {
    FakeNetwork network = new FakeNetwork();
    AtomicLong clock = new AtomicLong();
    ScriptEngine engine =
        engine(
            network,
            "local seen = nil\n"
                + "minecheck.onPacket('ping', function(a, b) seen = a .. ':' .. b end)\n"
                + "minecheck.expect(minecheck.waitFor(function() return seen ~= nil end, 5), 'handler ran')\n"
                + "minecheck.expect(seen == 'pong:2', 'handler saw the args: ' .. tostring(seen))\n",
            clock);

    runUntilFinished(engine, 2); // registers the handler, then waits
    assertFalse(engine.isFinished());
    PacketInbox.instance()
        .deliver(
            new MinecheckPacket(
                "ping", List.of(new PacketValue.Str("pong"), new PacketValue.Int(2))));
    runWithTimeouts(engine, clock);

    assertTrue(engine.isFinished(), "the script must reach its asserts");
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void handlerErrorFailsTheRunInsteadOfDroppingPackets() throws Exception {
    FakeNetwork network = new FakeNetwork();
    AtomicLong clock = new AtomicLong();
    ScriptEngine engine =
        engine(
            network,
            "minecheck.onPacket('boom', function() error('handler exploded') end)\n"
                + "minecheck.waitFor(function() return false end, 30)\n",
            clock);

    runUntilFinished(engine, 2); // registers the handler, then waits
    assertFalse(engine.isFinished());
    PacketInbox.instance().deliver(new MinecheckPacket("boom", List.of()));
    runWithTimeouts(engine, clock);

    assertTrue(engine.isFailed());
    assertTrue(
        String.valueOf(engine.failureMessage()).contains("onPacket(boom) handler failed"),
        String.valueOf(engine.failureMessage()));
  }

  @Test
  void watchPacketChannelDrivesTheRegisteredDecoder() throws Exception {
    FakeNetwork network = new FakeNetwork();
    AtomicLong clock = new AtomicLong();
    ScriptEngine engine =
        engine(
            network,
            "minecheck.expect(minecheck.watchPacketChannel('other-mod:chan'), 'observed')\n"
                + "minecheck.registerPacketDecoder('other-mod:chan', function(bytes)\n"
                + "  return minecheck.reader(bytes).readVarInt()\n"
                + "end)\n"
                + "local p = minecheck.waitForPacket('other-mod:chan', 5)\n"
                + "minecheck.expect(p ~= false and p.args[1] == 7, 'decoded payload: ' .. tostring(p))\n",
            clock);

    runUntilFinished(engine, 2); // reaches the wait
    assertFalse(engine.isFinished());
    assertNotNull(network.observer, "watchPacketChannel must register an observer");
    network.observer.accept(new byte[] {7});
    runUntilFinished(engine, 10);

    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
    assertEquals(List.of("other-mod:chan"), network.observed);
  }

  @Test
  void waitForPacketReturnsFalseAtOnceWhenTheLoaderWiresNoTransport() throws Exception {
    // The loader registered no network platform, so a packet can never arrive: waiting out the
    // timeout is pure waste. A single tick must be enough to finish the script — the script is
    // parked inside waitForPacket after the first tick if it is waiting.
    AtomicLong clock = new AtomicLong();
    ScriptEngine engine =
        engine(
            null,
            "minecheck.expect(minecheck.waitForPacket('never', 60) == false, 'no transport')\n",
            clock);
    engine.tick();

    assertTrue(
        engine.isFinished(), "waitForPacket must not spin when there is no transport to wait on");
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void waitForPacketStillWaitsOnAChannelThatIsMerelyUnavailable() throws Exception {
    // The contrast that keeps the rule honest: a channel that exists but is not up yet can come up
    // later, so this one genuinely must wait rather than answer false immediately.
    FakeNetwork network = new FakeNetwork();
    network.available = false;
    AtomicLong clock = new AtomicLong();
    ScriptEngine engine =
        engine(
            network,
            "local p = minecheck.waitForPacket('later', 60)\n"
                + "minecheck.expect(p ~= false and p.args[1] == 'now', 'received while waiting')\n",
            clock);
    engine.tick();
    assertFalse(engine.isFinished(), "an unavailable channel is worth waiting on");

    network.available = true;
    PacketInbox.instance()
        .deliver(new MinecheckPacket("later", List.of(new PacketValue.Str("now"))));
    engine.tick();
    assertTrue(engine.isFinished(), "the packet that arrived while waiting is seen");
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void watchPacketChannelReportsUnsupportedLoaders() throws Exception {
    FakeNetwork network = new FakeNetwork();
    network.observeSupported = false;
    ScriptEngine engine =
        engine(
            network,
            "minecheck.expect(minecheck.watchPacketChannel('other-mod:chan') == false, 'unsupported')\n",
            new AtomicLong());
    runUntilFinished(engine, 10);
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void readerDecodesVarIntPrefixedStringsAndFixedWidthNumbers() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(
            network,
            "local r = minecheck.reader('\\5hello')\n"
                + "minecheck.expect(r.readString() == 'hello', 'varint-prefixed string')\n"
                + "minecheck.expect(r.remaining() == 0, 'consumed everything')\n"
                + "local r2 = minecheck.reader('\\1\\2\\0\\0\\0\\7')\n"
                + "minecheck.expect(#r2.readBytes(2) == 2, 'raw bytes')\n"
                + "minecheck.expect(r2.readInt() == 7, 'fixed big-endian int')\n"
                + "minecheck.expect(r2.offset() == 6 and r2.remaining() == 0, 'cursor moved')\n",
            new AtomicLong());
    runUntilFinished(engine, 10);
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void readerDecodesEveryOtherDocumentedFixedWidthType() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(
            network,
            // Lua's own assert rather than minecheck.expect: a failed expect is recorded in the run
            // result but does not fail the run, so expect-based checks cannot tell this test that a
            // value came back wrong. Each assert names the value it actually read.
            // 1.25f is 0x3FA00000; -2.5d is 0xC004000000000000.
            "local f = minecheck.reader('\\63\\160\\0\\0')\n"
                + "local fl = f.readFloat()\n"
                + "assert(fl == 1.25, 'readFloat: ' .. tostring(fl))\n"
                + "local d = minecheck.reader('\\192\\4\\0\\0\\0\\0\\0\\0')\n"
                + "local db = d.readDouble()\n"
                + "assert(db == -2.5, 'readDouble: ' .. tostring(db))\n"
                + "local b = minecheck.reader('\\1\\0')\n"
                + "assert(b.readBool() == true, 'readBool true')\n"
                + "assert(b.readBool() == false, 'readBool false')\n"
                // A VarInt byte length then that many bytes, twice, to prove it advances.
                + "local s = minecheck.reader('\\5hello\\3abc')\n"
                + "assert(s.readBytesVarInt() == 'hello', 'readBytesVarInt first chunk')\n"
                + "assert(s.readBytesVarInt() == 'abc', 'readBytesVarInt second chunk')\n"
                + "assert(s.remaining() == 0, 'readBytesVarInt consumed the length prefix')\n"
                + "local l = minecheck.reader('\\1\\2\\3\\4\\5\\6\\7\\8')\n"
                + "assert(l.readLong() > 0, 'readLong reads a positive value')\n"
                + "assert(l.remaining() == 0, 'readLong consumed 8 bytes')\n",
            new AtomicLong());
    runUntilFinished(engine, 10);
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  /**
   * A 64-bit reader value now survives intact, in both directions.
   *
   * <p>{@code 0x0102030405060708L} is {@code 72623859790382856}, which no Lua double can hold — the
   * old binding rounded it to {@code ...848} with no warning. It arrives as an int64 whose exact
   * text and outgoing wire form are both the original value.
   */
  @Test
  void readerReadLongKeepsEveryBit() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(
            network,
            "local v = minecheck.reader('\\1\\2\\3\\4\\5\\6\\7\\8').readLong()\n"
                + "assert(tostring(v) == '72623859790382856', 'exact text, got ' .. tostring(v))\n"
                + "minecheck.sendPacket('probe', v)\n",
            new AtomicLong());
    runUntilFinished(engine, 10);
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
    assertEquals(List.of("probe|i64:72623859790382856"), network.sent);
  }

  /** The int64 value type: exact as text, as comparison, and as a wire argument. */
  @Test
  void int64IsAnExactIntegerInLua() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(
            network,
            "local small = minecheck.int64(7)\n"
                // Rendering and coercion, without becoming a double.
                + "assert(tostring(small) == '7', 'tostring')\n"
                + "assert(small:toString() == '7', ':toString')\n"
                + "assert(small .. '' == '7' and '' .. small == '7', 'concat, both sides')\n"
                + "assert(string.format('%s', small) == '7', '%s')\n"
                // Arithmetic converts to a number, in both operand orders.
                + "assert(small + 1 == 8 and 1 + small == 8, 'add')\n"
                + "assert(small - 10 == -3, 'sub')\n"
                + "assert(small * 2 == 14, 'mul')\n"
                + "assert(small / 2 == 3.5, 'div')\n"
                + "assert(small % 4 == 3, 'mod')\n"
                + "assert(2 ^ small == 128, 'pow')\n"
                + "assert(-small == -7, 'unary minus')\n"
                + "assert(math.floor(small / 2) == 3, 'math sees a number')\n"
                // The point of the type: a value no double can hold, compared exactly.
                + "local big = minecheck.int64('72623859790382856')\n"
                + "assert(big:toNumber() == 72623859790382848, 'toNumber is the rounded double')\n"
                + "assert(big ~= 72623859790382848, 'not the double it would have become')\n"
                + "assert(big > 72623859790382848, 'greater than that double, exactly')\n"
                + "assert(72623859790382848 < big, 'and in the reversed operand order')\n"
                + "assert(big == minecheck.int64('72623859790382856'), 'equal values')\n"
                + "assert(big ~= minecheck.int64('72623859790382857'), 'unequal values')\n"
                + "assert(big >= minecheck.int64('72623859790382856'), 'greater-or-equal')\n"
                + "assert(big <= minecheck.int64('72623859790382856'), 'less-or-equal')\n"
                // eq() compares values: a Lua literal rounds, so it never equals the exact value.
                + "assert(big:eq(72623859790382856) == false, 'a rounded literal is not equal')\n"
                + "assert(big:eq(big) == true, ':eq accepts the value')\n"
                // Lua dispatches __eq only between two tables, so this order is uniformly false.
                + "assert((72623859790382856 == big) == false, 'number == int64 is false by Lua rule')\n"
                + "minecheck.sendPacket('probe', big)\n",
            new AtomicLong());
    runUntilFinished(engine, 10);
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
    assertEquals(List.of("probe|i64:72623859790382856"), network.sent);
  }

  @Test
  void int64ConstructorRejectsWhatItCannotRepresent() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(
            network,
            // note: '-' is a lazy quantifier in a Lua pattern, hence the %- escapes
            "local okString, errString = pcall(function() return minecheck.int64('not a number') end)\n"
                + "assert(okString == false and string.find(errString, 'not a 64%-bit integer'), 'text: ' .. tostring(errString))\n"
                + "local okFloat, errFloat = pcall(function() return minecheck.int64(1.5) end)\n"
                + "assert(okFloat == false and string.find(errFloat, 'not a 64%-bit integer'), 'float: ' .. tostring(errFloat))\n"
                + "local okRange, errRange = pcall(function() return minecheck.int64('9223372036854775808') end)\n"
                + "assert(okRange == false and string.find(errRange, 'not a 64%-bit integer'), 'too big: ' .. tostring(errRange))\n"
                // A 64-bit value that does not fit in 32 bits is refused, never truncated.
                + "local okInt, errInt = pcall(function() return minecheck.int64('72623859790382856'):toString() end)\n"
                + "assert(okInt == true, 'toString always works')\n"
                + "local ok32, err32 = pcall(function() return string.format('%d', minecheck.int64('72623859790382856')) end)\n"
                + "assert(ok32 == false and string.find(err32, 'does not fit in a 32%-bit int'), 'narrow refuse: ' .. tostring(err32))\n",
            new AtomicLong());
    runUntilFinished(engine, 10);
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void readingPastTheEndIsALuaErrorNamingTheMethod() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(
            network,
            "local r = minecheck.reader('')\n"
                + "local ok, err = pcall(function() return r.readInt() end)\n"
                + "minecheck.expect(ok == false, 'truncated read raises')\n"
                + "minecheck.expect(string.find(err, 'reader.readInt') ~= nil, 'error names the method: ' .. tostring(err))\n",
            new AtomicLong());
    runUntilFinished(engine, 10);
    assertFalse(engine.isFailed(), String.valueOf(engine.failureMessage()));
  }

  @Test
  void sendingATableWithoutTheBytesMarkerIsRejected() throws Exception {
    FakeNetwork network = new FakeNetwork();
    ScriptEngine engine =
        engine(network, "minecheck.sendPacket('oops', { 1, 2 })\n", new AtomicLong());
    runUntilFinished(engine, 10);
    assertTrue(engine.isFailed());
    assertTrue(
        String.valueOf(engine.failureMessage()).contains("cannot send a table"),
        String.valueOf(engine.failureMessage()));
  }
}
