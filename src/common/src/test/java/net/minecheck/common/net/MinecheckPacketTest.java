package net.minecheck.common.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecheck.platform.api.net.MinecheckPacket;
import net.minecheck.platform.api.net.PacketValue;
import net.minecheck.platform.api.net.WireFormatException;
import net.minecheck.platform.api.net.WireReader;
import net.minecheck.platform.api.net.WireWriter;
import org.junit.jupiter.api.Test;

class MinecheckPacketTest {

  @Test
  void roundTripsEveryValueType() {
    byte[] raw = {(byte) 0x00, (byte) 0xFF, (byte) 0x80, 0x7F};
    MinecheckPacket sent =
        new MinecheckPacket(
            "hello",
            List.of(
                new PacketValue.Nil(),
                new PacketValue.Bool(true),
                new PacketValue.Int(-7),
                new PacketValue.Long(5_000_000_000L),
                new PacketValue.Double(1.5),
                new PacketValue.Str("unicode: ✓"),
                new PacketValue.Bytes(raw)));

    MinecheckPacket received = MinecheckPacket.decode(sent.encode());

    assertEquals(MinecheckPacket.DISCRIMINATOR_RENDEZVOUS, received.discriminator());
    assertEquals("hello", received.name());
    assertEquals(7, received.args().size());
    assertInstanceOf(PacketValue.Nil.class, received.args().get(0));
    assertEquals(true, ((PacketValue.Bool) received.args().get(1)).value());
    assertEquals(-7, ((PacketValue.Int) received.args().get(2)).value());
    assertEquals(5_000_000_000L, ((PacketValue.Long) received.args().get(3)).value());
    assertEquals(1.5, ((PacketValue.Double) received.args().get(4)).value());
    assertEquals("unicode: ✓", ((PacketValue.Str) received.args().get(5)).value());
    assertArrayEquals(raw, ((PacketValue.Bytes) received.args().get(6)).value());
  }

  @Test
  void keepsLargeLongsExact() {
    MinecheckPacket sent =
        new MinecheckPacket("big", List.of(new PacketValue.Long(Long.MAX_VALUE)));
    PacketValue value = MinecheckPacket.decode(sent.encode()).args().get(0);
    assertEquals(Long.MAX_VALUE, ((PacketValue.Long) value).value());
  }

  @Test
  void emptyArgListRoundTrips() {
    MinecheckPacket received =
        MinecheckPacket.decode(new MinecheckPacket("ping", List.of()).encode());
    assertEquals("ping", received.name());
    assertTrue(received.args().isEmpty());
  }

  @Test
  void rejectsTruncatedAndTrailingBytes() {
    byte[] good = new MinecheckPacket("x", List.of(new PacketValue.Int(1))).encode();

    byte[] truncated = new byte[good.length - 1];
    System.arraycopy(good, 0, truncated, 0, truncated.length);
    assertThrows(WireFormatException.class, () -> MinecheckPacket.decode(truncated));

    byte[] withTrailer = new byte[good.length + 1];
    System.arraycopy(good, 0, withTrailer, 0, good.length);
    assertThrows(WireFormatException.class, () -> MinecheckPacket.decode(withTrailer));

    assertThrows(WireFormatException.class, () -> MinecheckPacket.decode(new byte[] {1, 2, 3}));
  }

  /**
   * A value with a tag this build does not know is skipped by its frame length, so a newer sender
   * cannot break an older reader — the forward compatibility §5.1 asks for.
   */
  @Test
  void skipsUnknownValueTagsAndStillReadsTheRest() {
    WireWriter writer = new WireWriter();
    writer.writeInt(MinecheckPacket.DISCRIMINATOR_RENDEZVOUS);
    writer.writeString("mixed");
    writer.writeVarInt(2);
    // A frame with an unknown tag 99 and an opaque payload.
    WireWriter unknown = new WireWriter();
    unknown.writeByte(99).writeRaw(new byte[] {1, 2, 3});
    byte[] unknownFrame = unknown.toByteArray();
    writer.writeVarInt(unknownFrame.length).writeRaw(unknownFrame);
    // ...followed by a value this build does understand.
    writer.writeValue(new PacketValue.Str("after"));

    MinecheckPacket decoded = MinecheckPacket.decode(writer.toByteArray());

    assertEquals(2, decoded.args().size());
    PacketValue.Skipped skipped =
        assertInstanceOf(PacketValue.Skipped.class, decoded.args().get(0));
    assertEquals(99, skipped.tag());
    assertEquals("after", ((PacketValue.Str) decoded.args().get(1)).value());
  }

  /** The skipped value survives a re-encode, so a proxy can forward packets it cannot read. */
  @Test
  void reencodesSkippedValuesVerbatim() {
    WireWriter writer = new WireWriter();
    writer.writeInt(MinecheckPacket.DISCRIMINATOR_RENDEZVOUS);
    writer.writeString("mixed");
    writer.writeVarInt(1);
    WireWriter unknown = new WireWriter();
    unknown.writeByte(42).writeRaw(new byte[] {9, 8, 7, 6});
    byte[] unknownFrame = unknown.toByteArray();
    writer.writeVarInt(unknownFrame.length).writeRaw(unknownFrame);

    byte[] original = writer.toByteArray();
    assertArrayEquals(original, MinecheckPacket.decode(original).encode());
  }

  @Test
  void readerAndWriterAgreeOnFixedWidthTypes() {
    WireWriter writer = new WireWriter();
    writer.writeInt(0x01020304);
    writer.writeLong(0x0102030405060708L);
    writer.writeFloat(1.25f);
    writer.writeDouble(-2.5d);
    writer.writeBool(true);
    writer.writeString("héllo");
    writer.writeBytes("héllo".getBytes(StandardCharsets.UTF_8));

    WireReader reader = new WireReader(writer.toByteArray());
    assertEquals(0x01020304, reader.readInt());
    assertEquals(0x0102030405060708L, reader.readLong());
    assertEquals(1.25f, reader.readFloat());
    assertEquals(-2.5d, reader.readDouble());
    assertTrue(reader.readBool());
    assertEquals("héllo", reader.readString());
    assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), reader.readBytes());
    assertEquals(0, reader.remaining());
  }

  @Test
  void zigZagKeepsSmallNegativesSmall() {
    WireWriter small = new WireWriter();
    small.writeZigZag(-1);
    assertArrayEquals(new byte[] {0x01}, small.toByteArray(), "-1 must be a single byte");

    WireWriter extreme = new WireWriter();
    extreme.writeZigZag(Long.MIN_VALUE);
    WireReader reader = new WireReader(extreme.toByteArray());
    assertEquals(Long.MIN_VALUE, reader.readZigZagLong());
  }

  @Test
  void readerRejectsTruncatedInput() {
    WireReader reader = new WireReader(new byte[] {1, 2});
    assertThrows(WireFormatException.class, reader::readInt);
  }
}
