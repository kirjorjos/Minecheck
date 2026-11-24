package net.minecheck.loader.legacyfabric;

import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;

/**
 * The custom-payload packet the mod's channel travels in on this line.
 *
 * <p>1.4.7 predates the split into C2S and S2C custom payloads: there is one packet class, and the
 * same class is sent in both directions. It carries the channel name and the raw bytes, which is
 * exactly the wire shape the encoded {@link net.minecheck.platform.api.net.MinecheckPacket} needs.
 */
final class LegacyPayloads {

  private LegacyPayloads() {}

  static CustomPayloadC2SPacket toServer(byte[] payload) {
    return new CustomPayloadC2SPacket(LegacyFabricNetworkPlatform.CHANNEL_NAME, payload);
  }

  static CustomPayloadC2SPacket toClient(byte[] payload) {
    return new CustomPayloadC2SPacket(LegacyFabricNetworkPlatform.CHANNEL_NAME, payload);
  }

  /** The packet's channel, or {@code null} when the packet carries none. */
  static String channelOf(CustomPayloadC2SPacket packet) {
    return packet == null ? null : packet.channel;
  }

  /** The packet's bytes, or {@code null} when the packet carries none. */
  static byte[] bytesOf(CustomPayloadC2SPacket packet) {
    return packet == null ? null : packet.field_2455;
  }
}
