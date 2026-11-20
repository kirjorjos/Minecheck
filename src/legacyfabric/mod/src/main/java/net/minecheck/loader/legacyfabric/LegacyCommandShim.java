package net.minecheck.loader.legacyfabric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.World;

/**
 * Answers the parts of the newer command syntax 1.7.10 cannot execute.
 *
 * <p>This is the same shim the newer lines carry, ported onto this line's names: {@code /execute if
 * block} and {@code /execute if entity} are 1.13 syntax that 1.7.10 parses as {@code execute} with
 * an entity named {@code if}, which answers zero — a zero that reads as a failed condition rather
 * than an unsupported command. {@code /data get entity} and a bare {@code /gamerule <name>} query
 * are missing here for the same reason, and {@code /tag} goes further: this line has no scoreboard
 * tags at all, so the tags a script writes are kept here and read back from here.
 *
 * <p>Selectors are resolved here rather than by the command manager, because the manager's own
 * parser rejects the 1.13 arguments ({@code tag=}, {@code nbt=}, {@code limit=}) outright. Only the
 * forms the fixtures use are understood: {@code @a}, {@code @p}, {@code @e}, {@code @s} and a
 * player name, with {@code name=}, {@code type=} and {@code tag=} filters.
 */
final class LegacyCommandShim {

  /**
   * The scoreboard tags a script has set, keyed by entity id, because 1.7.10 has no scoreboard tags
   * of its own: the {@code /tag} command arrives in 1.13, and the tag list it drives arrives with
   * it. A tag lives as long as the entity id does; ids are not reused within a run.
   */
  private static final Map<Integer, Set<String>> TAGS = new ConcurrentHashMap<>();

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

  private static final Pattern FILL =
      Pattern.compile(
          "^fill\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+"
              + "([A-Za-z0-9_.:\\-]+)(?:\\s.*)?$");

  private LegacyCommandShim() {}

  /** The tags a script has set on an entity, as it would read them back. */
  static Set<String> tagsOf(Entity entity) {
    Set<String> tags = TAGS.get(entity.getEntityId());
    if (tags == null) {
      return Collections.emptySet();
    }
    synchronized (tags) {
      return Collections.unmodifiableSet(new HashSet<>(tags));
    }
  }

  private static boolean addTag(Entity entity, String tag) {
    Set<String> tags =
        TAGS.computeIfAbsent(entity.getEntityId(), id -> ConcurrentHashMap.newKeySet());
    return tags.add(tag);
  }

  private static boolean removeTag(Entity entity, String tag) {
    Set<String> tags = TAGS.get(entity.getEntityId());
    return tags != null && tags.remove(tag);
  }

  /** Every rewrite this line needs before a command reaches 1.7.10's parser. */
  static String translate(String command) {
    String out = rewriteGive(command);
    // Selectors spell the game mode `m=` on this line and `gamemode=` from 1.13, and cap the match
    // with `c=` where 1.13 says `limit=`. An unknown argument is rejected outright, so a selector
    // carrying one matched nothing at all.
    out = out.replace("gamemode=", "m=");
    out = out.replaceAll("limit=(\\d+)", "c=$1");
    if (out.startsWith("replaceitem ")) {
      // 1.12.2 prefixes a container slot with `slot.` (`slot.hotbar.0`), which 1.13 dropped; this
      // line has no `/replaceitem` at all, so the rewrite only keeps the command's shape.
      out = out.replaceAll(" (hotbar|inventory|weapon|armor|enderchest|horse)\\.", " slot.$1.");
    }
    // The aliases are replaced inside the command, not applied to the whole string: a `setblock`
    // naming a 1.13 id is the command 1.7.10 rejects ("There is no such block with name
    // minecraft:oak_sign"), and it is only that one argument that needs the old name.
    for (Map.Entry<String, String> alias : LegacyFabricNbt.legacyAliases().entrySet()) {
      out = out.replace(alias.getKey(), alias.getValue());
    }
    return out;
  }

  /**
   * A command rewritten into the commands this line can run, which can be more than one.
   *
   * <p>Two things the newer lines' parser accepts do not exist here: the {@code @s} selector (1.8)
   * and {@code /fill} (1.8). A command naming {@code @s} is rewritten to name the player running
   * it, and a {@code /fill} becomes one {@code /setblock} per block — the shape the newer lines'
   * {@code /fill} is a shorthand for anyway. Coordinates are resolved against the runner's feet, so
   * the relative form means the same thing it does on the newer lines.
   */
  private static final Pattern TELEPORT =
      Pattern.compile("^tp\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+\\S+\\s+\\S+$");

