package net.minecheck.platform.yarn;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * The single {@code minecheck:main} payload.
 *
 * <p>1.20.5 replaced raw channel buffers with typed payloads: a receiver is registered for a
 * payload {@link CustomPayload.Id}, and both sides must declare a codec for that id before the play
 * phase. Registering in this class's initialiser (rather than in either transport) means a client
 * that also constructs the server-side transport registers the pair exactly once.
 */
public record MinecheckPayload(byte[] data) implements CustomPayload {

  // The Id constructor takes a full identifier; CustomPayload.id(String) treats its argument as a
  // path under `minecraft:` and rejects the colon.
  public static final CustomPayload.Id<MinecheckPayload> ID =
      new CustomPayload.Id<>(net.minecraft.util.Identifier.of("minecheck", "main"));

  public static final PacketCodec<RegistryByteBuf, MinecheckPayload> CODEC =
      PacketCodec.of(
          (payload, buf) -> buf.writeBytes(payload.data()),
          buf -> {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return new MinecheckPayload(data);
          });

  static {
    PayloadTypeRegistry.playC2S().register(ID, CODEC);
    PayloadTypeRegistry.playS2C().register(ID, CODEC);
  }

  @Override
  public CustomPayload.Id<? extends CustomPayload> getId() {
    return ID;
  }
}
