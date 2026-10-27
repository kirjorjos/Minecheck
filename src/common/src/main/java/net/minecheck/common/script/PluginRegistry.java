package net.minecheck.common.script;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecheck.platform.api.plugin.MinecheckPlugin;
import net.minecheck.platform.api.plugin.MinecheckRegistry;
import net.minecheck.platform.api.plugin.PluginAction;
import net.minecheck.platform.api.plugin.PluginWaitCondition;

/**
 * Collects the actions and wait conditions that third-party plugins register, and discovers the
 * plugins themselves with {@link ServiceLoader} (see {@link MinecheckPlugin}).
 *
 * <p>Discovery is best-effort and never fatal: a plugin that cannot be loaded or that throws while
 * registering is logged and skipped, with any partial registrations rolled back, so one broken
 * third-party mod cannot break a harness run.
 */
public final class PluginRegistry implements MinecheckRegistry {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** A script action, tagged with the plugin that registered it. */
  public record RegisteredAction(String pluginId, String name, PluginAction action) {}

  /** A wait condition, tagged with the plugin that registered it. */
  public record RegisteredWait(String pluginId, String name, PluginWaitCondition condition) {}

  private final List<RegisteredAction> actions = new ArrayList<>();
  private final List<RegisteredWait> waits = new ArrayList<>();

  /** Every name claimed so far; actions and wait conditions share one script namespace. */
  private final Set<String> names = new LinkedHashSet<>();

  /** Which side this run is, so plugins can register per-side (see {@link #dedicatedServer()}). */
  private final boolean dedicatedServer;

  private String currentPlugin;

  /**
   * @param dedicatedServer true on a dedicated server, false on a client — the composition root
   *     takes it from {@code PlatformServices.dedicatedServer()}
   */
  public PluginRegistry(boolean dedicatedServer) {
    this.dedicatedServer = dedicatedServer;
  }

  @Override
  public boolean dedicatedServer() {
    return dedicatedServer;
  }

  public List<RegisteredAction> actions() {
    return List.copyOf(actions);
  }

  public List<RegisteredWait> waits() {
    return List.copyOf(waits);
  }

  @Override
  public void action(String name, PluginAction action) {
    checkName(name);
    if (action == null) {
      throw new IllegalArgumentException("plugin action '" + name + "' has no implementation");
    }
    actions.add(new RegisteredAction(currentPlugin, name, action));
  }

  @Override
  public void waitCondition(String name, PluginWaitCondition condition) {
    checkName(name);
    if (condition == null) {
      throw new IllegalArgumentException("plugin wait condition '" + name + "' has no predicate");
    }
    waits.add(new RegisteredWait(currentPlugin, name, condition));
  }

  /**
   * Runs one plugin's registration, rolling the registry back if it throws.
   *
   * @return true when the plugin registered cleanly
   */
  public boolean register(MinecheckPlugin plugin) {
    int actionMark = actions.size();
    int waitMark = waits.size();
    Set<String> nameMark = new LinkedHashSet<>(names);
    currentPlugin = plugin.id();
    try {
      plugin.register(this);
      return true;
    } catch (RuntimeException e) {
      actions.subList(actionMark, actions.size()).clear();
      waits.subList(waitMark, waits.size()).clear();
      names.clear();
      names.addAll(nameMark);
      LOG.log(
          Level.SEVERE,
          String.format(
              "Minecheck: plugin '%s' failed to register and was skipped (%s)",
              plugin.id(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
      return false;
    } finally {
      currentPlugin = null;
    }
  }

  private void checkName(String name) {
    if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException(
          "plugin name '" + name + "' is not a valid Lua identifier (letters, digits, _)");
    }
    if (!names.add(name)) {
      throw new IllegalArgumentException("name '" + name + "' is already registered");
    }
  }

  /**
   * Discovers {@link MinecheckPlugin} implementations on the classpath. Provider files that cannot
   * be read are logged and skipped; the returned list is in discovery order (which is the order
   * their names are checked against each other).
   */
  public static List<MinecheckPlugin> discover() {
    List<MinecheckPlugin> found = new ArrayList<>();
    java.util.Iterator<MinecheckPlugin> it = ServiceLoader.load(MinecheckPlugin.class).iterator();
    while (true) {
      // One bad provider must not hide the ones after it, so step the iterator by hand: after a
      // ServiceConfigurationError it has already advanced past the offending entry.
      try {
        if (!it.hasNext()) {
          break;
        }
        found.add(it.next());
      } catch (ServiceConfigurationError e) {
        LOG.log(
            Level.SEVERE,
            String.format(
                "Minecheck: skipping an unreadable MinecheckPlugin provider (%s)", e.getMessage()));
      }
    }
    return found;
  }
}
