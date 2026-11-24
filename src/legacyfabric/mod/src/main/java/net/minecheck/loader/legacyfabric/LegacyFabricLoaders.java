package net.minecheck.loader.legacyfabric;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Reaches the Fabric loader without a static interface method call.
 *
 * <p>{@code FabricLoader.getInstance()} is a static method declared on an interface. This line's
 * downgrade turns that call into one on a generated {@code FabricLoader$jvmdg$StaticDefaults}
 * companion, and the downgrader only emits that companion for interfaces it also rewrites — one
 * from another jar (the loader's) gets no companion, so the entrypoint died at startup with {@code
 * NoClassDefFoundError: net/fabricmc/loader/api/FabricLoader$jvmdg$StaticDefaults}.
 *
 * <p>Asking {@code Class} for the method keeps our bytecode free of the instruction that needs a
 * companion, so the downgrade has nothing to rewrite. The reflection runs on a Java 8 runtime —
 * this loader's smallest JVM — where invoking an interface's static method is ordinary.
 */
final class LegacyFabricLoaders {

  private LegacyFabricLoaders() {}

  /** The process's loader instance. */
  static FabricLoader get() {
    try {
      Method method = FabricLoader.class.getMethod("getInstance");
      return (FabricLoader) method.invoke(null);
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new IllegalStateException("the Fabric loader instance is not reachable", e);
    }
  }
}
