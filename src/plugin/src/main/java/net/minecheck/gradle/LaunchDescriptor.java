package net.minecheck.gradle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encoding for the launch descriptor a loader's {@code devHelper*Launch} task writes and {@link
 * MinecheckLifecycleTest} reads back.
 *
 * <p>The descriptor is a {@code key=value} file, one field per line. Fields holding a list ({@code
 * args}, {@code jvmArgs}, {@code systemProperties}) join their entries with {@link
 * #LIST_SEPARATOR}; the {@code environment} field joins {@code KEY=VALUE} entries with {@link
 * #ENV_SEPARATOR}, and carries only the variables the launched process would not inherit (see
 * {@link #encodeEnvironment(Map)}).
 *
 * <p>Both separators are control characters that cannot occur in the values themselves — a NUL
 * cannot appear in a file path or a process argument on any supported platform, and JVM/system
 * arguments are NUL-terminated at the OS boundary. That is why there is no escaping: an earlier
 * version joined on {@code ||} and tried to escape embedded occurrences, but the escape never
 * round-tripped. The writer emitted {@code \\||} while the reader looked for {@code \||}
 * <em>after</em> splitting on {@code ||}, so an element containing the separator was both split
 * apart and left with leaked backslashes. No realistic classpath entry or JVM argument contains
 * {@code ||}, so nothing broke in practice, but the encoding was wrong in principle. A separator
 * that cannot occur removes the whole class of bug rather than fixing one instance of it.
 */
public final class LaunchDescriptor {

  /**
   * Joins the list-valued fields ({@code args}, {@code jvmArgs}, {@code systemProperties}).
   *
   * <p>NUL is not legal in a path on any supported platform, nor in an argv/JVM-argument string
   * once it reaches the OS, so a value can never contain it.
   */
  public static final char LIST_SEPARATOR = '\u0000';

  /** Joins the {@code KEY=VALUE} entries of the {@code environment} field. */
  public static final char ENV_SEPARATOR = '\u0001';

  private LaunchDescriptor() {}

  /** Joins {@code entries} into one list-field value (see {@link #LIST_SEPARATOR}). */
  public static String joinList(List<String> entries) {
    return join(entries, LIST_SEPARATOR);
  }

  /**
   * Splits a {@link #LIST_SEPARATOR}-joined list field back into its entries.
   *
   * <p>A {@code null} or empty value is an empty list, matching how an absent field reads. Interior
   * empty entries are preserved; a trailing empty entry is not, because it cannot be told apart
   * from the value simply ending — which is also what the writer produces for such a list.
   */
  public static List<String> splitList(String value) {
    return split(value, LIST_SEPARATOR);
  }

  /**
   * Joins {@code entries} into the {@code environment} field value (see {@link #ENV_SEPARATOR}).
   */
  public static String joinEnvironment(List<String> entries) {
    return join(entries, ENV_SEPARATOR);
  }

  /**
   * Encodes a run's configured environment into the {@code environment} field, using this process's
   * environment as the inherited set.
   */
  public static String encodeEnvironment(Map<String, String> configured) {
    return encodeEnvironment(configured, System.getenv());
  }

  /**
   * Encodes a run's configured environment into the {@code environment} field, keeping only the
   * variables that are <em>not</em> already what the launching process would inherit.
   *
   * <p>Gradle pre-fills {@code JavaExec.environment} with the whole environment of the Gradle
   * process, and the lifecycle task's {@link ProcessBuilder} starts from that same process's
   * environment. Writing the pre-filled entries into the descriptor is therefore both redundant and
   * a leak: the descriptor lives in {@code <loader>/build/minecheck/}, so every exported CI token
   * or cloud credential in the shell ended up in a build artifact (git-ignored, but still an
   * artifact). Only entries that differ from — or are absent in — the inherited environment need to
   * travel through the descriptor, because those are the ones the child would not otherwise see. An
   * explicitly-set variable that happens to equal the inherited one is dropped too, which is
   * harmless: the child inherits the same value anyway.
   *
   * @param configured the run's environment (Gradle's {@code task.environment}, placeholders
   *     already resolved)
   * @param inherited the environment the launched process inherits (normally {@link
   *     System#getenv()})
   */
  public static String encodeEnvironment(
      Map<String, String> configured, Map<String, String> inherited) {
    List<String> entries = new ArrayList<>();
    for (Map.Entry<String, String> e : configured.entrySet()) {
      if (!Objects.equals(inherited.get(e.getKey()), e.getValue())) {
        entries.add(e.getKey() + "=" + e.getValue());
      }
    }
    return joinEnvironment(entries);
  }

  /**
   * Parses the {@code environment} field into an ordered {@code name -> value} map, splitting each
   * entry on its <em>first</em> {@code =} so a value may itself contain {@code =}.
   */
  public static Map<String, String> parseEnvironment(String value) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String entry : split(value, ENV_SEPARATOR)) {
      int eq = entry.indexOf('=');
      if (eq > 0) {
        out.put(entry.substring(0, eq), entry.substring(eq + 1));
      }
    }
    return out;
  }

  private static String join(List<String> entries, char separator) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < entries.size(); i++) {
      if (i > 0) {
        sb.append(separator);
      }
      sb.append(entries.get(i));
    }
    return sb.toString();
  }

  private static List<String> split(String value, char separator) {
    if (value == null || value.isEmpty()) {
      return List.of();
    }
    ArrayList<String> out = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) == separator) {
        out.add(value.substring(start, i));
        start = i + 1;
      }
    }
    if (start < value.length()) {
      out.add(value.substring(start));
    }
    return List.copyOf(out);
  }
}
