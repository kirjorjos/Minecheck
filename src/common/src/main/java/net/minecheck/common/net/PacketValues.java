package net.minecheck.common.net;

import java.util.Arrays;
import java.util.logging.Logger;
import net.minecheck.common.script.LuaInt64;
import net.minecheck.platform.api.net.PacketValue;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Marshalling between Lua values and the packet wire.
 *
 * <p>Type mapping:
 *
 * <table>
 *   <tr><th>Lua</th><th>wire</th></tr>
 *   <tr><td>nil</td><td>nil</td></tr>
 *   <tr><td>boolean</td><td>bool</td></tr>
 *   <tr><td>integer number within ±2<sup>53</sup></td><td>i32 when it fits, else i64</td></tr>
 *   <tr><td>fractional number, or an integral one past ±2<sup>53</sup></td><td>f64</td></tr>
 *   <tr><td>{@code minecheck.int64(v)}</td><td>i64, exactly</td></tr>
 *   <tr><td>string</td><td>string</td></tr>
 *   <tr><td>{@code minecheck.bytes(s)} marker</td><td>bytes</td></tr>
 * </table>
 *
 * <p>The ±2<sup>53</sup> cutoff is the last integer a Lua number (a double) holds exactly. Past it
 * the low bits are already gone, so calling the value an integer would claim an exactness it does
 * not have — it travels as f64 instead, and {@code minecheck.int64} is how a script sends all 64
 * bits. The cutoff is inclusive: 2<sup>53</sup> itself is exact and still goes as i64.
 *
 * <p>Incoming bytes become a plain Lua string: Lua strings are byte sequences, so a script can
 * index and slice a payload directly. Tables, functions, threads and userdata cannot be serialized
 * and are rejected with a Lua error naming the offending argument.
 */
public final class PacketValues {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** Key that marks a table as a raw-bytes wrapper; see {@link #bytesMarker}. */
  public static final String BYTES_KEY = "__minecheck_bytes";

  private PacketValues() {}

  /**
   * Wraps a Lua string in the marker table {@code minecheck.bytes(s)} returns.
   *
   * <p>The wrapper holds the {@link LuaString} itself, never a re-encoded Java string: LuaJ strings
   * are byte sequences, and round-tripping through {@code new String(bytes)} would corrupt bytes
   * above 0x7F on the way back out.
   *
   * <p>A <em>number</em> is not rejected: LuaJ's {@code isstring()} is true for numbers, so it
   * falls through to the string coercion and is wrapped as its decimal text ({@code bytes(5)} is
   * the byte {@code '5'}). Only genuinely non-string types raise. Use {@code type(v) == "string"}
   * in a script to tell the two apart.
   */
  public static LuaValue bytesMarker(LuaValue value) {
    if (!value.isstring()) {
      throw new LuaError("bytes: expected a string, got " + value.typename());
    }
    LuaTable marker = new LuaTable();
    marker.set(BYTES_KEY, value.checkstring());
    return marker;
  }

  /**
   * Converts one Lua argument to a wire value.
   *
   * @param context action name used in error messages (e.g. {@code sendPacket})
   * @param value the Lua value
   * @throws LuaError when the value has no wire representation
   */
  public static PacketValue toWire(String context, LuaValue value) {
    if (value.isnil()) {
      return new PacketValue.Nil();
    }
    // Before the table check below, which would reject it: an int64 carries a 64-bit integer that a
    // Lua number cannot, so it is passed through as an integer rather than as a double. It is then
    // narrowed by PacketValue.number() like any other integer, so an int64 inside the i32 range
    // travels as an i32 — exact either way, but the Lua type a receiver sees depends on the
    // magnitude. See PacketValuesTest for the pinned behavior.
    if (value instanceof LuaInt64 i) {
      return PacketValue.number(i.longValue());
    }
    if (value.isboolean()) {
      return new PacketValue.Bool(value.toboolean());
    }
    if (value.isnumber()) {
      double d = value.todouble();
      if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) <= 9.007199254740992E15) {
        return PacketValue.number((long) d);
      }
      return new PacketValue.Double(d);
    }
    if (value.isstring()) {
      return new PacketValue.Str(value.tojstring());
    }
    if (value.istable()) {
      LuaValue payload = value.rawget(BYTES_KEY);
      if (!payload.isnil()) {
        return new PacketValue.Bytes(bytesOf(context, payload));
      }
      throw new LuaError(
          context
              + ": cannot send a table — pass strings, numbers, booleans, nil, or a payload "
              + "wrapped with minecheck.bytes()");
    }
    throw new LuaError(context + ": cannot send a " + value.typename());
  }

  /** Extracts the raw bytes of a Lua string (Lua strings already are byte sequences). */
  public static byte[] bytesOf(String context, LuaValue value) {
    if (!value.isstring()) {
      throw new LuaError(context + ": expected a string of bytes, got " + value.typename());
    }
    LuaString s = value.checkstring();
    return Arrays.copyOfRange(s.m_bytes, s.m_offset, s.m_offset + s.m_length);
  }

  /** Converts one wire value to the Lua value a script sees. */
  public static LuaValue toLua(PacketValue value) {
    if (value instanceof PacketValue.Bool b) {
      return LuaValue.valueOf(b.value());
    }
    if (value instanceof PacketValue.Int i) {
      return LuaValue.valueOf(i.value());
    }
    if (value instanceof PacketValue.Long l) {
      // Exact, not a rounded double: see LuaInt64.
      return LuaInt64.of(l.value());
    }
    if (value instanceof PacketValue.Double d) {
      return LuaValue.valueOf(d.value());
    }
    if (value instanceof PacketValue.Str s) {
      return LuaValue.valueOf(s.value());
    }
    if (value instanceof PacketValue.Bytes b) {
      return LuaString.valueOf(b.value());
    }
    if (value instanceof PacketValue.Skipped s) {
      LOG.warning(
          String.format(
              "Minecheck: skipping packet value with unknown tag %d (%s) — this Minecheck build is older than the sender's",
              s.tag(), PacketValue.tagName(s.tag())));
      return LuaValue.NIL;
    }
    return LuaValue.NIL;
  }
}
