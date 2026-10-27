package net.minecheck.platform.api.net;

/**
 * One value on a Minecheck packet (see {@code .notes/harness-and-coordinator-design.md} §5.1).
 *
 * <p>The wire carries a tag per value, so the receiving side reconstructs the exact Lua type that
 * was sent. Values are framed with a length prefix by {@link MinecheckPacket}, which is what makes
 * forward-compatible skipping possible: a reader that does not know a tag can skip the frame
 * wholesale using its length.
 *
 * <p>Modelled as a sealed interface of records so callers can handle the tags exhaustively with
 * {@code instanceof} patterns:
 *
 * <pre>{@code
 * String text = value instanceof PacketValue.Str s ? s.value() : "?";
 * }</pre>
 */
public sealed interface PacketValue {

  /** Tag ids on the wire. These are part of the protocol; do not renumber them. */
  int TAG_NIL = 0;

  int TAG_BOOL = 1;
  int TAG_I32 = 2;
  int TAG_I64 = 3;
  int TAG_F64 = 4;
  int TAG_STRING = 5;
  int TAG_BYTES = 6;

  /** The wire tag of this value. */
  int tag();

  /** Human-readable tag name, used in logs and errors. */
  static String tagName(int tag) {
    return switch (tag) {
      case TAG_NIL -> "nil";
      case TAG_BOOL -> "bool";
      case TAG_I32 -> "i32";
      case TAG_I64 -> "i64";
      case TAG_F64 -> "f64";
      case TAG_STRING -> "string";
      case TAG_BYTES -> "bytes";
      default -> "unknown(" + tag + ")";
    };
  }

  /** Builds the smallest lossless integer value: {@link Int} when it fits, else {@link Long}. */
  static PacketValue number(long value) {
    if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
      return new Int((int) value);
    }
    return new Long(value);
  }

  /** Lua {@code nil} (also the value of an absent argument). */
  record Nil() implements PacketValue {
    @Override
    public int tag() {
      return TAG_NIL;
    }
  }

  record Bool(boolean value) implements PacketValue {
    @Override
    public int tag() {
      return TAG_BOOL;
    }
  }

  record Int(int value) implements PacketValue {
    @Override
    public int tag() {
      return TAG_I32;
    }
  }

  record Long(long value) implements PacketValue {
    @Override
    public int tag() {
      return TAG_I64;
    }
  }

  record Double(double value) implements PacketValue {
    @Override
    public int tag() {
      return TAG_F64;
    }
  }

  record Str(String value) implements PacketValue {
    @Override
    public int tag() {
      return TAG_STRING;
    }
  }

  /**
   * Raw bytes. On the Lua side these are plain strings: Lua strings are byte sequences, so no
   * conversion is needed and a script can index or concatenate a payload directly. {@code
   * minecheck.bytes(s)} marks a string as bytes when sending.
   */
  record Bytes(byte[] value) implements PacketValue {
    @Override
    public int tag() {
      return TAG_BYTES;
    }
  }

  /**
   * A value whose tag this reader does not know, kept verbatim so it can be skipped and (if the
   * packet is forwarded or re-encoded) re-emitted byte-for-byte. The Lua layer maps it to {@code
   * nil} and logs a warning: a script never sees a tag it did not ask for.
   */
  record Skipped(int tag, byte[] payload) implements PacketValue {}
}
