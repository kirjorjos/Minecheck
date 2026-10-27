package net.minecheck.common.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.minecheck.common.script.LuaInt64;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.PacketValue;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Direct tests for the Lua-to-wire marshalling layer.
 *
 * <p>{@link MinecheckPacketTest} covers the byte layer ({@code PacketValue} to bytes and back).
 * This covers the layer above it — the one a script actually touches — in both directions: {@link
 * PacketValues#toWire} (Lua to {@code PacketValue}) and {@link PacketValues#toLua} ({@code
 * PacketValue} to Lua), and then both together through the real wire, because a value can survive
 * either half and still not survive the pair.
 *
 * <p>The cases that matter are the ones where a plausible-looking implementation is silently wrong:
 * the ±2^53 integer cutoff, the i64 asymmetry ({@code Int} comes back as a Lua number, {@code Long}
 * as an {@code int64}), bytes above {@code 0x7F}, and a {@code LuaString} whose bytes are a window
 * into a larger buffer.
 */
class PacketValuesTest {

  private static final String CONTEXT = "sendPacket";

  // ------------------------------------------------------------------ protocol

  /**
   * The tag numbers are part of the wire protocol ("do not renumber them"): reusing one for a
   * different variant would break every peer, in both directions and silently.
   */
  @Test
  void everyPacketValueVariantKeepsItsDocumentedTag() {
    assertEquals(0, new PacketValue.Nil().tag());
    assertEquals(1, new PacketValue.Bool(true).tag());
    assertEquals(2, new PacketValue.Int(1).tag());
    assertEquals(3, new PacketValue.Long(1L).tag());
    assertEquals(4, new PacketValue.Double(1.5).tag());
    assertEquals(5, new PacketValue.Str("s").tag());
    assertEquals(6, new PacketValue.Bytes(new byte[0]).tag());

    // The human-readable names logs and errors are built from, including the unknown-tag form a
    // newer sender produces.
    assertEquals("nil", PacketValue.tagName(new PacketValue.Nil().tag()));
    assertEquals("bool", PacketValue.tagName(new PacketValue.Bool(true).tag()));
    assertEquals("i32", PacketValue.tagName(new PacketValue.Int(1).tag()));
    assertEquals("i64", PacketValue.tagName(new PacketValue.Long(1L).tag()));
    assertEquals("f64", PacketValue.tagName(new PacketValue.Double(1.5).tag()));
    assertEquals("string", PacketValue.tagName(new PacketValue.Str("s").tag()));
    assertEquals("bytes", PacketValue.tagName(new PacketValue.Bytes(new byte[0]).tag()));
    assertEquals("unknown(99)", PacketValue.tagName(99));
  }

  /** {@code number()} is what keeps a small i64 from being sent as a big i32 — or the reverse. */
  @Test
  void numberPicksTheSmallestLosslessIntegerTag() {
    assertInstanceOf(PacketValue.Int.class, PacketValue.number(0));
    assertInstanceOf(PacketValue.Int.class, PacketValue.number(Integer.MAX_VALUE));
    assertInstanceOf(PacketValue.Int.class, PacketValue.number(Integer.MIN_VALUE));
    assertInstanceOf(PacketValue.Long.class, PacketValue.number((long) Integer.MAX_VALUE + 1));
    assertInstanceOf(PacketValue.Long.class, PacketValue.number((long) Integer.MIN_VALUE - 1));
    assertInstanceOf(PacketValue.Long.class, PacketValue.number(Long.MAX_VALUE));
    assertInstanceOf(PacketValue.Long.class, PacketValue.number(Long.MIN_VALUE));
  }

  // ---------------------------------------------------------- Lua -> wire

  @Test
  void primitiveLuaValuesMapToTheirWireTag() {
    assertInstanceOf(PacketValue.Nil.class, PacketValues.toWire(CONTEXT, LuaValue.NIL));
    assertEquals(true, ((PacketValue.Bool) wire(LuaValue.TRUE)).value());
    assertEquals(false, ((PacketValue.Bool) wire(LuaValue.FALSE)).value());
    assertEquals("hi", ((PacketValue.Str) wire(LuaValue.valueOf("hi"))).value());
  }

