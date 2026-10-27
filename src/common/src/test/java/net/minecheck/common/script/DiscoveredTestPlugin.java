package net.minecheck.common.script;

import net.minecheck.platform.api.plugin.MinecheckPlugin;
import net.minecheck.platform.api.plugin.MinecheckRegistry;
import net.minecheck.platform.api.plugin.PluginStep;

/**
 * Test double reachable only through {@code META-INF/services} — it exists so {@link
 * PluginRegistry#discover()} is exercised against a real provider file rather than a stubbed
 * ServiceLoader.
 */
public final class DiscoveredTestPlugin implements MinecheckPlugin {

  /** How many times the discovered action has run, so a test can prove discovery reached Lua. */
  public static int calls;

  @Override
  public String id() {
    return "discovered";
  }

  @Override
  public void register(MinecheckRegistry registry) {
    registry.action(
        "discovered_add",
        call -> {
          calls++;
          return PluginStep.done(call.requireNumber(1) + call.requireNumber(2));
        });
  }
}
