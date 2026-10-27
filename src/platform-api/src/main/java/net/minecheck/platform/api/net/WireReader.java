package net.minecheck.platform.api.net;

import java.nio.charset.StandardCharsets;

/**
 * Big-endian, VarInt-framed byte reader shared by the packet wire format and the Lua {@code
 * minecheck.reader} helper. The {@code read*} methods mirror {@link WireWriter}.
 *
 * <p>Malformed input raises {@link WireFormatException}, which the Lua layer surfaces as a Lua
 * error rather than an unchecked crash.
 */
public final class WireReader {

  private final byte[] data;
  private int offset;

  public WireReader(byte[] data) {
    this(data, 0);
  }

  public WireReader(byte[] data, int offset) {
    if (offset < 0 || offset > data.length) {
      throw new WireFormatException("offset " + offset + " outside 0.." + data.length);
    }
    this.data = data;
    this.offset = offset;
  }

  /** Bytes not yet consumed. */
  public int remaining() {
    return data.length - offset;
  }

  /** Current read position. */
  public int offset() {
    return offset;
  }

  private int require(int count) {
    if (count < 0 || remaining() < count) {
      throw new WireFormatException(
          "truncated packet: need "
              + count
              + " byte(s) at offset "
              + offset
              + ", have "
              + remaining());
    }
    return offset;
  }

  public int readByte() {
    require(1);
    return data[offset++] & 0xFF;
  }

  public boolean readBool() {
    return readByte() != 0;
  }

  /** Reads one unsigned VarInt (up to 5 bytes). */
  public int readVarInt() {
    int result = 0;
    for (int shift = 0; shift < 32; shift += 7) {
      int b = readByte();
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
    }
    throw new WireFormatException("VarInt longer than 5 bytes at offset " + offset);
  }

  /** Reads one signed value that was ZigZag-encoded as a VarInt. */
  public int readZigZag() {
    int raw = readVarInt();
    return (raw >>> 1) ^ -(raw & 1);
  }

  /** Reads one signed long that was ZigZag-encoded as a VarLong (up to 10 bytes). */
  public long readZigZagLong() {
    long result = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      int b = readByte();
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return (result >>> 1) ^ -(result & 1);
      }
    }
    throw new WireFormatException("VarLong longer than 10 bytes at offset " + offset);
  }

  public int readInt() {
    int o = require(4);
    int v =
        ((data[o] & 0xFF) << 24)
            | ((data[o + 1] & 0xFF) << 16)
            | ((data[o + 2] & 0xFF) << 8)
            | (data[o + 3] & 0xFF);
    offset += 4;
    return v;
  }

  public long readLong() {
    int o = require(8);
    long v = 0;
    for (int i = 0; i < 8; i++) {
      v = (v << 8) | (data[o + i] & 0xFFL);
    }
    offset += 8;
    return v;
  }

  public float readFloat() {
    return Float.intBitsToFloat(readInt());
  }

  public double readDouble() {
    return Double.longBitsToDouble(readLong());
  }

  /** Reads a VarInt byte length and then that many bytes. */
  public byte[] readBytes() {
    return readBytes(readVarInt());
  }

  /** Reads exactly {@code count} raw bytes (no length prefix). */
  public byte[] readBytes(int count) {
    int o = require(count);
    byte[] out = new byte[count];
    System.arraycopy(data, o, out, 0, count);
    offset += count;
    return out;
  }

  /** Reads everything left, no length prefix. */
  public byte[] readRemaining() {
    return readBytes(remaining());
  }

  /** Reads a VarInt byte length and then that many UTF-8 bytes. */
  public String readString() {
    return new String(readBytes(), StandardCharsets.UTF_8);
  }
}