  @Test
  void integersInsideTheI32RangeTravelAsI32AndLeaveItAsI64() {
    assertEquals(7, ((PacketValue.Int) wire(LuaValue.valueOf(7))).value());
    assertEquals(-7, ((PacketValue.Int) wire(LuaValue.valueOf(-7))).value());
    assertEquals(
        Integer.MAX_VALUE, ((PacketValue.Int) wire(LuaValue.valueOf(Integer.MAX_VALUE))).value());
    assertEquals(
        Integer.MIN_VALUE, ((PacketValue.Int) wire(LuaValue.valueOf(Integer.MIN_VALUE))).value());

    assertEquals(
        (long) Integer.MAX_VALUE + 1,
        ((PacketValue.Long) wire(LuaValue.valueOf((double) Integer.MAX_VALUE + 1))).value());
    assertEquals(
        5_000_000_000L, ((PacketValue.Long) wire(LuaValue.valueOf(5_000_000_000d))).value());
  }

  /**
   * The ±2^53 cutoff, inclusive. 2^53 is the last integer a Lua number holds exactly, so it is
   * still an integer; past it the low bits are already gone, so calling it one would claim an
   * exactness it does not have and it must travel as f64 instead.
   */
  @Test
  void theTwoToThe53CutoffIsInclusiveAndPastItValuesTravelAsF64() {
    double cutoff = 9_007_199_254_740_992d; // 2^53

    assertEquals(
        (long) cutoff, ((PacketValue.Long) wire(LuaValue.valueOf(cutoff))).value(), "2^53 exactly");
    assertEquals(
        9_007_199_254_740_991L,
        ((PacketValue.Long) wire(LuaValue.valueOf(cutoff - 1))).value(),
        "2^53 - 1");

    // 2^53 + 2 is the first integer a double cannot hold; it goes as f64 rather than a rounded i64.
    assertInstanceOf(
        PacketValue.Double.class,
        wire(LuaValue.valueOf(cutoff + 2)),
        "past the cutoff the value is a double, not a lying integer");
    assertEquals(cutoff + 2, ((PacketValue.Double) wire(LuaValue.valueOf(cutoff + 2))).value());
  }

  @Test
  void fractionalAndNonFiniteNumbersTravelAsF64() {
    assertEquals(1.5, ((PacketValue.Double) wire(LuaValue.valueOf(1.5))).value());
    assertEquals(-0.25, ((PacketValue.Double) wire(LuaValue.valueOf(-0.25))).value());

    // Neither is an integer, and neither may be coerced to one: (long) NaN is 0, which would send a
    // confident wrong value rather than the one the script passed.
    PacketValue nan = wire(LuaValue.valueOf(Double.NaN));
    assertInstanceOf(PacketValue.Double.class, nan);
    assertTrue(Double.isNaN(((PacketValue.Double) nan).value()));

    assertEquals(
        Double.POSITIVE_INFINITY,
        ((PacketValue.Double) wire(LuaValue.valueOf(Double.POSITIVE_INFINITY))).value());
    assertEquals(
        Double.NEGATIVE_INFINITY,
        ((PacketValue.Double) wire(LuaValue.valueOf(Double.NEGATIVE_INFINITY))).value());
  }

