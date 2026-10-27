package net.minecheck.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the launch-descriptor encoding that the loader build scripts write and the lifecycle task
 * reads back. The {@code ||} case is the regression: the old code split on {@code ||} and then
 * tried to unescape {@code \||}, so an element that contained the separator came back split and
 * with leaked backslashes.
 */
class LaunchDescriptorTest {

  @Test
  void roundTripsAPlainList() {
    List<String> in = List.of("--width", "854", "--height", "480");
    assertEquals(in, LaunchDescriptor.splitList(LaunchDescriptor.joinList(in)));
  }

  @Test
  void roundTripsAnElementContainingTheOldSeparator() {
    // The exact shape that used to break: the element itself contains "||".
    List<String> in = List.of("-Dx=a||b", "plain");
    List<String> out = LaunchDescriptor.splitList(LaunchDescriptor.joinList(in));
    assertEquals(in, out);
    assertEquals("-Dx=a||b", out.get(0), "an element containing || must survive intact");
  }

  @Test
  void roundTripsAnElementContainingABackslash() {
    List<String> in = List.of("C:\\games\\run", "-Dpa\\th=x");
    assertEquals(in, LaunchDescriptor.splitList(LaunchDescriptor.joinList(in)));
  }

  @Test
  void preservesInteriorEmptyEntries() {
    assertEquals(
        List.of("a", "", "c"),
        LaunchDescriptor.splitList(LaunchDescriptor.joinList(List.of("a", "", "c"))));
  }

  @Test
  void absentOrEmptyValueReadsAsNoEntries() {
    assertTrue(LaunchDescriptor.splitList(null).isEmpty());
    assertTrue(LaunchDescriptor.splitList("").isEmpty());
  }

  @Test
  void singleEntryNeedsNoSeparator() {
    assertEquals(
        List.of("--nogui"),
        LaunchDescriptor.splitList(LaunchDescriptor.joinList(List.of("--nogui"))));
  }

  @Test
  void environmentRoundTripsAndKeepsEqualsInValues() {
    List<String> in = List.of("LD_LIBRARY_PATH=/a/b", "MESA_GL_VERSION_OVERRIDE=3.2=extra");
    String encoded = LaunchDescriptor.joinEnvironment(in);
    Map<String, String> parsed = LaunchDescriptor.parseEnvironment(encoded);
    assertEquals(2, parsed.size(), "both entries must survive: " + parsed);
    assertEquals("/a/b", parsed.get("LD_LIBRARY_PATH"));
    assertEquals("3.2=extra", parsed.get("MESA_GL_VERSION_OVERRIDE"), "value may contain =");
  }

  @Test
  void environmentSeparatorIsTheControlCharacterNotItsText() {
    // The other half of the old bug: the reader split on the six-character string "\u0001"
    // rather than on U+0001, so a multi-entry environment collapsed to one bogus entry.
    String encoded = "A=1" + LaunchDescriptor.ENV_SEPARATOR + "B=2";
    assertEquals(Map.of("A", "1", "B", "2"), LaunchDescriptor.parseEnvironment(encoded));
  }

  @Test
  void absentEnvironmentReadsAsEmpty() {
    assertTrue(LaunchDescriptor.parseEnvironment(null).isEmpty());
    assertTrue(LaunchDescriptor.parseEnvironment("").isEmpty());
  }

  @Test
  void inheritedEnvironmentIsNotWrittenToTheDescriptor() {
    // Gradle pre-fills task.environment with the whole Gradle-process environment, but the
    // launched child inherits that same environment, so writing it down is redundant — and it
    // used to put every exported shell secret into <loader>/build/minecheck/*-launch.txt.
    Map<String, String> inherited =
        Map.of("PATH", "/usr/bin", "GITHUB_TOKEN", "ghp_secret", "HOME", "/home/dev");
    Map<String, String> configured =
        Map.of("PATH", "/usr/bin", "GITHUB_TOKEN", "ghp_secret", "HOME", "/home/dev");

    String encoded = LaunchDescriptor.encodeEnvironment(configured, inherited);

    assertEquals("", encoded, "nothing here differs from what the child inherits");
    assertTrue(!encoded.contains("ghp_secret"), "a secret must not reach the descriptor");
    assertTrue(LaunchDescriptor.parseEnvironment(encoded).isEmpty());
  }

  @Test
  void onlyVariablesThatDifferFromTheInheritedSetAreWritten() {
    Map<String, String> inherited = Map.of("PATH", "/usr/bin", "LANG", "C");
    Map<String, String> configured =
        Map.of("PATH", "/usr/bin", "LANG", "en_US.UTF-8", "MESA_GL_VERSION_OVERRIDE", "3.2");

    String encoded = LaunchDescriptor.encodeEnvironment(configured, inherited);
    Map<String, String> parsed = LaunchDescriptor.parseEnvironment(encoded);

    assertEquals(2, parsed.size(), "only the overridden and the added variable: " + parsed);
    assertEquals("en_US.UTF-8", parsed.get("LANG"), "an override must survive");
    assertEquals("3.2", parsed.get("MESA_GL_VERSION_OVERRIDE"), "an addition must survive");
    assertTrue(!parsed.containsKey("PATH"), "an unchanged inherited variable is dropped");
  }

  @Test
  void anEmptyInheritedSetWritesEverything() {
    Map<String, String> configured = Map.of("A", "1", "B", "2");
    assertEquals(
        Map.of("A", "1", "B", "2"),
        LaunchDescriptor.parseEnvironment(
            LaunchDescriptor.encodeEnvironment(configured, Map.of())));
  }
}
