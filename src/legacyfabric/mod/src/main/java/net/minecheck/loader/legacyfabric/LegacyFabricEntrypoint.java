package net.minecheck.loader.legacyfabric;

import java.util.logging.Logger;
import net.fabricmc.api.ModInitializer;

/**
 * Legacy Fabric entrypoint for the 1.12.2 line.
 *
 * <p>Fabric's own loader starts at 1.14, so this line is covered by Legacy Fabric instead, which
 * loads the same {@code fabric.mod.json} shape against 1.12.2's classes. The platform services are
 * wired here the way {@code FabricEntrypoint} wires the modern line's, once the 1.12.2 platform
 * classes exist: this line speaks MCP-free legacy Yarn names, so its platform classes are their own
 * implementation rather than a re-use of {@code net.minecheck.platform.yarn}, which is written
 * against 1.14+ API.
 *
 * <p>Until that wiring lands, the entrypoint only proves the module loads: a run that reaches this
 * line has resolved the loader, the intermediaries and the mappings for 1.12.2.
 */
public final class LegacyFabricEntrypoint implements ModInitializer {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  @Override
  public void onInitialize() {
    LOG.info("Minecheck: Legacy Fabric entrypoint loaded for 1.12.2");
  }
}
