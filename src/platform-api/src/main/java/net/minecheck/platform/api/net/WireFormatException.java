package net.minecheck.platform.api.net;

/** Raised when a packet or a script-supplied byte string is not valid Minecheck wire data. */
public class WireFormatException extends RuntimeException {

  public WireFormatException(String message) {
    super(message);
  }
}
