package net.minecheck.loader.forge;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
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
 *
 * <p>{@code /give} takes its item tag inline since 1.13 ({@code give @p iron_sword{display:...}
 * 1}); 1.12.2 parses the whole {@code iron_sword{display:...}} as the item name, finds no such item
 * and answers zero, so the tag is moved to the trailing argument 1.12.2 expects, with its
 * single-quoted JSON strings turned into the double-quoted form 1.12.2's NBT reader accepts.
 *
 * <p>Block ids renamed in 1.13 are mapped back ({@code oak_sign} to {@code standing_sign}), because
 * {@code /setblock minecraft:oak_sign} answers "There is no such block" and the fixture reads an
 * empty position instead of the sign it just placed.
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

  private static final Pattern SELECTOR_TAG = Pattern.compile("tag=([^,\\]]+)");

  private static final String GIVE = "give";

  /** 1.13 names for blocks and items 1.12.2 only knows under the old id. */
  private static final Map<String, String> BLOCK_ALIASES = new HashMap<>();

  /** The reverse of {@link #BLOCK_ALIASES}: what a fixture should read back for an old id. */
  private static final Map<String, String> MODERN_BLOCK_IDS = new HashMap<>();

  static {
    BLOCK_ALIASES.put("minecraft:oak_sign", "minecraft:standing_sign");
    BLOCK_ALIASES.put("minecraft:oak_wall_sign", "minecraft:wall_sign");
    BLOCK_ALIASES.put("minecraft:oak_planks", "minecraft:planks");
    MODERN_BLOCK_IDS.put("minecraft:standing_sign", "minecraft:oak_sign");
    MODERN_BLOCK_IDS.put("minecraft:wall_sign", "minecraft:oak_wall_sign");
    MODERN_BLOCK_IDS.put("minecraft:planks", "minecraft:oak_planks");
  }

  /**
   * The name a block or item is known by on the newer lines, so one fixture can name it and read it
   * back; a rename would otherwise place or give something the suite then cannot find.
   */
  static String modernId(String id) {
    return MODERN_BLOCK_IDS.getOrDefault(id, id);
  }

  private LegacyCommandShim() {}

  /** Every rewrite this line needs before a command reaches 1.12.2's parser. */
  static String translate(String command) {
    String out = rewriteGive(command);
    for (Map.Entry<String, String> alias : BLOCK_ALIASES.entrySet()) {
      out = out.replace(alias.getKey(), alias.getValue());
    }
    // Selectors spell the game mode `m=` on 1.12.2 and `gamemode=` from 1.13, and cap the match
    // with
    // `c=` where 1.13 says `limit=`. An unknown argument is rejected outright, so a selector
    // carrying
    // one matched nothing at all.
    out = out.replace("gamemode=", "m=");
    out = out.replaceAll("limit=(\\d+)", "c=$1");
    // 1.12.2 prefixes a container slot with `slot.` (`slot.hotbar.0`), which 1.13 dropped; without
    // it
    // the slot is not a valid parameter and the item never arrives.
    if (out.startsWith("replaceitem ")) {
      out = out.replaceAll(" (hotbar|inventory|weapon|armor|enderchest|horse)\\.", " slot.$1.");
    }
    return out;
  }

  /**
   * Rewrites the 1.13 inline-tag {@code /give} into the form 1.12.2 parses, or returns the command
   * unchanged when it is anything else.
   */
  static String rewriteGive(String command) {
    String trimmed = command.trim();
    if (!trimmed.startsWith(GIVE + " ")) {
      return command;
    }
    int open = trimmed.indexOf('{');
    if (open < 0) {
      return command;
    }
    String head = trimmed.substring(GIVE.length() + 1, open);
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
    return GIVE + " " + parts[0] + " " + parts[1] + " " + count + " 0 " + tag;
  }

  /**
   * 1.12.2's NBT reader only knows double-quoted strings, and the newer fixtures write a JSON text
   * inside single quotes ({@code Name:'{"text":"..."}'}); left as written, 1.12.2 reports "Expected
   * value" at the first value of the tag and gives nothing.
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
    GameRules rules = world.getGameRules();
    String value = rules.getString(match.group(1));
    // A rule this line does not have reads as "off": 1.12.2 predates doInsomnia, and the suite's
    // question is only ever "is it disabled".
    if (value == null || value.isEmpty() || value.equalsIgnoreCase("false") || value.equals("0")) {
      return 0;
    }
    return 1;
  }

  /**
   * {@code execute if entity <selector>} answered through 1.12.2's own selector parser, which the
   * command's {@code execute} does not understand at all (it takes a selector, then a position).
   */
  static Integer executeIfEntity(ICommandSender sender, String command) {
    Matcher match = EXECUTE_IF_ENTITY.matcher(command.trim());
    if (!match.matches()) {
      return null;
    }
    try {
      java.util.List<Entity> found = matchingEntities(sender, match.group(1));
      return found.isEmpty() ? 0 : 1;
    } catch (CommandException e) {
      return 0;
    }
  }

  /**
   * The entities a selector names. 1.12.2 has no {@code tag=} argument (it arrives in 1.13) and
   * rejects the whole selector over it, so the tag terms are taken out and applied against Forge's
   * entity tags, which are already there.
   */
  private static java.util.List<Entity> matchingEntities(ICommandSender sender, String selector)
      throws CommandException {
    java.util.Set<String> tags = new java.util.HashSet<>();
    java.util.List<Object> ntbs = new java.util.ArrayList<>();
    String cleaned = selector;
    Matcher match = SELECTOR_TAG.matcher(cleaned);
    while (match.find()) {
      tags.add(match.group(1));
      cleaned = cleaned.replace(match.group(), "");
    }
    cleaned = stripNbtTerms(cleaned, ntbs);
    cleaned = cleaned.replace(",,", ",").replace("[,\"", "[");
    cleaned = cleaned.replace(",]", "]");
    if (!ntbs.isEmpty()) {}
    java.util.List<Entity> found = CommandBase.getEntityList(sender.getServer(), sender, cleaned);
    java.util.List<Entity> filtered = new java.util.ArrayList<>();
    for (Entity entity : found) {
      if (!entity.getTags().containsAll(tags)) {
        continue;
      }
      boolean nbtMatches = true;
      for (Object expected : ntbs) {
        if (!matchesNbt(entity.writeToNBT(new NBTTagCompound()), expected)) {
          nbtMatches = false;
          break;
        }
      }
      if (nbtMatches) {
        filtered.add(entity);
      }
    }
    return filtered;
  }

  /**
   * Takes the {@code nbt=<compound>} terms out of a selector, parsing each one. 1.12.2 has no
   * {@code nbt=} argument either, and an unknown argument fails the whole selector rather than just
   * the term.
   */
  private static String stripNbtTerms(String selector, java.util.List<Object> parsed) {
    String remaining = selector;
    while (true) {
      int start = remaining.indexOf("nbt={");
      if (start < 0) {
        return remaining;
      }
      int depth = 0;
      int end = -1;
      for (int i = start + 4; i < remaining.length(); i++) {
        char c = remaining.charAt(i);
        if (c == '{') {
          depth++;
        } else if (c == '}' && --depth == 0) {
          end = i + 1;
          break;
        }
      }
      if (end < 0) {
        return remaining;
      }
      parsed.add(parseSnbt(remaining.substring(start + 4, end)));
      remaining = remaining.substring(0, start) + remaining.substring(end);
    }
  }

  /** The compound subset SNBT selectors use: nested compounds, quoted or bare keys, scalars. */
  private static Object parseSnbt(String text) {
    int[] at = {0};
    return parseCompound(text, at);
  }

  private static java.util.Map<String, Object> parseCompound(String text, int[] at) {
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    skipSpace(text, at);
    expect(text, at, '{');
    skipSpace(text, at);
    if (peek(text, at) == '}') {
      at[0]++;
      return out;
    }
    while (true) {
      skipSpace(text, at);
      String key = String.valueOf(parseScalar(text, at));
      skipSpace(text, at);
      expect(text, at, ':');
      skipSpace(text, at);
      Object value = peek(text, at) == '{' ? parseCompound(text, at) : parseScalar(text, at);
      out.put(key, value);
      skipSpace(text, at);
      if (peek(text, at) == ',') {
        at[0]++;
        continue;
      }
      expect(text, at, '}');
      return out;
    }
  }

  private static Object parseScalar(String text, int[] at) {
    char quote = peek(text, at);
    if (quote == '"' || quote == '\'') {
      StringBuilder out = new StringBuilder();
      at[0]++;
      while (at[0] < text.length() && text.charAt(at[0]) != quote) {
        out.append(text.charAt(at[0]++));
      }
      at[0]++;
      return out.toString();
    }
    int start = at[0];
    while (at[0] < text.length()
        && text.charAt(at[0]) != ','
        && text.charAt(at[0]) != '}'
        && text.charAt(at[0]) != ':') {
      at[0]++;
    }
    String token = text.substring(start, at[0]).trim();
    if (token.equalsIgnoreCase("true") || token.equalsIgnoreCase("false")) {
      return token.equalsIgnoreCase("true") ? Boolean.TRUE : Boolean.FALSE;
    }
    try {
      return Double.valueOf(token.replaceAll("[bBsSlLfFdD]$", ""));
    } catch (NumberFormatException e) {
      return token;
    }
  }

  private static void skipSpace(String text, int[] at) {
    while (at[0] < text.length() && Character.isWhitespace(text.charAt(at[0]))) {
      at[0]++;
    }
  }

  private static char peek(String text, int[] at) {
    return at[0] < text.length() ? text.charAt(at[0]) : '\0';
  }

  private static void expect(String text, int[] at, char c) {
    skipSpace(text, at);
    if (peek(text, at) == c) {
      at[0]++;
    }
  }

  /** Whether one entity's NBT satisfies a parsed {@code nbt=} term. */
  @SuppressWarnings("unchecked")
  private static boolean matchesNbt(NBTBase tag, Object expected) {
    if (expected instanceof java.util.Map) {
      if (!(tag instanceof NBTTagCompound)) {
        return false;
      }
      NBTTagCompound compound = (NBTTagCompound) tag;
      for (java.util.Map.Entry<String, Object> entry :
          ((java.util.Map<String, Object>) expected).entrySet()) {
        NBTBase child = compound.getTag(entry.getKey());
        if (child == null || !matchesNbt(child, entry.getValue())) {
          return false;
        }
      }
      return true;
    }
    if (expected instanceof Boolean) {
      return tag instanceof NBTPrimitive
          && (((NBTPrimitive) tag).getDouble() != 0.0) == (Boolean) expected;
    }
    String text = String.valueOf(expected);
    if (tag instanceof net.minecraft.nbt.NBTTagString) {
      return ((net.minecraft.nbt.NBTTagString) tag).getString().equals(text);
    }
    if (tag instanceof NBTPrimitive) {
      try {
        return ((NBTPrimitive) tag).getDouble() == Double.parseDouble(text);
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return false;
  }

  /**
   * {@code tag <selector> add|remove <name>}: the root arrives in 1.13, while Forge's entity tags
   * are already there on 1.12.2 — the command that drives them is what is missing, and the suites
   * use it to mark the entity they spawned.
   */
  static Integer tagCommand(ICommandSender sender, String command) {
    Matcher match = TAG_COMMAND.matcher(command.trim());
    if (!match.matches()) {
      return null;
    }
    try {
      java.util.List<Entity> found = matchingEntities(sender, match.group(1));
      boolean add = match.group(2).equals("add");
      String tag = match.group(3);
      int changed = 0;
      for (Entity entity : found) {
        boolean has = entity.getTags().contains(tag);
        if (add && !has) {
          entity.addTag(tag);
          changed++;
        } else if (!add && has) {
          entity.removeTag(tag);
          changed++;
        }
      }
      return changed;
    } catch (CommandException e) {
      return 0;
    }
  }

  /**
   * {@code data get entity <selector> <path>}: 1.12.2 has no {@code /data} root at all (it has
   * {@code /entitydata}, which writes), so the read is answered from the entity's own NBT. A number
   * is its value; any other tag is present, and reports 1 the way the newer command does for a
   * non-numeric tag it can print.
   */
  static Integer dataGetEntity(ICommandSender sender, String command) {
    Matcher match = DATA_GET_ENTITY.matcher(command.trim());
    if (!match.matches()) {
      return null;
    }
    try {
      java.util.List<Entity> found = matchingEntities(sender, match.group(1));
      if (found.isEmpty()) {
        return 0;
      }
      NBTBase node = found.get(0).writeToNBT(new NBTTagCompound());
      for (String step : match.group(2).split("\\.")) {
        node = child(node, step);
        if (node == null) {
          return 0;
        }
      }
      return node instanceof NBTPrimitive ? (int) Math.round(((NBTPrimitive) node).getDouble()) : 1;
    } catch (CommandException e) {
      return 0;
    }
  }

  /** One step of an NBT path, such as {@code Attributes[0]}. */
  private static NBTBase child(NBTBase node, String step) {
    String name = step;
    int index = -1;
    int bracket = step.indexOf('[');
    if (bracket >= 0) {
      name = step.substring(0, bracket);
      index = Integer.parseInt(step.substring(bracket + 1, step.indexOf(']')));
    }
    NBTBase value = node;
    if (node instanceof NBTTagCompound && !name.isEmpty()) {
      value = ((NBTTagCompound) node).getTag(name);
    }
    if (value != null && index >= 0 && value instanceof NBTTagList) {
      NBTTagList list = (NBTTagList) value;
      value = index < list.tagCount() ? list.get(index) : null;
    }
    return value;
  }

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
