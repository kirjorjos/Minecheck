package net.minecheck.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Simple Voice Chat staging: the listener must follow the player, and nothing else in the
 * human's client config may move.
 *
 * <p>The file belongs to another mod, which writes it on its first client boot with the device and
 * keybind choices a human made. A test run may set one key and must leave every other line exactly
 * where it was, including comments and ordering — so the merge is line-based and the assertions
 * below are on whole files, not on the presence of the key.
 */
class VoiceChatConfigTest {

  private static String merge(String text) {
    return MinecheckLifecycleTest.mergeVoiceChatProperty(text, "freecam_mode", "PLAYER");
  }

  @Test
  void addsTheKeyToAFileThatHasNoSuchKey() {
    assertEquals("freecam_mode=PLAYER\n", merge(""));
  }

  @Test
  void rewritesTheKeyInPlaceAndKeepsEverythingElse() {
    String in =
        "# voice chat client settings\n"
            + "enabled=true\n"
            + "freecam_mode=CAMERA\n"
            + "mic_amplification=1.0\n";
    assertEquals(
        "# voice chat client settings\n"
            + "enabled=true\n"
            + "freecam_mode=PLAYER\n"
            + "mic_amplification=1.0\n",
        merge(in));
  }

  @Test
  void appendsTheKeyWhenOnlyOtherSettingsExist() {
    assertEquals(
        "enabled=true\nmic_amplification=1.0\nfreecam_mode=PLAYER\n",
        merge("enabled=true\nmic_amplification=1.0\n"));
  }

  @Test
  void acceptsSpacesAroundTheSeparator() {
    assertEquals("freecam_mode=PLAYER\nother=1\n", merge("freecam_mode = CAMERA\nother=1\n"));
  }

  @Test
  void isIdempotent() {
    String once = merge("enabled=true\n");
    assertEquals(once, merge(once), "a second run must not grow the file");
  }
}