  /**
   * An int64 is a table, so it would be rejected by the table branch if it were not checked first.
   *
   * <p>It then travels as the <em>smallest lossless</em> integer tag, which for a value inside the
   * i32 range is i32 — so a small int64 arrives on the other side as a plain Lua number, not an
   * int64. Pinned rather than assumed: it is the one place where the receiving Lua type depends on
   * the magnitude of the value, and a script that branches on {@code type(v)} would see the
   * difference. The value itself is never changed either way.
   */
  @Test
  void anInt64TravelsAsTheSmallestLosslessIntegerTagAndKeepsItsValue() {
    assertEquals(
        72_623_859_790_382_856L,
        ((PacketValue.Long) wire(LuaInt64.parse("72623859790382856"))).value());
    assertEquals(Long.MAX_VALUE, ((PacketValue.Long) wire(LuaInt64.of(Long.MAX_VALUE))).value());
    assertEquals(Long.MIN_VALUE, ((PacketValue.Long) wire(LuaInt64.of(Long.MIN_VALUE))).value());

    // Past the i32 range it is an i64, and comes back as an int64.
    assertInstanceOf(PacketValue.Long.class, wire(LuaInt64.of((long) Integer.MAX_VALUE + 1)));
    assertInstanceOf(LuaInt64.class, throughTheWire(LuaInt64.of((long) Integer.MAX_VALUE + 1)));

    // Inside it, it narrows to i32 and comes back as a number — exact, but a different Lua type.
    assertEquals(-1, ((PacketValue.Int) wire(LuaInt64.of(-1L))).value());
    assertEquals(5, ((PacketValue.Int) wire(LuaInt64.of(5L))).value());
    assertEquals("num:5", canonical(throughTheWire(LuaInt64.of(5L))));
  }

  /**
   * Bytes are byte-exact, including above {@code 0x7F}. The marker holds the {@link LuaString}
   * itself for exactly this reason: re-encoding the payload as a Java {@code String} and back would
   * replace every byte above {@code 0x7F} with a replacement character.
   */
  @Test
  void aBytesMarkerTravelsAsBytesWithEveryBytePreserved() {
    byte[] raw = {0x00, 0x7F, (byte) 0x80, (byte) 0xFF, 0x41};
    PacketValue.Bytes wire =
        (PacketValue.Bytes)
            PacketValues.toWire(CONTEXT, PacketValues.bytesMarker(LuaString.valueOf(raw)));

    assertArrayEquals(raw, wire.value());
  }

  @Test
  void aBytesMarkerWrapsTheStringWithoutReencodingIt() {
    LuaString payload = LuaString.valueOf("raw");
    LuaValue marker = PacketValues.bytesMarker(payload);

    assertTrue(marker.istable(), "the marker is a table");
    assertSame(payload, marker.rawget(PacketValues.BYTES_KEY), "holds the very same LuaString");
  }

  /**
   * The type guard is weaker than its message suggests, in a way worth pinning: LuaJ's {@code
   * isstring()} is <b>true for numbers</b> ({@code LuaInteger} and {@code LuaDouble} both override
   * it to true), so {@code isstring()} does not actually mean "is a Lua string". A number therefore
   * passes the guard and is coerced to its digits, exactly as {@code tostring} would.
   *
   * <p>Only {@code type() == TSTRING} distinguishes the two — the same trap {@code clickGui} and
   * {@code ArgsView} already work around.
   */
  @Test
  void bytesMarkerCoercesANumberToItsDigitsButRejectsEveryOtherType() {
    // A number is not rejected: it becomes its decimal text, byte for byte (0x35 is '5').
    LuaValue fromNumber = PacketValues.bytesMarker(LuaValue.valueOf(5));
    assertArrayEquals(
        new byte[] {0x35},
        PacketValues.bytesOf(CONTEXT, fromNumber.rawget(PacketValues.BYTES_KEY)),
        "bytes(5) is the byte '5', not an error and not the byte 0x05");

    // Everything genuinely non-string is rejected, with the message the guard was written for.
    LuaError booleanError =
        assertThrows(LuaError.class, () -> PacketValues.bytesMarker(LuaValue.TRUE));
    assertTrue(booleanError.getMessage().contains("expected a string"), booleanError.getMessage());
    assertTrue(booleanError.getMessage().contains("boolean"), booleanError.getMessage());
    assertThrows(LuaError.class, () -> PacketValues.bytesMarker(new LuaTable()));

    // ...and the same guard on the read side, for a marker table carrying a non-string payload.
    LuaTable marker = new LuaTable();
    marker.set(PacketValues.BYTES_KEY, LuaValue.TRUE);
    LuaError payloadError =
        assertThrows(LuaError.class, () -> PacketValues.toWire(CONTEXT, marker));
    assertTrue(
        payloadError.getMessage().contains("expected a string of bytes"),
        payloadError.getMessage());
  }

