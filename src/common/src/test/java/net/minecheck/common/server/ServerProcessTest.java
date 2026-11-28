package net.minecheck.common.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerProcessTest {

  @TempDir Path tempDir;

  /** The harness writes one {@code key=value} per line; a value may itself contain {@code =}. */
  @Test
  void readsTheLaunchDescriptorAsTheHarnessWritesIt() throws Exception {
    Path descriptor = tempDir.resolve("server.descriptor");
    Files.writeString(
        descriptor,
        "mainClass=net.fabricmc.devlaunchinjector.Main\n"
            + "classpath=/a.jar:/b.jar\n"
            + "systemProperties=fabric.development=true\n"
            + "args=--nogui\u0000--port\n"
            + "workingDir=/tmp/run\n"
            + "empty=\n",
        StandardCharsets.UTF_8);

    Map<String, String> fields = ServerProcess.parse(descriptor);

    assertEquals("net.fabricmc.devlaunchinjector.Main", fields.get("mainClass"));
    assertEquals("/a.jar:/b.jar", fields.get("classpath"));
    assertEquals(List.of("--nogui", "--port"), ServerProcess.split(fields.get("args")));
    assertEquals("", fields.get("empty"));
    assertNull(fields.get("absent"));
  }

  @Test
  void aListFieldIsNulSeparatedAndAnAbsentOneIsEmpty() {
    assertEquals(List.of("-Xmx2G", "-Dfoo=bar"), ServerProcess.split("-Xmx2G\u0000-Dfoo=bar"));
    assertTrue(ServerProcess.split(null).isEmpty());
    assertTrue(ServerProcess.split("").isEmpty());
  }

  @Test
  void noStagedServerMeansNoProcess() {
    // Nothing is set outside the harness, which is exactly the case of a plain ./gradlew run.
    assertNull(ServerProcess.fromSystemProperties());
  }
}
