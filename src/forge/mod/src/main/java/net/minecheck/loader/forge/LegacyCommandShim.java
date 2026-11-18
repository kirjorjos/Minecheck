package net.minecheck.loader.forge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Answers the parts of the newer command syntax this line cannot execute.
 *
 * <p>{@code /execute if block} is 1.13 syntax: 1.12.2's {@code /execute} takes a selector and a
 * position and knows nothing about {@code if}, so the command is parsed as {@code execute} with an
 * entity named {@code if} and answers "Entity 'if' cannot be found" — a zero that looks like a
 * failed condition rather than an unsupported command. Every fixture uses this form to ask "is that
 * block there", which 1.12.2 can answer directly, so it is answered here instead.
 *
 * <p>Only the block form is translated. Selector forms ({@code execute if entity ...}) still go to
 * the command manager unchanged; they are not used by the suites this line runs yet.
 */
final class LegacyCommandShim {

  private static final Pattern EXECUTE_IF_BLOCK =
      Pattern.compile(
          "^execute\\s+if\\s+block\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+([A-Za-z0-9_.:\\-]+)"
              + "(?:\\s.*)?$");

  private LegacyCommandShim() {}

  /**
   * The result count the command would have reported, or {@code null} when the command is not one
   * this class answers (and should be executed normally).
   */
  static Integer executeIfBlock(World world, String command) {
    Matcher match = EXECUTE_IF_BLOCK.matcher(command.trim());
    if (!match.matches()) {
      return null;
    }
    if (world == null) {
      return 0;
    }
    BlockPos pos =
        new BlockPos(
            Integer.parseInt(match.group(1)),
            Integer.parseInt(match.group(2)),
            Integer.parseInt(match.group(3)));
    Block expected = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(match.group(4)));
    if (expected == null) {
      return 0;
    }
    // An unloaded chunk has no blocks to test: report no match rather than loading it here, which
    // is the same answer the conditional command gives on the lines that support it.
    if (!world.isBlockLoaded(pos)) {
      return 0;
    }
    IBlockState state = world.getBlockState(pos);
    return state.getBlock() == expected ? 1 : 0;
  }
}