  @Test
  void tablesAndFunctionsAreRejectedWithTheActionNameInTheMessage() {
    LuaError table =
        assertThrows(LuaError.class, () -> PacketValues.toWire(CONTEXT, new LuaTable()));
    assertTrue(table.getMessage().contains(CONTEXT), table.getMessage());
    assertTrue(table.getMessage().contains("minecheck.bytes()"), table.getMessage());

    LuaError function = assertThrows(LuaError.class, () -> PacketValues.toWire(CONTEXT, fn()));
    assertTrue(function.getMessage().contains(CONTEXT), function.getMessage());
    assertTrue(function.getMessage().contains("function"), function.getMessage());
  }

  // ------------------------------------------------------------ bytesOf

  /**
   * A {@code LuaString} is a window into a byte buffer, and a script's payload is usually one:
   * {@code payload:sub(n)} or a value read out of the middle of a frame. Copying {@code m_bytes}
   * wholesale would return the whole buffer, so only {@code [m_offset, m_offset + m_length)} may be
   * read.
   */
  @Test
  void bytesOfReturnsExactlyTheWindowOfASubstringPayload() {
    LuaString haystack = LuaString.valueOf("HEADpayloadTAIL");
    LuaString window = haystack.substring(4, 11);

    assertEquals("payload", window.tojstring(), "fixture check: the window is the payload");
    assertNotNull(window.m_bytes);
    assertTrue(
        window.m_offset != 0, "fixture check: the fixture really does have a non-zero offset");
    assertTrue(
        window.m_length < window.m_bytes.length,
        "fixture check: the window is shorter than its backing buffer");

    assertArrayEquals(
        "payload".getBytes(StandardCharsets.UTF_8), PacketValues.bytesOf(CONTEXT, window));
  }

  /** The same, with bytes above {@code 0x7F} so a charset round trip would show up. */
  @Test
  void bytesOfKeepsHighBytesOfAWindowExact() {
    byte[] backing = {(byte) 0xFF, (byte) 0x80, 0x41, 0x42, 0x43, (byte) 0xFE};
    LuaString window = LuaString.valueOf(backing).substring(2, 5);

    assertArrayEquals(new byte[] {0x41, 0x42, 0x43}, PacketValues.bytesOf(CONTEXT, window));
  }

  @Test
  void bytesOfRejectsANonString() {
    LuaError error =
        assertThrows(LuaError.class, () -> PacketValues.bytesOf(CONTEXT, LuaValue.valueOf(true)));
    assertTrue(error.getMessage().contains(CONTEXT), error.getMessage());
    assertTrue(error.getMessage().contains("boolean"), error.getMessage());
  }

  // ---------------------------------------------------------- wire -> Lua

  /** Every variant, converted back. The canonical forms make the int64 asymmetry visible. */
  @Test
  void everyWireValueConvertsBackToTheDocumentedLuaValue() {
    record Case(String label, PacketValue wire, String canonical) {}

    byte[] raw = {0x00, 0x7F, (byte) 0x80, (byte) 0xFF};
    List<Case> cases =
        List.of(
            new Case("nil", new PacketValue.Nil(), "nil"),
            new Case("bool true", new PacketValue.Bool(true), "bool:true"),
            new Case("bool false", new PacketValue.Bool(false), "bool:false"),
            new Case("i32", new PacketValue.Int(-7), "num:-7"),
            new Case("i32 max", new PacketValue.Int(Integer.MAX_VALUE), "num:2147483647"),
            new Case("f64", new PacketValue.Double(1.5), "num:1.5"),
            new Case("f64 is not rounded", new PacketValue.Double(-0.125), "num:-0.125"),
            new Case(
                "string",
                new PacketValue.Str("hello"),
                "str:" + hex("hello".getBytes(StandardCharsets.UTF_8))),
            new Case(
                "i64 beyond 2^53",
                new PacketValue.Long(72_623_859_790_382_856L),
                "i64:72623859790382856"),
            new Case(
                "i64 both extremes",
                new PacketValue.Long(Long.MAX_VALUE),
                "i64:9223372036854775807"),
            new Case(
                "i64 both extremes",
                new PacketValue.Long(Long.MIN_VALUE),
                "i64:-9223372036854775808"),
            // A narrow i64 still arrives as an int64, not a number: the sender chose 64 bits, and
            // that choice is what the script branches on (`type(v)` differs).
            new Case("small i64 stays an int64", new PacketValue.Long(5L), "i64:5"),
            new Case("bytes", new PacketValue.Bytes(raw), "str:" + hex(raw)));

    for (Case c : cases) {
      assertEquals(c.canonical(), canonical(PacketValues.toLua(c.wire())), c.label());
    }
  }

