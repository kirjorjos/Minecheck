package net.minecheck.loader.legacyfabric;

import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.util.PacketByteBuf;

/**
 * The custom-payload packets the mod's channel travels in on this line.
 *
 * <p>1.12.2's payload packets take a channel name and a byte buffer, which is exactly the wire
 * shape the encoded {@link net.minecheck.platform.api.net.MinecheckPacket} needs. Both directions
 * are built here so the channel name lives in one place.
 */
final class LegacyPayloads {

  private LegacyPayloads() {}

  static CustomPayloadC2SPacket toServer(byte[] payload) {
    return new CustomPayloadC2SPacket(LegacyFabricNetworkPlatform.CHANNEL_NAME, buffer(payload));
  }

  static CustomPayloadS2CPacket toClient(byte[] payload) {
    return new CustomPayloadS2CPacket(LegacyFabricNetworkPlatform.CHANNEL_NAME, buffer(payload));
  }

  private static PacketByteBuf buffer(byte[] payload) {
    PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer(Math.max(1, payload.length)));
    buf.writeBytes(payload);
    return buf;
  }

  /** A non-destructive copy of a payload's bytes, or {@code null} when the buffer is gone. */
  static byte[] bytesOf(PacketByteBuf buf) {
    if (buf == null) {
      return null;
    }
    byte[] out = new byte[buf.readableBytes()];
    buf.getBytes(buf.readerIndex(), out);
    return out;
  }
}
