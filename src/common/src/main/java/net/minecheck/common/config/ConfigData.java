package net.minecheck.common.config;

/**
 * Contents of config/minecheck/config.json.
 *
 * @param script single-phase boot script file name; empty = passive
 * @param coordinator optional coordinator file that declares the order of phases (long-lived
 *     session; wins over {@code script} when set)
 * @param failMode "exit" (default): stop client on script failure; "continue": log and keep going
 * @param defaultTimeout default timeout in seconds for waits/joins
 */
public record ConfigData(String script, String coordinator, String failMode, int defaultTimeout) {

  public static ConfigData defaults() {
    return new ConfigData("", null, "exit", 30);
  }

  public boolean exitOnFail() {
    return !"continue".equalsIgnoreCase(failMode);
  }

  public String entryScript() {
    return (coordinator != null && !coordinator.isBlank()) ? coordinator : script;
  }

  public boolean isCoordinator() {
    return coordinator != null && !coordinator.isBlank();
  }
}