  @Test
  void aWireStringReachesTheScriptWithNonAsciiIntact() {
    assertEquals("unicode: ✓", PacketValues.toLua(new PacketValue.Str("unicode: ✓")).tojstring());
  }

  @Test
  void anI64ArrivesAsAnExactValueNotARoundedNumber() {
    // The whole reason the type exists: as a double, 2^53 + 1 is 2^53.
    LuaValue seed = PacketValues.toLua(new PacketValue.Long(9_007_199_254_740_993L));

    LuaInt64 exact = assertInstanceOf(LuaInt64.class, seed);
    assertEquals("9007199254740993", exact.exactText());
    assertEquals("9007199254740993", seed.tojstring());
  }

  /**
   * An unknown tag is a value this build is too old to understand. It reaches the script as nil —
   * never as a wrong value — and says so in the log, because a script that silently receives nil
   * otherwise has nothing to go on.
   */
  @Test
  void anUnknownWireTagReachesTheScriptAsNilAndWarns() {
    List<LogRecord> records = new ArrayList<>();
    Logger logger = Logger.getLogger("Minecheck");
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      assertEquals(
          LuaValue.NIL, PacketValues.toLua(new PacketValue.Skipped(99, new byte[] {1, 2, 3})));
    } finally {
      logger.removeHandler(handler);
    }

