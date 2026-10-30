package net.minecheck.common.live;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * One accepted live connection: non-blocking reads, UTF-8, newline-framed.
 *
 * <p>The frame is a line because the documented protocol is line-oriented (a program pipes chunks
 * or commands at it with {@code nc}/{@code socat}). {@link #pollLine()} never waits: it reads
 * whatever bytes are available, returns the next complete line if there is one, and keeps the
 * remainder buffered for the next poll. Both {@code \n} and {@code \r\n} end a line, so a Windows
 * client needs no special casing.
 *
 * <p>The buffer is capped ({@link #MAX_LINE_BYTES}): a peer that never sends a newline can't grow
 * it without bound, and the line it was building is discarded with a {@link LiveException} the
 * caller can report back over the same socket.
 */
public final class LiveConnection implements AutoCloseable {

  /** Longest line accepted. Chunks and commands are small; anything near this is a bug or abuse. */
  public static final int MAX_LINE_BYTES = 1 << 20;

  private final SocketChannel channel;
  private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
  private final ByteBuffer readBuffer = ByteBuffer.allocate(8192);

  private boolean open = true;

  LiveConnection(SocketChannel channel) throws IOException {
    this.channel = channel;
    this.channel.configureBlocking(false);
  }

  /** One poll: the next complete line (without its terminator), or {@code null} if none yet. */
  public String pollLine() {
    if (!open) {
      return null;
    }
    drainFromSocket();
    String line = takeLine();
    if (line != null) {
      return line;
    }
    if (!open) {
      // Peer went away with a partial frame pending: hand the remnant over once rather than lose
      // what it managed to say. An empty buffer means it just closed, and the caller sees null.
      String remnant = pending.toString(StandardCharsets.UTF_8);
      pending.reset();
      return remnant.isEmpty() ? null : remnant;
    }
    return null;
  }

  /** Writes {@code text} plus a newline. Returns false when the peer is gone. */
  public boolean send(String text) {
    if (!open) {
      return false;
    }
    ByteBuffer out = ByteBuffer.wrap((text + "\n").getBytes(StandardCharsets.UTF_8));
    try {
      while (out.hasRemaining()) {
        if (channel.write(out) < 0) {
          open = false;
          return false;
        }
      }
      return true;
    } catch (IOException e) {
      open = false;
      return false;
    }
  }

  /** True until the peer closes or the channel is closed from this side. */
  public boolean isOpen() {
    return open && channel.isOpen();
  }

  /** The peer's {@code host:port}, for logging which program attached. */
  public String peer() {
    try {
      return String.valueOf(channel.getRemoteAddress());
    } catch (IOException | RuntimeException e) {
      return "unknown";
    }
  }

  @Override
  public void close() {
    open = false;
    try {
      channel.close();
    } catch (IOException ignored) {
      // The socket is going away either way.
    }
  }

  private void drainFromSocket() {
    try {
      while (true) {
        readBuffer.clear();
        int n = channel.read(readBuffer);
        if (n < 0) {
          open = false;
          return;
        }
        if (n == 0) {
          return;
        }
        pending.write(readBuffer.array(), 0, n);
        if (pending.size() > MAX_LINE_BYTES) {
          pending.reset();
          throw new LiveException("recv: line longer than " + MAX_LINE_BYTES + " bytes");
        }
      }
    } catch (IOException e) {
      open = false;
    }
  }

  /** Removes one complete line from the buffer, or returns null when there is none yet. */
  private String takeLine() {
    byte[] bytes = pending.toByteArray();
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] != '\n') {
        continue;
      }
      int end = i;
      if (end > 0 && bytes[end - 1] == '\r') {
        end--;
      }
      String line = new String(bytes, 0, end, StandardCharsets.UTF_8);
      pending.reset();
      pending.write(bytes, i + 1, bytes.length - (i + 1));
      return line;
    }
    return null;
  }
}
