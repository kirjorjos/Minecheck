package net.minecheck.loader.forge;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

/**
 * {@code /setblock} and {@code /fill}, which this line has no commands for.
 *
 * <p>1.4.7's command table holds teleport, give, gamerule and the server commands; nothing writes a
 * block. A fixture that builds a scene with {@code fill}/{@code setblock} — every one of the
 * integration suites does — therefore placed nothing at all, and the run failed later at whatever
 * first read the block back. Registering the two as ordinary commands is what makes them work from
 * both directions: a script's own {@code runCommand} (which the platform answers before dispatch)
 * and the client's chat commands, which arrive at this command table and nowhere else.
 *
 * <p>Implemented through {@link LegacyCommandShim}, which owns the name resolution and the world
 * write, so one copy of the semantics serves the script path and this one.
 */
final class LegacyBlockCommands {

  private LegacyBlockCommands() {}

  /** Registers both commands, once per server. Safe to call every tick. */
  static void register(MinecraftServer server) {
    if (server == null) {
      return;
    }
    ICommandManager manager = server.getCommandManager();
    if (!(manager instanceof CommandHandler)) {
      return;
    }
    CommandHandler handler = (CommandHandler) manager;
    if (handler.getCommands().containsKey("setblock")) {
      return;
    }
    handler.registerCommand(new SetBlock());
    handler.registerCommand(new Fill());
  }

  /** The world a fixture's coordinates are in: the overworld it reads back. */
  private static World overworld() {
    MinecraftServer server = MinecraftServer.getServer();
    return server == null ? null : server.worldServerForDimension(0);
  }

  /**
   * The command's arguments rejoined, which is the shape the shim parses.
   *
   * <p>This line's dispatcher drops the command name before handing the arguments over, so the
   * array starts at the first coordinate: rejoining from index one lost a coordinate and left every
   * line one argument short.
   */
  private static String line(String name, String[] args) {
    StringBuilder out = new StringBuilder(name);
    for (int i = 0; i < args.length; i++) {
      out.append(' ').append(args[i]);
    }
    return out.toString();
  }

  /** {@code /setblock <x> <y> <z> <block> [data]}. */
  private static final class SetBlock extends CommandBase {

    @Override
    public String getCommandName() {
      return "setblock";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
      return "/setblock <x> <y> <z> <block> [data]";
    }

    @Override
    public int getRequiredPermissionLevel() {
      return 2;
    }

    // CommandBase implements Comparable through this line's `compareNameTo`, and the erased
    // `compareTo(Object)` is left abstract for the subclass to forward.
    @Override
    public int compareTo(Object other) {
      return compareNameTo((ICommand) other);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
      // x, y, z and the block; the dispatcher already dropped the command name.
      if (args.length < 4) {
        return;
      }
      World world = overworld();
      if (world == null) {
        return;
      }
      // Throws for a name this line does not know, which is reported to the sender the same way an
      // unknown block is on the newer lines.
      LegacyCommandShim.setBlock(world, line("setblock", args));
    }
  }

  /** {@code /fill <x1> <y1> <z1> <x2> <y2> <z2> <block> [data]}. */
  private static final class Fill extends CommandBase {

    @Override
    public String getCommandName() {
      return "fill";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
      return "/fill <x1> <y1> <z1> <x2> <y2> <z2> <block> [data]";
    }

    @Override
    public int getRequiredPermissionLevel() {
      return 2;
    }

    @Override
    public int compareTo(Object other) {
      return compareNameTo((ICommand) other);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
      // Both corners and the block, without the command name the dispatcher already dropped.
      if (args.length < 7) {
        return;
      }
      World world = overworld();
      if (world == null) {
        return;
      }
      LegacyCommandShim.setBlock(world, line("fill", args));
    }
  }
}
