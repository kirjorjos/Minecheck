package net.minecheck.common.screenshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class DigestsTest {

  @Test
  void hashesTheKnownVector() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        Digests.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  void hashesPixelsAsBigEndianBytes() {
    assertEquals(
        Digests.sha256(new byte[] {0x01, 0x02, 0x03, 0x04, (byte) 0xFF, 0x00, 0x00, 0x00}),
        Digests.sha256(new int[] {0x01020304, 0xFF000000}));
  }

  @Test
  void roundTripsPackedPixels() {
    int[] pixels = {0x11223344, 0x55667788, 0x00000000, 0xFFFFFFFF};
    assertArrayEquals(pixels, Digests.unpack(Digests.pack(pixels)));
  }

  @Test
  void refusesBytesThatAreNotWholePixels() {
    assertThrows(IllegalArgumentException.class, () -> Digests.unpack(new byte[] {1, 2, 3}));
  }
}
