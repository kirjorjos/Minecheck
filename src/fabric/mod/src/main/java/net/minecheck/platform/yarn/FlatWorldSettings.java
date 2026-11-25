package net.minecheck.platform.yarn;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorLayer;
import net.minecraft.world.gen.feature.PlacedFeature;

/**
 * The flat world every Minecheck suite runs in, built from the declaration the harness also hands
 * the dedicated server.
 *
 * <p>{@code tests/flat-world.json} is the one description of the world's layers and biome: it is
 * bundled into this mod, parsed here for the client worlds a script creates, and written verbatim
 * into the server's {@code generator-settings}. Both sides therefore build the same surface, so the
 * fixed coordinates and surface heights the fixtures assume cannot drift from the world they run
 * in.
 */
final class FlatWorldSettings {

  private static final String RESOURCE = "/flat-world.json";

  private FlatWorldSettings() {}

  /**
   * One block layer and how many blocks thick it is, counted up from the bottom of the world.
   *
   * <p>Plain fields rather than a record: the Gson bundled with this Minecraft version predates
   * record support and fails to bind their final fields.
   */
  private static final class Layer {
    private String block;
    private int height;
  }

  /** The declaration as written in {@code tests/flat-world.json}. */
  private static final class Declaration {
    private List<Layer> layers;
    private String biome;
  }

  private static Declaration declaration() {
    try (InputStream in = FlatWorldSettings.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the mod's resources");
      }
      Declaration declared =
          new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Declaration.class);
      if (declared == null || declared.layers == null || declared.layers.isEmpty()) {
        throw new IllegalStateException(RESOURCE + " declares no layers");
      }
      return declared;
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + RESOURCE, e);
    }
  }

  /** The declared world, laid over the preset this version would build so nothing else shifts. */
  static FlatChunkGeneratorConfig config(
      RegistryEntryLookup<Biome> biomes,
      RegistryEntryLookup<StructureSet> structureSets,
      RegistryEntryLookup<PlacedFeature> placedFeatures) {
    Declaration declared = declaration();
    List<FlatChunkGeneratorLayer> layers = new ArrayList<>();
    for (Layer layer : declared.layers) {
      Block block = Registries.BLOCK.get(new Identifier(layer.block));
      layers.add(new FlatChunkGeneratorLayer(layer.height, block));
    }
    FlatChunkGeneratorConfig base =
        FlatChunkGeneratorConfig.getDefaultConfig(biomes, structureSets, placedFeatures);
    // A registry-backed entry, not RegistryEntry.of: a direct holder is not a registry element,
    // and the game refuses to write a level whose flat settings name one.
    RegistryEntry<Biome> biome =
        biomes
            .getOptional(RegistryKey.of(RegistryKeys.BIOME, new Identifier(declared.biome)))
            .orElseGet(base::getBiome);
    // No structure overrides: the declaration describes bare ground, and the suites place the
    // structures they assert on themselves.
    return base.with(layers, Optional.empty(), biome);
  }
}
