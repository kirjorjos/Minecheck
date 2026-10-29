package net.minecheck.common.live;

/**
 * A live-socket failure the script can act on: the port is taken, the peer vanished, a line overran
 * the cap. Runtime, because every call site is inside a Lua action that turns it into a {@code
 * LuaError} with the action's name prefixed — the same shape as the rest of the script surface.
 */
public final class LiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public LiveException(String message) {
    super(message);
  }

  public LiveException(String message, Throwable cause) {
    super(message, cause);
  }
}
