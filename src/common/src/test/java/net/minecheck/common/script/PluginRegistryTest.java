package net.minecheck.common.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;
import net.minecheck.platform.api.plugin.MinecheckPlugin;
import net.minecheck.platform.api.plugin.MinecheckRegistry;
import net.minecheck.platform.api.plugin.PluginStep;
import org.junit.jupiter.api.Test;

/** Registration rules for the third-party plugin surface (see {@link MinecheckPlugin}). */
class PluginRegistryTest {

  /** A plugin whose {@code register} body is supplied inline. */
  private record TestPlugin(String id, Consumer<MinecheckRegistry> body)
      implements MinecheckPlugin {

    @Override
    public void register(MinecheckRegistry registry) {
      body.accept(registry);
    }
  }

  @Test
  void discoveryFindsProvidersDeclaredInServicesFiles() {
    List<MinecheckPlugin> found = PluginRegistry.discover();
    assertTrue(
        found.stream().anyMatch(plugin -> "discovered".equals(plugin.id())),
        "the META-INF/services provider should be discovered: " + found);
  }

  @Test
  void discoveredPluginsRegisterIntoTheRegistry() {
    PluginRegistry registry = new PluginRegistry(false);
    for (MinecheckPlugin plugin : PluginRegistry.discover()) {
      registry.register(plugin);
    }
    assertEquals(
        List.of("discovered_add"),
        registry.actions().stream().map(PluginRegistry.RegisteredAction::name).toList());
  }

  @Test
  void duplicateNamesAreRejectedAndRolledBack() {
    PluginRegistry registry = new PluginRegistry(false);
    boolean ok =
        registry.register(
            new TestPlugin(
                "dupes",
                r -> {
                  r.action("taken", call -> PluginStep.done());
                  r.action("taken", call -> PluginStep.done());
                }));
    assertFalse(ok, "the second registration of the same name must fail");
    assertTrue(registry.actions().isEmpty(), "the first (partial) registration is rolled back");
  }

  @Test
  void actionsAndWaitConditionsShareOneNamespace() {
    PluginRegistry registry = new PluginRegistry(false);
    boolean ok =
        registry.register(
            new TestPlugin(
                "mixed",
                r -> {
                  r.action("shared", call -> PluginStep.done());
                  r.waitCondition("shared", call -> true);
                }));
    assertFalse(ok, "an action and a wait condition cannot claim the same script name");
    assertTrue(registry.actions().isEmpty());
    assertTrue(registry.waits().isEmpty());
  }

  @Test
  void namesMustBeValidLuaIdentifiers() {
    PluginRegistry registry = new PluginRegistry(false);
    assertFalse(
        registry.register(
            new TestPlugin("bad", r -> r.action("not a name", call -> PluginStep.done()))));
    assertFalse(
        registry.register(
            new TestPlugin("bad", r -> r.action("1leading", call -> PluginStep.done()))));
    assertTrue(registry.actions().isEmpty());
  }

  @Test
  void aPluginThatThrowsIsRolledBackAndSkipped() {
    PluginRegistry registry = new PluginRegistry(false);
    boolean ok =
        registry.register(
            new TestPlugin(
                "broken",
                r -> {
                  r.action("kept", call -> PluginStep.done());
                  throw new IllegalStateException("boom");
                }));
    assertFalse(ok, "a throwing plugin is reported as failed");
    assertTrue(registry.actions().isEmpty(), "nothing it registered survives");
  }

  @Test
  void theRegistryReportsWhichSideTheRunIs() {
    assertFalse(new PluginRegistry(false).dedicatedServer(), "a client run");
    assertTrue(new PluginRegistry(true).dedicatedServer(), "a dedicated server run");
  }

  @Test
  void aPluginCanRegisterDifferentNamesPerSide() {
    MinecheckPlugin sideAware =
        new TestPlugin(
            "sided",
            r -> {
              if (r.dedicatedServer()) {
                r.action("server_only", call -> PluginStep.done("s"));
              } else {
                r.action("client_only", call -> PluginStep.done("c"));
              }
            });
    PluginRegistry client = new PluginRegistry(false);
    PluginRegistry server = new PluginRegistry(true);
    assertTrue(client.register(sideAware));
    assertTrue(server.register(sideAware));
    assertEquals(
        List.of("client_only"),
        client.actions().stream().map(PluginRegistry.RegisteredAction::name).toList());
    assertEquals(
        List.of("server_only"),
        server.actions().stream().map(PluginRegistry.RegisteredAction::name).toList());
  }

  @Test
  void registrationsRecordTheirOwningPlugin() {
    PluginRegistry registry = new PluginRegistry(false);
    assertTrue(
        registry.register(
            new TestPlugin(
                "mine",
                r -> {
                  r.action("act", call -> PluginStep.done("x"));
                  r.waitCondition("cond", call -> true);
                })));
    assertEquals("mine", registry.actions().get(0).pluginId());
    assertEquals("mine", registry.waits().get(0).pluginId());
  }
}
