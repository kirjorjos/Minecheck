package net.minecheck.platform.api.net;

import java.util.ArrayList;
import java.util.List;

/**
 * The Minecheck packet payload (design note {@code .notes/harness-and-coordinator-design.md} §5.1).
 *
 * <pre>
 * int32 discriminator; String name; VarInt argc; Value[] args
 * </pre>
 *
 * <p>Values are {@link PacketValue}s, each framed as {@code VarInt frameLength; byte tag; payload}
 * so a reader that meets an unknown tag can still skip it and keep parsing the rest of the packet —
 * the forward compatibility the design note asks for. The discriminator lets a future protocol
 * revision change the framing; {@link #DISCRIMINATOR_RENDEZVOUS} is the value used for the
 * script-facing surface, and 5+ remains reserved.
 *
 * <p>This class is pure Java by design: loaders hand its {@code encode()}/{@code decode()} bytes to
 * (and from) their platform's byte buffer, so the format has exactly one implementation.
 */
public final class MinecheckPacket {

  /** Discriminator for the script-facing send/wait surface ({@code 0.x} rendezvous range). */
  public static final int DISCRIMINATOR_RENDEZVOUS = 0;

  /** Largest accepted frame, as a sanity bound on hostile/garbage input. */
  public static final int MAX_FRAME_BYTES = 1 << 16;

  private final int discriminator;
  private final String name;
  private final List<PacketValue> args;

  public MinecheckPacket(int discriminator, String name, List<PacketValue> args) {
    checkName(name);
    this.discriminator = discriminator;
    this.name = name;
    this.args = List.copyOf(args);
  }

  public MinecheckPacket(String name, List<PacketValue> args) {
    this(DISCRIMINATOR_RENDEZVOUS, name, args);
  }

  private static void checkName(String name) {
    if (name == null || name.isEmpty()) {
      throw new WireFormatException("packet name must not be empty");
    }
    if (name.length() > 256) {
      throw new WireFormatException("packet name longer than 256 chars");
    }
  }

  public int discriminator() {
    return discriminator;
  }

  /** The logical packet name a script sends and waits on (e.g. {@code "hello"}). */
  public String name() {
    return name;
  }

  public List<PacketValue> args() {
    return args;
  }

  public byte[] encode() {
    WireWriter out = new WireWriter();
    out.writeInt(discriminator);
    out.writeString(name);
    out.writeVarInt(args.size());
    for (PacketValue arg : args) {
      out.writeValue(arg);
    }
    byte[] bytes = out.toByteArray();
    if (bytes.length > MAX_FRAME_BYTES) {
      throw new WireFormatException("packet frame exceeds " + MAX_FRAME_BYTES + " bytes");
    }
    return bytes;
  }

  /**
   * Decodes one packet frame.
   *
   * @throws WireFormatException when the bytes are truncated or not a Minecheck frame
   */
  public static MinecheckPacket decode(byte[] bytes) {
    if (bytes.length > MAX_FRAME_BYTES) {
      throw new WireFormatException("packet frame exceeds " + MAX_FRAME_BYTES + " bytes");
    }
    WireReader in = new WireReader(bytes);
    int discriminator = in.readInt();
    String name = in.readString();
    int argc = in.readVarInt();
    if (argc < 0 || argc > 4096) {
      throw new WireFormatException("implausible argument count " + argc);
    }
    List<PacketValue> args = new ArrayList<>(argc);
    for (int i = 0; i < argc; i++) {
      args.add(readValue(in));
    }
    if (in.remaining() != 0) {
      throw new WireFormatException(in.remaining() + " trailing byte(s) after packet frame");
    }
    return new MinecheckPacket(discriminator, name, args);
  }

  /**
   * Reads one length-framed value. An unknown tag is skipped via its frame length, so a newer
   * sender cannot break an older reader.
   */
  private static PacketValue readValue(WireReader in) {
    int frameLength = in.readVarInt();
    if (frameLength <= 0 || frameLength > in.remaining()) {
      throw new WireFormatException("bad value frame length " + frameLength);
    }
    WireReader frame = new WireReader(in.readBytes(frameLength));
    int tag = frame.readByte();
    return switch (tag) {
      case PacketValue.TAG_NIL -> new PacketValue.Nil();
      case PacketValue.TAG_BOOL -> new PacketValue.Bool(frame.readBool());
      case PacketValue.TAG_I32 -> new PacketValue.Int(frame.readZigZag());
      case PacketValue.TAG_I64 -> new PacketValue.Long(frame.readZigZagLong());
      case PacketValue.TAG_F64 -> new PacketValue.Double(frame.readDouble());
      case PacketValue.TAG_STRING -> new PacketValue.Str(frame.readString());
      case PacketValue.TAG_BYTES -> new PacketValue.Bytes(frame.readBytes());
      default -> new PacketValue.Skipped(tag, frame.readRemaining());
    };
  }
}
