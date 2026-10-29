package net.minecheck.common.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Loopback tests for the live socket layer: framing, the non-blocking poll contract, and close
 * semantics. These run against a real socket on 127.0.0.1 rather than a mock, because the thing
 * that has to hold is exactly how the OS splits and coalesces the bytes.
 */
class LiveSessionTest {

  private static LiveConnection acceptWithin(LiveListener listener, long millis) throws Exception {
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      LiveConnection conn = listener.poll();
      if (conn != null) {
        return conn;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no connection accepted within " + millis + " ms");
  }

  private static String lineWithin(LiveConnection conn, long millis) throws Exception {
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      String line = conn.pollLine();
      if (line != null) {
        return line;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no line received within " + millis + " ms");
  }

  private static String readLine(BufferedReader in, long millis) throws Exception {
    String line = in.readLine();
    assertNotNull(line, "client read timed out after " + millis + " ms");
    return line;
  }

  @Test
  @Timeout(30)
  void ephemeralPortIsReportedAndAcceptsOneClient() throws Exception {
    try (LiveListener listener = LiveListener.open(null, 0)) {
      assertTrue(listener.port() > 0, "ephemeral port was not reported");
      assertEquals("127.0.0.1", listener.host());
      assertNull(listener.poll(), "poll must return null when nobody is connected");

      try (Socket client = new Socket()) {
        client.connect(new InetSocketAddress("127.0.0.1", listener.port()), 5000);
        try (LiveConnection conn = acceptWithin(listener, 5000)) {
          assertTrue(conn.isOpen());
          assertTrue(conn.peer().contains(":"), "peer should be host:port, was " + conn.peer());
        }
      }
    }
  }

  @Test
  @Timeout(30)
  void linesAreFramedIndependentlyOfHowTheBytesArrive() throws Exception {
    try (LiveListener listener = LiveListener.open(null, 0);
        Socket client = new Socket()) {
      client.connect(new InetSocketAddress("127.0.0.1", listener.port()), 5000);
      client.setTcpNoDelay(true);
      OutputStream out = client.getOutputStream();
      try (LiveConnection conn = acceptWithin(listener, 5000)) {
        // Two frames in one write, then a frame split across two writes with a CRLF terminator.
        out.write("first\nsecond\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        assertEquals("first", lineWithin(conn, 5000));
        assertEquals("second", lineWithin(conn, 5000));

        out.write("thi".getBytes(StandardCharsets.UTF_8));
        out.flush();
        assertNull(conn.pollLine(), "a partial frame must not be handed over");
        out.write("rd\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        assertEquals("third", lineWithin(conn, 5000), "CRLF must frame like LF");
      }
    }
  }

  @Test
  @Timeout(30)
  void sendWritesOneNewlineTerminatedFrame() throws Exception {
    try (LiveListener listener = LiveListener.open(null, 0);
        Socket client = new Socket()) {
      client.connect(new InetSocketAddress("127.0.0.1", listener.port()), 5000);
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
      try (LiveConnection conn = acceptWithin(listener, 5000)) {
        assertTrue(conn.send("ok 2"));
        assertEquals("ok 2", readLine(in, 5000));
      }
    }
  }

  @Test
  @Timeout(30)
  void peerCloseEndsTheConnection() throws Exception {
    try (LiveListener listener = LiveListener.open(null, 0)) {
      Socket client = new Socket();
      client.connect(new InetSocketAddress("127.0.0.1", listener.port()), 5000);
      LiveConnection conn = acceptWithin(listener, 5000);
      client.getOutputStream().write("bye\n".getBytes(StandardCharsets.UTF_8));
      client.getOutputStream().flush();
      assertEquals("bye", lineWithin(conn, 5000));

      client.close();
      long deadline = System.currentTimeMillis() + 5000;
      while (conn.isOpen() && System.currentTimeMillis() < deadline) {
        conn.pollLine();
        Thread.sleep(20);
      }
      assertFalse(conn.isOpen(), "connection should report closed after the peer leaves");
      assertNull(conn.pollLine());
      assertFalse(conn.send("gone"), "send must fail on a closed connection");
      conn.close();
    }
  }

  @Test
  @Timeout(30)
  void aLineOverTheCapIsRejectedRatherThanBuffered() throws Exception {
    try (LiveListener listener = LiveListener.open(null, 0);
        Socket client = new Socket()) {
      client.connect(new InetSocketAddress("127.0.0.1", listener.port()), 5000);
      try (LiveConnection conn = acceptWithin(listener, 5000)) {
        byte[] chunk = new byte[8192];
        java.util.Arrays.fill(chunk, (byte) 'x');
        OutputStream out = client.getOutputStream();
        int written = 0;
        LiveException thrown = null;
        try {
          while (written <= LiveConnection.MAX_LINE_BYTES + chunk.length) {
            out.write(chunk);
            out.flush();
            written += chunk.length;
            conn.pollLine();
          }
        } catch (LiveException e) {
          thrown = e;
        }
        assertNotNull(thrown, "an over-long line must raise instead of growing the buffer");
        assertTrue(thrown.getMessage().contains("line longer"), thrown.getMessage());
      }
    }
  }

  @Test
  @Timeout(30)
  void aBusyPortFailsToBind() throws Exception {
    try (LiveListener first = LiveListener.open(null, 0)) {
      LiveException e =
          assertThrows(LiveException.class, () -> LiveListener.open(null, first.port()));
      assertTrue(e.getMessage().contains("cannot listen"), e.getMessage());
    }
  }

  @Test
  @Timeout(30)
  void closeIsIdempotentAndEndsPolling() throws Exception {
    LiveListener listener = LiveListener.open(null, 0);
    int port = listener.port();
    listener.close();
    listener.close();
    assertFalse(listener.isOpen());
    assertNull(listener.poll());

    // The port is free again: the engine closes handles when a script ends, and the next run has to
    // be able to bind the same number.
    try (LiveListener again = LiveListener.open(null, port)) {
      assertEquals(port, again.port());
    }
  }
}
