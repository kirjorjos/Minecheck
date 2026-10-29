package net.minecheck.common.live;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.logging.Logger;

/**
 * A script-owned TCP listener on the loopback interface, for driving a running game from a program
 * on the same machine (see {@code minecheck.listen} / {@code minecheck.liveAttach}).
 *
 * <p><b>Non-blocking on purpose.</b> Every method is a single poll with no wait: the Lua actions
 * that expose it suspend the script coroutine and poll once per client tick, exactly the way {@code
 * waitFor} does. A listener nobody connects to — or a client that connects and then goes quiet —
 * therefore costs one {@code accept}/{@code read} syscall per tick and never stalls the game.
 *
 * <p><b>Loopback only.</b> The default host is {@code 127.0.0.1}; this is a debugging channel for a
 * program on the same desktop, not a network service, and a script that wants to bind anything else
 * has to say so explicitly.
 *
 * <p><b>Closes with the run.</b> The engine registers every listener and connection it hands to a
 * script ({@link net.minecheck.common.script.ScriptEngine#registerLive}) and closes them when the
 * script finishes or fails, so a run cannot leave a port bound.
 */
public final class LiveListener implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** The host a listener binds unless the script names another one. */
  public static final String DEFAULT_HOST = "127.0.0.1";

  /**
   * Backlog for pending connects. One client at a time is the documented model, so this only has to
   * be big enough that a second program gets a connection instead of a reset while it waits.
   */
  private static final int BACKLOG = 8;

  private final ServerSocketChannel channel;

  private LiveListener(ServerSocketChannel channel) {
    this.channel = channel;
  }

  /**
   * Binds {@code host:port} (ephemeral port when {@code port == 0}) and logs where it listens: with
   * an ephemeral port the log line is the only way a program can find it, so the line is the
   * contract, not a convenience.
   *
   * @throws LiveException when the address cannot be bound (port in use, unknown host)
   */
  public static LiveListener open(String host, int port) {
    String bindHost = host == null || host.isBlank() ? DEFAULT_HOST : host;
    if (port < 0 || port > 65535) {
      throw new LiveException("port out of range: " + port);
    }
    ServerSocketChannel ch = null;
    try {
      ch = ServerSocketChannel.open();
      ch.configureBlocking(false);
      ch.bind(new InetSocketAddress(bindHost, port), BACKLOG);
      LiveListener listener = new LiveListener(ch);
      LOG.info(
          String.format("Minecheck: live listening on %s:%d", listener.host(), listener.port()));
      return listener;
    } catch (IOException | RuntimeException e) {
      closeQuietly(ch);
      throw new LiveException(
          String.format("cannot listen on %s:%d: %s", bindHost, port, reason(e)), e);
    }
  }

  /** The bound port — the real one, so {@code listen(0)} reports what the OS chose. */
  public int port() {
    try {
      return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    } catch (IOException | RuntimeException e) {
      throw new LiveException("cannot read the bound port: " + reason(e), e);
    }
  }

  /** The bound host, as reported in the listen log line. */
  public String host() {
    try {
      return ((InetSocketAddress) channel.getLocalAddress()).getAddress().getHostAddress();
    } catch (IOException | RuntimeException e) {
      throw new LiveException("cannot read the bound address: " + reason(e), e);
    }
  }

  public boolean isOpen() {
    return channel.isOpen();
  }

  /**
   * One poll: the next connection, or {@code null} when none is pending. Polling instead of waiting
   * is what lets the caller stay inside the tick loop.
   */
  public LiveConnection poll() {
    if (!channel.isOpen()) {
      return null;
    }
    try {
      SocketChannel accepted = channel.accept();
      return accepted == null ? null : new LiveConnection(accepted);
    } catch (IOException e) {
      throw new LiveException("accept failed: " + reason(e), e);
    }
  }

  @Override
  public void close() {
    closeQuietly(channel);
  }

  private static void closeQuietly(java.nio.channels.Channel c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (IOException ignored) {
      // Nothing left to do: the socket is going away either way.
    }
  }

  private static String reason(Exception e) {
    return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
  }
}
