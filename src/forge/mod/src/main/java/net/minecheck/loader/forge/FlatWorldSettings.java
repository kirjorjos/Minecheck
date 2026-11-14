package net.minecheck.loader.forge;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

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
  static FlatLevelGeneratorSettings settings(Registry<Biome> biomes) {
    Declaration declared = declaration();
    List<FlatLayerInfo> layers = new ArrayList<>();
    for (Layer layer : declared.layers) {
      Block block = Registry.BLOCK.get(new ResourceLocation(layer.block));
      layers.add(new FlatLayerInfo(layer.height, block));
    }
    FlatLevelGeneratorSettings base = FlatLevelGeneratorSettings.getDefault(biomes);
    FlatLevelGeneratorSettings settings = base.withLayers(layers, base.structureSettings());
    Biome biome = biomes.get(new ResourceLocation(declared.biome));
    if (biome != null) {
      settings.setBiome(() -> biome);
    }
    return settings;
  }
}