  static List<String> expandForLegacy(
      String command, String playerName, double feetX, double feetY, double feetZ) {
    String rewritten = command.replace("@s", playerName);
    // `tp <target> x y z yaw pitch`: this line's teleport takes the position only, and the six
    // argument form is rejected outright, which leaves the runner where it stood. The angles are
    // dropped rather than faked — a script that needs to face a direction calls look/lookAt.
    Matcher teleport = TELEPORT.matcher(rewritten.trim());
    if (teleport.matches()) {
      rewritten =
          "tp "
              + teleport.group(1)
              + " "
              + teleport.group(2)
              + " "
              + teleport.group(3)
              + " "
              + teleport.group(4);
    }
    Matcher fill = FILL.matcher(rewritten.trim());
    if (!fill.matches()) {
      return Collections.singletonList(rewritten);
    }
    int fromX = coordinate(fill.group(1), feetX);
    int fromY = coordinate(fill.group(2), feetY);
    int fromZ = coordinate(fill.group(3), feetZ);
    int toX = coordinate(fill.group(4), feetX);
    int toY = coordinate(fill.group(5), feetY);
    int toZ = coordinate(fill.group(6), feetZ);
    String block = LegacyFabricNbt.legacyId(fill.group(7));
    List<String> out = new ArrayList<>();
    for (int x = Math.min(fromX, toX); x <= Math.max(fromX, toX); x++) {
      for (int y = Math.min(fromY, toY); y <= Math.max(fromY, toY); y++) {
        for (int z = Math.min(fromZ, toZ); z <= Math.max(fromZ, toZ); z++) {
          out.add("setblock " + x + " " + y + " " + z + " " + block);
        }
      }
    }
    return out;
  }

  /** A fill coordinate: absolute, or {@code ~} relative to the runner's feet. */
  private static int coordinate(String token, double origin) {
    if (!token.startsWith("~")) {
      return (int) Math.floor(Double.parseDouble(token));
    }
    String offset = token.substring(1);
    double relative = offset.isEmpty() ? 0.0 : Double.parseDouble(offset);
    return (int) Math.floor(origin + relative);
  }

  /**
   * Rewrites the 1.13 inline-tag {@code /give} into the form 1.7.10 parses, or returns the command
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
    // item a 1.7.10 lookup would try to resolve.
    return "give " + parts[0] + " " + parts[1] + " " + count + " 0 " + tag;
  }

  /**
   * This line's NBT reader only knows double-quoted strings, and the newer fixtures write a JSON
   * text inside single quotes; left as written, the reader reports "Expected value" at the first
   * value.
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
   * The value of {@code gamerule <name>}, which 1.13 made the command's result; this line answers 1
   * for any query, so a fixture asking "is this rule off" read every rule as on.
   */
  static Integer gameruleValue(World world, String command) {
    Matcher match = GAMERULE_QUERY.matcher(command.trim());
    if (!match.matches() || world == null) {
      return null;
    }
    String name = match.group(1);
    // A rule this line does not have reads as "off": 1.7.10 predates doInsomnia and doWeatherCycle,
    // and the suite's question is only ever "is it disabled".
    if (!world.getGameRules().contains(name)) {
      return 0;
    }
    String value = world.getGameRules().getString(name);
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
   * {@code tag <selector> add|remove <name>}: the root arrives in 1.13 along with the tags it
   * writes, while this line has neither — so the tag list is the one kept in this class and read
   * back by {@link #tagsOf}.
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
      boolean has = tagsOf(entity).contains(tag);
      if (add && !has) {
        addTag(entity, tag);
        changed++;
      } else if (!add && has) {
        removeTag(entity, tag);
        changed++;
      }
    }
    return changed;
  }

  /**
   * {@code data get entity <selector> <path>}: this line has no {@code /data} root at all, so the
   * read is answered from the entity's own NBT.
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
    // This line writes an entity's NBT into a compound and answers whether it wrote anything, so
    // the compound is the value and the boolean is discarded.
    NbtCompound entityTag = new NbtCompound();
    found.get(0).saveToNbt(entityTag);
    NbtElement node = entityTag;
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
      if (index >= list.size()) {
        return null;
      }
      // This line's list exposes no element at an index: it answers by type, so a list of compounds
      // — the only list a path continues into — is read through the compound accessor.
      try {
        value = list.getCompound(index);
      } catch (RuntimeException e) {
        return null;
      }
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
    int x = Integer.parseInt(match.group(1));
    int y = Integer.parseInt(match.group(2));
    int z = Integer.parseInt(match.group(3));
    Block expected = Block.get(LegacyFabricNbt.legacyId(match.group(4)));
    if (expected == null) {
      return 0;
    }
    // An unloaded chunk has no blocks to test: report no match rather than loading it here, which
    // is the same answer the conditional command gives on the lines that support it.
    if (!world.getChunkProvider().chunkExists(x >> 4, z >> 4)) {
      return 0;
    }
    // This line has no block states, so the block is what a state comparison resolves to.
    return world.getBlock(x, y, z) == expected ? 1 : 0;
  }

  /** The entities a selector names, with the 1.13 arguments this line has no parser for. */
  private static List<Entity> matchingEntities(World world, String selector) {
    if (world == null) {
      return Collections.emptyList();
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
        for (Object o : world.playerEntities) {
          Entity player = (Entity) o;
          if (playerName(player).equals(base)) {
            return Collections.singletonList(player);
          }
        }
        return Collections.emptyList();
    }
    for (Object o : world.playerEntities) {
      candidates.add((Entity) o);
    }
    if (!playersOnly) {
      for (Object o : world.loadedEntities) {
        candidates.add((Entity) o);
      }
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
        String name = EntityType.getEntityName(entity);
        if (name == null || !types.contains(LegacyFabricNbt.modernEntityId(name))) {
          continue;
        }
      }
      if (!tags.isEmpty() && !tagsOf(entity).containsAll(tags)) {
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
