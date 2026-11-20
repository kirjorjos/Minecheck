package net.minecheck.loader.legacyfabric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Answers the parts of the newer command syntax 1.12.2 cannot execute.
 *
 * <p>This is the same shim the Forge line carries, ported onto this line's names: {@code /execute
 * if block} and {@code /execute if entity} are 1.13 syntax that 1.12.2 parses as {@code execute}
 * with an entity named {@code if}, which answers zero — a zero that reads as a failed condition
 * rather than an unsupported command. {@code /data get entity}, {@code /tag} and a bare {@code
 * /gamerule <name>} query are missing here for the same reason.
 *
 * <p>Selectors are resolved here rather than by the command manager, because the manager's own
 * parser rejects the 1.13 arguments ({@code tag=}, {@code nbt=}, {@code limit=}) outright. Only the
 * forms the fixtures use are understood: {@code @a}, {@code @p}, {@code @e}, {@code @s} and a
 * player name, with {@code name=}, {@code type=} and {@code tag=} filters.
 */
final class LegacyCommandShim {

  private static final Pattern EXECUTE_IF_BLOCK =
      Pattern.compile(
          "^execute\\s+if\\s+block\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+([A-Za-z0-9_.:\\-]+)"
              + "(?:\\s.*)?$");

  private static final Pattern GAMERULE_QUERY =
      Pattern.compile("^gamerule\\s+([A-Za-z0-9_.\\-]+)$");

  private static final Pattern EXECUTE_IF_ENTITY =
      Pattern.compile("^execute\\s+if\\s+entity\\s+(.+)$");

  private static final Pattern DATA_GET_ENTITY =
      Pattern.compile("^data\\s+get\\s+entity\\s+(\\S+)\\s+(\\S+)$");

  private static final Pattern TAG_COMMAND =
      Pattern.compile("^tag\\s+(\\S+)\\s+(add|remove)\\s+(\\S+)$");

  private static final Pattern SELECTOR_ARG = Pattern.compile("([a-zA-Z]+)=([^,\\]]+)");

  private LegacyCommandShim() {}

  /** Every rewrite this line needs before a command reaches 1.12.2's parser. */
  static String translate(String command) {
    String out = rewriteGive(command);
    // Selectors spell the game mode `m=` on 1.12.2 and `gamemode=` from 1.13, and cap the match
    // with `c=` where 1.13 says `limit=`. An unknown argument is rejected outright, so a selector
    // carrying one matched nothing at all.
    out = out.replace("gamemode=", "m=");
    out = out.replaceAll("limit=(\\d+)", "c=$1");
    if (out.startsWith("replaceitem ")) {
      // 1.12.2 prefixes a container slot with `slot.` (`slot.hotbar.0`), which 1.13 dropped.
      out = out.replaceAll(" (hotbar|inventory|weapon|armor|enderchest|horse)\\.", " slot.$1.");
    }
    // The aliases are replaced inside the command, not applied to the whole string: a `setblock`
    // naming a 1.13 id is the command 1.12.2 rejects ("There is no such block with name
    // minecraft:oak_sign"), and it is only that one argument that needs the old name.
    for (Map.Entry<String, String> alias : LegacyFabricNbt.legacyAliases().entrySet()) {
      out = out.replace(alias.getKey(), alias.getValue());
    }
    return out;
  }

  /**
   * Rewrites the 1.13 inline-tag {@code /give} into the form 1.12.2 parses, or returns the command
   * unchanged when it is anything else.
   */
  static String rewriteGive(String command) {
    String trimmed = command.trim();
    if (!trimmed.startsWith("give ")) {
      return command;
    }
    int open = trimmed.indexOf('{');
    if (open < 0) {
      return command;
    }
    String head = trimmed.substring("give ".length(), open);
    String[] parts = head.trim().split("\\s+");
    if (parts.length != 2) {
      return command;
    }
    int close = matchingBrace(trimmed, open);
    if (close < 0) {
      return command;
    }
    String tag = normalizeQuotes(trimmed.substring(open, close + 1));
    String count = trimmed.substring(close + 1).trim();
    if (count.isEmpty()) {
      count = "1";
    }
    // <player> <item> [amount] [data] [dataTag]: the tag is the last argument here, not part of the
    // item a 1.12.2 lookup would try to resolve.
    return "give " + parts[0] + " " + parts[1] + " " + count + " 0 " + tag;
  }

