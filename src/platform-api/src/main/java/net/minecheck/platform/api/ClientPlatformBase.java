package net.minecheck.platform.api;

/**
 * The shared implementations {@link ClientPlatform} used to carry as {@code default} methods.
 *
 * <p>An interface can only declare {@code default} methods from class file 52 on, and the 1.4.7
 * line has to stay at 51, so the bodies live in this superclass instead and every client platform
 * extends it. The contract and its documentation stay on the interface.
 */
public abstract class ClientPlatformBase implements ClientPlatform {

  @Override
  public void runOnClientThread(Runnable action) {
    action.run();
  }

  @Override
  public boolean canReachServer(String address) {
    String host = address;
    int port = 25565;
    int colon = address.lastIndexOf(':');
    if (colon > 0) {
      host = address.substring(0, colon);
      try {
        port = Integer.parseInt(address.substring(colon + 1));
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1); // bracketed IPv6 literal
    }
    try (java.net.Socket socket = new java.net.Socket()) {
      socket.connect(new java.net.InetSocketAddress(host, port), 250);
      return true;
    } catch (java.io.IOException | RuntimeException e) {
      return false;
    }
  }

  @Override
  public boolean isConnecting() {
    return false;
  }

  @Override
  public boolean isLoggingIn() {
    return false;
  }

  @Override
  public boolean isConnectFailed() {
    return false;
  }

  @Override
  public boolean isBootComplete() {
    return true;
  }

  @Override
  public float worldLoadProgress() {
    return -1f;
  }

  @Override
  public boolean isWorldGenSettled() {
    return true;
  }

  @Override
  public void pinClientClock() {}

  @Override
  public void pinRenderTickDelta() {}

  @Override
  public void setVanillaCameraRestored(boolean restored) {}

  @Override
  public CameraPlatform camera() {
    return CameraPlatform.NONE;
  }

  @Override
  public void setViewWindow(ViewWindow window) {}

  @Override
  public void pumpViewWindow() {}

  @Override
  public void virtualKey(int glfwKey, boolean down) {}

  @Override
  public void requestScreenshot(ScreenshotRequest request) {
    request.fail("screenshots are not supported by this platform");
  }

  @Override
  public void requestFrame(ScreenshotRequest request) {
    request.fail("in-memory frame capture is not supported by this platform");
  }

  @Override
  public void writeFrame(ScreenshotRequest request) {
    request.fail("writing an in-memory frame is not supported by this platform");
  }
}
