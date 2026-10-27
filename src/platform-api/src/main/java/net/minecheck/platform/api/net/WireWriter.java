package net.minecheck.platform.api.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Big-endian, VarInt-framed byte writer shared by the packet wire format and the Lua {@code
 * minecheck.reader} helper.
 *
 * <p>Integers use Minecraft's VarInt encoding; {@link #writeZigZag(int)} / {@link
 * #writeZigZag(long)} put signed values on the wire without wasting bytes on small negatives.
 * Lengths are always written as plain (unsigned) VarInts.
 */
public final class WireWriter {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  /** Writes one unsigned VarInt (Minecraft's 7-bits-per-byte, continuation-bit scheme). */
  public WireWriter writeVarInt(int value) {
    int v = value;
    while ((v & ~0x7F) != 0) {
      out.write((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    out.write(v);
    return this;
  }

  /** Writes one signed value ZigZag-encoded as a VarInt. */
  public WireWriter writeZigZag(int value) {
    return writeVarInt((value << 1) ^ (value >> 31));
  }

  /** Writes one signed long ZigZag-encoded as a VarLong. */
  public WireWriter writeZigZag(long value) {
    long v = (value << 1) ^ (value >> 63);
    while ((v & ~0x7FL) != 0) {
      out.write((int) ((v & 0x7F) | 0x80));
      v >>>= 7;
    }
    out.write((int) v);
    return this;
  }

  public WireWriter writeByte(int value) {
    out.write(value & 0xFF);
    return this;
  }

  public WireWriter writeBool(boolean value) {
    return writeByte(value ? 1 : 0);
  }

  public WireWriter writeInt(int value) {
    out.write((value >>> 24) & 0xFF);
    out.write((value >>> 16) & 0xFF);
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
    return this;
  }

  public WireWriter writeLong(long value) {
    for (int shift = 56; shift >= 0; shift -= 8) {
      out.write((int) ((value >>> shift) & 0xFF));
    }
    return this;
  }

  public WireWriter writeFloat(float value) {
    return writeInt(Float.floatToIntBits(value));
  }

  public WireWriter writeDouble(double value) {
    return writeLong(Double.doubleToLongBits(value));
  }

  /** Writes a VarInt byte length followed by the UTF-8 bytes of {@code value}. */
  public WireWriter writeString(String value) {
    return writeBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  /** Writes a VarInt byte length followed by the bytes. */
  public WireWriter writeBytes(byte[] value) {
    writeVarInt(value.length);
    out.writeBytes(value);
    return this;
  }

  /** Writes the raw bytes with no length prefix. */
  public WireWriter writeRaw(byte[] value) {
    out.writeBytes(value);
    return this;
  }

  /** Writes one wire value (tag + payload) with its length frame (see {@link MinecheckPacket}). */
  public WireWriter writeValue(PacketValue value) {
    WireWriter payload = new WireWriter();
    payload.writeByte(value.tag());
    if (value instanceof PacketValue.Bool b) {
      payload.writeBool(b.value());
    } else if (value instanceof PacketValue.Int i) {
      payload.writeZigZag(i.value());
    } else if (value instanceof PacketValue.Long l) {
      payload.writeZigZag(l.value());
    } else if (value instanceof PacketValue.Double d) {
      payload.writeDouble(d.value());
    } else if (value instanceof PacketValue.Str s) {
      payload.writeString(s.value());
    } else if (value instanceof PacketValue.Bytes b) {
      payload.writeBytes(b.value());
    } else if (value instanceof PacketValue.Skipped s) {
      payload.writeRaw(s.payload());
    }
    byte[] frames = payload.toByteArray();
    writeVarInt(frames.length);
    return writeRaw(frames);
  }

  public byte[] toByteArray() {
    return out.toByteArray();
  }
}
