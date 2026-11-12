package net.minecheck.common.screenshot;

/**
 * Lower-case hex SHA-256, the one digest every capture path reports.
 *
 * <p>It lives here rather than in each platform because a capture is compared in three places — the
 * written PNG's bytes, an in-memory frame's pixels, and the {@code sha256} action a script can call
 * — and three implementations of the same digest would eventually disagree.
 */
public final class Digests {

  private Digests() {}

  /** Lower-case hex SHA-256 of {@code bytes}. */
  public static String sha256(byte[] bytes) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }

  /**
   * Lower-case hex SHA-256 of packed pixels — each {@code int} written as four big-endian bytes.
   *
   * <p>An in-memory capture is compared by its pixels rather than by the bytes of a PNG it never
   * encoded. The packing is the platform's own ({@code getColor}/{@code getPixelRGBA}), and all
   * that matters is that the same picture packs the same way on every run of the same client.
   */
  public static String sha256(int[] pixels) {
    return sha256(pack(pixels));
  }

  /** Packs pixels as four big-endian bytes each — the buffer {@code captureFrame} hands to Lua. */
  public static byte[] pack(int[] pixels) {
    byte[] bytes = new byte[pixels.length * 4];
    int at = 0;
    for (int pixel : pixels) {
      bytes[at++] = (byte) (pixel >>> 24);
      bytes[at++] = (byte) (pixel >>> 16);
      bytes[at++] = (byte) (pixel >>> 8);
      bytes[at++] = (byte) pixel;
    }
    return bytes;
  }

  /** Unpacks the bytes {@link #pack} produced, e.g. a frame a script handed back to be written. */
  public static int[] unpack(byte[] bytes) {
    if (bytes.length % 4 != 0) {
      throw new IllegalArgumentException(
          "packed pixels must be a multiple of four bytes, got " + bytes.length);
    }
    int[] pixels = new int[bytes.length / 4];
    for (int i = 0; i < pixels.length; i++) {
      int at = i * 4;
      pixels[i] =
          ((bytes[at] & 0xFF) << 24)
              | ((bytes[at + 1] & 0xFF) << 16)
              | ((bytes[at + 2] & 0xFF) << 8)
              | (bytes[at + 3] & 0xFF);
    }
    return pixels;
  }
}