    assertTrue(
        records.stream()
            .anyMatch(r -> r.getMessage() != null && r.getMessage().contains("unknown tag 99")),
        "expected a warning naming the tag, got: " + records);
    assertTrue(
        records.stream()
            .anyMatch(r -> r.getMessage() != null && r.getMessage().contains("unknown(99)")),
        "the warning should name the tag the human-readable way too, got: " + records);
  }

  // ------------------------------------------------------------ round trips

  /**
   * The pair, not either half: Lua to wire, encode, decode, back to Lua. A value can survive {@code
   * toWire} and {@code toLua} individually (both are tested above) and still not survive the bytes
   * between them, and this is the only test that would notice.
   */
  @Test
  void everyValueSurvivesATripThroughTheRealWire() {
    record Case(String label, LuaValue value, String canonical) {}

    byte[] raw = {0x00, 0x7F, (byte) 0x80, (byte) 0xFF, 0x41};
    List<Case> cases =
        List.of(
            new Case("nil", LuaValue.NIL, "nil"),
            new Case("true", LuaValue.TRUE, "bool:true"),
            new Case("false", LuaValue.FALSE, "bool:false"),
            new Case("integer", LuaValue.valueOf(7), "num:7"),
            new Case("negative integer", LuaValue.valueOf(-7), "num:-7"),
            new Case("i32 max", LuaValue.valueOf(Integer.MAX_VALUE), "num:2147483647"),
            new Case("i32 min", LuaValue.valueOf(Integer.MIN_VALUE), "num:-2147483648"),
            new Case("fraction", LuaValue.valueOf(1.5), "num:1.5"),
            // Past i32 a Lua number is sent as an i64 and comes back as an int64 — the exactness is
            // kept, and the type the script sees changes with it.
            new Case("integer past i32", LuaValue.valueOf(5_000_000_000d), "i64:5000000000"),
            new Case(
                "2^53 exactly", LuaValue.valueOf(9_007_199_254_740_992d), "i64:9007199254740992"),
            new Case("2^53 - 1", LuaValue.valueOf(9_007_199_254_740_991d), "i64:9007199254740991"),
            new Case(
                "string",
                LuaValue.valueOf("hello"),
                "str:" + hex("hello".getBytes(StandardCharsets.UTF_8))),
            new Case("int64 max", LuaInt64.of(Long.MAX_VALUE), "i64:9223372036854775807"),
            new Case("int64 min", LuaInt64.of(Long.MIN_VALUE), "i64:-9223372036854775808"),
            new Case(
                "int64 as a string", LuaInt64.parse("72623859790382856"), "i64:72623859790382856"),
            new Case("bytes", PacketValues.bytesMarker(LuaString.valueOf(raw)), "str:" + hex(raw)));

    for (Case c : cases) {
      assertEquals(c.canonical(), canonical(throughTheWire(c.value)), c.label());
    }
  }

  @Test
  void aNonAsciiStringSurvivesTheWire() {
    assertEquals(
        "unicode: ✓ — ünïcøde",
        throughTheWire(LuaValue.valueOf("unicode: ✓ — ünïcøde")).tojstring());
  }

  @Test
  void nonFiniteNumbersSurviveTheWire() {
    LuaValue nan = throughTheWire(LuaValue.valueOf(Double.NaN));
    assertTrue(nan.isnumber() && Double.isNaN(nan.todouble()), "NaN, not a coerced 0");

    assertEquals(
        Double.POSITIVE_INFINITY,
        throughTheWire(LuaValue.valueOf(Double.POSITIVE_INFINITY)).todouble());
    assertEquals(
        Double.NEGATIVE_INFINITY,
        throughTheWire(LuaValue.valueOf(Double.NEGATIVE_INFINITY)).todouble());
  }

  /**
   * The int64 round trip is a different path from the number one, so it gets its own check —
   * including that what comes back is still an {@code int64} and still usable as a number.
   *
   * <p>Arithmetic on it is deliberately *not* exact (it returns a plain number, like {@code
   * reader.readLong()} does), so this does not assert a {@code + 1} stays exact: that would be
   * asserting the opposite of the documented design.
   */
  @Test
  void anInt64ComesBackExactAndStillUsableAsANumber() {
    LuaInt64 sent = LuaInt64.parse("72623859790382856");
    LuaValue received = throughTheWire(sent);

    assertInstanceOf(LuaInt64.class, received, "still an int64, not a rounded number");
    assertEquals("72623859790382856", received.tojstring());
    assertTrue(received.eq_b(sent), "the same value compares equal");
    assertEquals(sent.tojstring(), throughTheWire(sent).tojstring(), "and is stable across trips");

    // Usable as a number: the nearest double is what a Lua number would have been, but the int64
    // kept the exact text alongside it.
    LuaInt64 back = (LuaInt64) received;
    assertEquals(72_623_859_790_382_856L, back.longValue());
    assertEquals((double) 72_623_859_790_382_856L, back.todouble());
  }

  // ------------------------------------------------------------ helpers

  private static PacketValue wire(LuaValue value) {
    return PacketValues.toWire(CONTEXT, value);
  }

  private static PacketValue throughTheWireValue(LuaValue value) {
    MinecheckPacket packet = new MinecheckPacket("t", List.of(wire(value)));
    return MinecheckPacket.decode(packet.encode()).args().get(0);
  }

  /** A full trip: Lua to wire value, to bytes, back to a wire value, back to Lua. */
  private static LuaValue throughTheWire(LuaValue value) {
    return PacketValues.toLua(throughTheWireValue(value));
  }

  /**
   * A Lua value's exact form, for comparison after a round trip. Deliberately distinguishes an
   * int64 from a number (they are different things to a script) and compares strings by their
   * bytes, so a text/bytes mix-up or a charset round trip cannot pass.
   */
  private static String canonical(LuaValue value) {
    if (value.isnil()) {
      return "nil";
    }
    if (value instanceof LuaInt64 exact) {
      return "i64:" + exact.exactText();
    }
    if (value.isboolean()) {
      return "bool:" + value.toboolean();
    }
    if (value.isnumber()) {
      return "num:" + value.tojstring();
    }
    return "str:" + hex(PacketValues.bytesOf("canonical", value));
  }

  private static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02X", b));
    }
    return sb.toString();
  }

  private static LuaValue fn() {
    return new VarArgFunction() {
      @Override
      public Varargs invoke(Varargs args) {
        return LuaValue.NIL;
      }
    };
  }
}