  /**
   * 1.12.2's NBT reader only knows double-quoted strings, and the newer fixtures write a JSON text
   * inside single quotes; left as written, the reader reports "Expected value" at the first value.
   */
  private static String normalizeQuotes(String tag) {
    StringBuilder out = new StringBuilder(tag.length() + 8);
    for (int i = 0; i < tag.length(); i++) {
      char c = tag.charAt(i);
      if (c != '\'') {
        out.append(c);
        continue;
      }
      out.append('"');
      for (i++; i < tag.length() && tag.charAt(i) != '\''; i++) {
        char inner = tag.charAt(i);
        if (inner == '"' || inner == '\\') {
          out.append('\\');
        }
        out.append(inner);
      }
      out.append('"');
    }
    return out.toString();
  }

  /** Index of the brace closing the one at {@code open}, honouring the quotes a tag may contain. */
  private static int matchingBrace(String text, int open) {
    int depth = 0;
    char quote = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') {
          i++;
        } else if (c == quote) {
          quote = 0;
        }
      } else if (c == '\'' || c == '"') {
        quote = c;
      } else if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /**
   * The value of {@code gamerule <name>}, which 1.13 made the command's result; 1.12.2 answers 1
   * for any query, so a fixture asking "is this rule off" read every rule as on.
   */
  static Integer gameruleValue(World world, String command) {
    Matcher match = GAMERULE_QUERY.matcher(command.trim());
    if (!match.matches() || world == null) {
      return null;
    }
    String value = world.getGameRules().getString(match.group(1));
    // A rule this line does not have reads as "off": 1.12.2 predates doInsomnia, and the suite's
    // question is only ever "is it disabled".
    if (value == null || value.isEmpty() || value.equalsIgnoreCase("false") || value.equals("0")) {
      return 0;
    }
    return 1;
  }

  /** {@code execute if entity <selector>} answered through this line's own entity list. */
  static Integer executeIfEntity(World world, String command) {
    Matcher match = EXECUTE_IF_ENTITY.matcher(command.trim());
    if (!match.matches()) {
      return null;
    }
    return matchingEntities(world, match.group(1)).isEmpty() ? 0 : 1;
  }

  /**
   * {@code tag <selector> add|remove <name>}: the root arrives in 1.13, while scoreboard tags are
   * already there on 1.12.2 — the command that drives them is what is missing, and the suites use
   * it to mark the entity they spawned.
   */
  static Integer tagCommand(World world, String command) {
    Matcher match = TAG_COMMAND.matcher(command.trim());
    if (!match.matches()) {
      return null;
    }
    boolean add = match.group(2).equals("add");
    String tag = match.group(3);
    int changed = 0;
    for (Entity entity : matchingEntities(world, match.group(1))) {
      boolean has = entity.getScoreboardTags().contains(tag);
      if (add && !has) {
        entity.addScoreboardTag(tag);
        changed++;
      } else if (!add && has) {
        entity.removeScoreboardTag(tag);
        changed++;
      }
    }
    return changed;
  }

  /**
   * {@code data get entity <selector> <path>}: 1.12.2 has no {@code /data} root at all, so the read
   * is answered from the entity's own NBT.
   */
  static Integer dataGetEntity(World world, String command) {
    Matcher match = DATA_GET_ENTITY.matcher(command.trim());
    if (!match.matches()) {
      return null;
    }
    List<Entity> found = matchingEntities(world, match.group(1));
    if (found.isEmpty()) {
      return 0;
    }
    NbtElement node = found.get(0).toNbt(new NbtCompound());
    for (String step : match.group(2).split("\\.")) {
      node = child(node, step);
      if (node == null) {
        return 0;
      }
    }
    return LegacyFabricNbt.isNumber(node) ? (int) Math.round(LegacyFabricNbt.asDouble(node)) : 1;
  }

  /** One step of an NBT path, such as {@code Attributes[0]}. */
  private static NbtElement child(NbtElement node, String step) {
    String name = step;
    int index = -1;
    int bracket = step.indexOf('[');
    if (bracket >= 0) {
      name = step.substring(0, bracket);
      index = Integer.parseInt(step.substring(bracket + 1, step.indexOf(']')));
    }
    NbtElement value = node;
    if (node instanceof NbtCompound && !name.isEmpty()) {
      value = ((NbtCompound) node).get(name);
    }
    if (value != null && index >= 0 && value instanceof NbtList) {
      NbtList list = (NbtList) value;
      value = index < list.size() ? list.get(index) : null;
    }
    return value;
  }

  /** {@code execute if block <x> <y> <z> <id>} answered directly from the world. */
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
    Block expected = Block.get(LegacyFabricNbt.legacyId(match.group(4)));
    if (expected == null) {
      return 0;
    }
    // An unloaded chunk has no blocks to test: report no match rather than loading it here, which
    // is the same answer the conditional command gives on the lines that support it.
    if (!world.getChunkProvider().isChunkGenerated(pos.getX() >> 4, pos.getZ() >> 4)) {
      return 0;
    }
    return world.getBlockState(pos).getBlock() == expected ? 1 : 0;
  }

  /** The entities a selector names, with the 1.13 arguments this line has no parser for. */
  private static List<Entity> matchingEntities(World world, String selector) {
    if (world == null) {
      return List.of();
    }
    String base = selector.trim();
    String arguments = "";
    int bracket = base.indexOf('[');
    if (bracket >= 0) {
      int close = base.lastIndexOf(']');
      arguments = close > bracket ? base.substring(bracket + 1, close) : "";
      base = base.substring(0, bracket);
    }
    List<Entity> candidates = new ArrayList<>();
    boolean playersOnly = true;
    switch (base) {
      case "@a":
      case "@p":
      case "@r":
      case "@s":
        break;
      case "@e":
        playersOnly = false;
        break;
      default:
        // A player name: the only selector form that names one entity directly.
        for (Entity player : world.playerEntities) {
          if (playerName(player).equals(base)) {
            return List.of(player);
          }
        }
        return List.of();
    }
    candidates.addAll(world.playerEntities);
    if (!playersOnly) {
      candidates.addAll(world.loadedEntities);
    }
    Set<String> names = new HashSet<>();
    Set<String> types = new HashSet<>();
    Set<String> tags = new HashSet<>();
    Matcher argument = SELECTOR_ARG.matcher(arguments);
    while (argument.find()) {
      switch (argument.group(1).toLowerCase(Locale.ROOT)) {
        case "name":
          names.add(stripQuotes(argument.group(2)));
          break;
        case "type":
          types.add(stripQuotes(argument.group(2)));
          break;
        case "tag":
          tags.add(stripQuotes(argument.group(2)));
          break;
        default:
          // An argument this shim does not model is ignored rather than rejecting the selector,
          // which is what the command manager would have done with it.
          break;
      }
    }
    List<Entity> out = new ArrayList<>();
    for (Entity entity : candidates) {
      if (!names.isEmpty() && !names.contains(playerName(entity))) {
        continue;
      }
      if (!types.isEmpty()) {
        Identifier id = EntityType.getId(entity);
        if (id == null || !types.contains(id.toString())) {
          continue;
        }
      }
      if (!tags.isEmpty() && !entity.getScoreboardTags().containsAll(tags)) {
        continue;
      }
      out.add(entity);
    }
    return out;
  }

  private static String playerName(Entity entity) {
    return entity instanceof net.minecraft.entity.player.PlayerEntity
        ? ((net.minecraft.entity.player.PlayerEntity) entity).getGameProfile().getName()
        : "";
  }

  private static String stripQuotes(String value) {
    String trimmed = value.trim();
    if (trimmed.length() >= 2
        && (trimmed.startsWith("\"") && trimmed.endsWith("\"")
            || trimmed.startsWith("'") && trimmed.endsWith("'"))) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }
}
