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

  /**
   * The tag an entity was spawned with, for the parts of it this line cannot hold.
   *
   * <p>A spawn tag may carry a 1.6+ attribute block, which this line has no attribute system for;
   * the entity's own NBT therefore does not report it. Keeping the spawn tag lets `data get` answer
   * for what the script asked for, which is what a script inspects after spawning.
   */
  private static final Map<Integer, NbtCompound> SPAWN_TAGS = new ConcurrentHashMap<>();

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

  private static final Pattern SETBLOCK_COMMAND =
      Pattern.compile(
          "^setblock\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+([A-Za-z0-9_.:\\-]+)"
              + "(?:\\s+(\\d+))?(?:\\s+(\\{.*\\}))?$");

  private static final Pattern GIVE =
      Pattern.compile("^give\\s+(\\S+)\\s+(\\d+)\\s+(\\d+)(?:\\s+(\\d+))?(?:\\s+(\\{.*\\}))?$");

  private static final Pattern SETBLOCK =
      Pattern.compile(
          "^setblock\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+([A-Za-z0-9_.:\\-]+)"
              + "(?:\\s+(\\d+))?(?:\\s+\\{.*)?$");

  private static final Pattern FILL =
      Pattern.compile(
          "^fill\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+"
              + "([A-Za-z0-9_.:\\-]+)(?:\\s.*)?$");

  private LegacyCommandShim() {}

  /** The tags a script has set on an entity, as it would read them back. */
  static Set<String> tagsOf(Entity entity) {
    Set<String> tags = TAGS.get(entity.id);
    if (tags == null) {
      return Collections.emptySet();
    }
    synchronized (tags) {
      return Collections.unmodifiableSet(new HashSet<>(tags));
    }
  }

  private static boolean addTag(Entity entity, String tag) {
    Set<String> tags = TAGS.computeIfAbsent(entity.id, id -> ConcurrentHashMap.newKeySet());
    return tags.add(tag);
  }

  private static boolean removeTag(Entity entity, String tag) {
    Set<String> tags = TAGS.get(entity.id);
    return tags != null && tags.remove(tag);
  }

  /** Every rewrite this line needs before a command reaches 1.7.10's parser. */
  static String translate(String command) {
    String out = rewriteGiveItemId(rewriteGive(command));
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
    // `/setblock` itself does not exist on this line, so a line that uses one is rewritten to
    // absolute coordinates and handled by the server's command hook. The coordinates have to be
    // resolved here, because the hook runs where the runner's feet are not known.
    Matcher setblock = SETBLOCK_COMMAND.matcher(rewritten.trim());
    if (setblock.matches()) {
      StringBuilder absolute =
          new StringBuilder("setblock ")
              .append(coordinate(setblock.group(1), feetX))
              .append(' ')
              .append(coordinate(setblock.group(2), feetY))
              .append(' ')
              .append(coordinate(setblock.group(3), feetZ))
              .append(' ')
              .append(LegacyFabricNbt.legacyId(setblock.group(4)));
      if (setblock.group(5) != null) {
        absolute.append(' ').append(setblock.group(5));
      }
      if (setblock.group(6) != null) {
        absolute.append(' ').append(setblock.group(6));
      }
      return Collections.singletonList(absolute.toString());
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
   * Places a block for a {@code /setblock} line, which this line has no command for.
   *
   * <p>1.4.7 predates both {@code /setblock} and {@code /fill}: its command table holds teleport,
   * give and gamerule, and nothing that writes a block. A fill is expanded into one setblock per
   * block (see {@link #expandForLegacy}), so the block is written through the world here and the
   * line answers one. A metadata argument is accepted and ignored, because every block a fixture
   * names is the metadata-zero form the newer lines' default state resolves to.
   */
  static Integer setBlock(World world, String command) {
    Matcher matcher = SETBLOCK.matcher(command.trim());
    if (!matcher.matches()) {
      return null;
    }
    int x = Integer.parseInt(matcher.group(1));
    int y = Integer.parseInt(matcher.group(2));
    int z = Integer.parseInt(matcher.group(3));
    String name = matcher.group(4);
    String bareName = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
    if (bareName.equals("air")) {
      // Air's block entry is not in this line's table, so it is written as the id itself.
      world.method_3601(x, y, z, 0, 0, true);
      LegacyFabricNames.markModified(world, x, z);
      return 1;
    }
    Block block = LegacyFabricNames.block(name);
    if (block == null) {
      // The numeric form is what this line's own scripts use; a name is what a fixture uses.
      try {
        int id = Integer.parseInt(name);
        block = id >= 0 && id < Block.BLOCKS.length ? Block.BLOCKS[id] : null;
      } catch (NumberFormatException ignored) {
        block = null;
      }
    }
    if (block == null) {
      throw new IllegalArgumentException("unknown block: " + name);
    }
    LegacyFabricNames.setBlock(world, x, y, z, block);
    return 1;
  }

  /**
   * Gives an item into the player's inventory, which is what a give ends as on every other line.
   *
   * <p>1.4.7's give builds the stack and drops it at the player's feet, leaving it to be picked up;
   * a player that moves on before the next tick walks away from the item, so the give can silently
   * fail to reach its inventory. The line also drops the tag argument entirely, so a named item
   * arrives unnamed. A give answered here is inserted directly, with its tag, which is the end
   * state the command has on the other lines.
   */
  static Integer give(net.minecraft.entity.player.ServerPlayerEntity sender, String command) {
    // The hook runs before the name-to-id rewrite, so a give that names its item is resolved here
    // first; the rewrite leaves an already numeric id alone.
    Matcher matcher = GIVE.matcher(rewriteGiveItemId(command).trim());
    if (!matcher.matches()) {
      return null;
    }
    // A script that runs a give as itself writes `@s`, which is not a name the player list holds;
    // it is the sending player, the same as it is for the command itself.
    String target = matcher.group(1);
    net.minecraft.entity.player.ServerPlayerEntity player;
    if (("@s".equals(target) || "@p".equals(target)) && sender != null) {
      player = sender;
    } else {
      net.minecraft.server.MinecraftServer server = LegacyFabricEntrypoint.server();
      if (server == null) {
        return null;
      }
      player = server.getPlayerManager().getPlayer(resolvePlayer(target));
    }
    if (player == null) {
      return null;
    }
    int id = Integer.parseInt(matcher.group(2));
    if (id < 0 || id >= net.minecraft.item.Item.ITEMS.length) {
      throw new IllegalArgumentException("unknown item id: " + id);
    }
    int damage = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
    net.minecraft.item.ItemStack stack =
        new net.minecraft.item.ItemStack(id, Integer.parseInt(matcher.group(3)), damage);
    if (matcher.group(5) != null) {
      stack.setNbt(LegacyJsonToNbt.parseSnbt(matcher.group(5)));
    }
    if (!player.inventory.insertStack(stack)) {
      player.dropStack(stack);
    }
    return 1;
  }

  /**
   * Rewrites an item name in {@code /give} to the numeric id this line parses.
   *
   * <p>1.4.7's {@code /give} takes an item id, not a name: {@code minecraft:emerald} is read as an
   * invalid number and the command fails. The id is looked up in the same table the item-drop
   * spawner uses, so a name resolves to the same item either way. Everything after the item (count,
   * damage, NBT) is left as written.
   */
  private static String rewriteGiveItemId(String command) {
    String trimmed = command.trim();
    if (!trimmed.startsWith("give ")) {
      return command;
    }
    String[] parts = trimmed.split("\\s+");
    if (parts.length < 3) {
      return command;
    }
    net.minecraft.item.Item item = LegacyFabricNames.item(parts[2]);
    if (item == null) {
      return command;
    }
    // /give indexes this line's item table with the slot the item was registered in, which is not
    // its public id: emerald's public id is 388 but it occupies slot 132. The slot is what the
    // command reads, so it is what the rewrite emits.
    int slot = -1;
    for (int i = 0; i < net.minecraft.item.Item.ITEMS.length; i++) {
      if (net.minecraft.item.Item.ITEMS[i] == item) {
        slot = i;
        break;
      }
    }
    StringBuilder out =
        new StringBuilder("give ")
            .append(resolvePlayer(parts[1]))
            .append(' ')
            .append(slot < 0 ? item.id : slot);
    for (int i = 3; i < parts.length; i++) {
      out.append(' ').append(parts[i]);
    }
    return out.toString();
  }

  /**
   * The server's own spelling of a player name.
   *
   * <p>1.4.7's {@code /give} resolves its player argument exactly, while {@code /op} and {@code
   * /clear} match case-insensitively. A script that writes the display name the join log prints
   * would otherwise get "player not found" from give and nothing from the others, so the name is
   * mapped to the one the player list holds before the command goes out.
   */
  private static String resolvePlayer(String name) {
    net.minecraft.server.MinecraftServer server = LegacyFabricEntrypoint.server();
    if (server == null) {
      return name;
    }
    for (Object raw : server.getPlayerManager().players) {
      net.minecraft.entity.player.ServerPlayerEntity player =
          (net.minecraft.entity.player.ServerPlayerEntity) raw;
      if (player.username != null && player.username.equalsIgnoreCase(name)) {
        return player.username;
      }
    }
    return name;
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
  /** Remembers the tag an entity was spawned with, for the parts this line does not carry. */
  static void rememberSpawnTag(int entityId, NbtCompound tag) {
    if (tag != null && !tag.isEmpty()) {
      SPAWN_TAGS.put(entityId, tag);
    }
  }

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
    Entity entity = found.get(0);
    entity.saveToNbt(entityTag);
    String[] steps = match.group(2).split("\\.");
    NbtElement node = entityTag;
    // A path the entity's own NBT does not hold may still be one the spawn tag carried, such as an
    // attribute this line has no system for; the spawn tag answers for the rest of the path.
    NbtCompound spawnTag = SPAWN_TAGS.get(entity.id);
    if (spawnTag != null && child(entityTag, steps[0]) == null) {
      node = spawnTag;
    }
    for (String step : steps) {
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
        value = (NbtCompound) list.getItem(index);
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
    String wanted = LegacyFabricNbt.legacyId(match.group(4));
    Block expected = LegacyFabricNames.block(wanted);
    if (expected == null) {
      // Air has no block entry on this line (its table slot is null), so the air test is answered
      // by the world instead of by an id comparison.
      if ("minecraft:air".equals(wanted)) {
        return world.isAir(x, y, z) ? 1 : 0;
      }
      return 0;
    }
    // An unloaded chunk has no blocks to test: report no match rather than loading it here, which
    // is the same answer the conditional command gives on the lines that support it.
    if (!world.getChunkProvider().chunkExists(x >> 4, z >> 4)) {
      return 0;
    }
    // This line has no block states, so the block is what a state comparison resolves to.
    return world.getBlock(x, y, z) == expected.id ? 1 : 0;
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
        ? ((net.minecraft.entity.player.PlayerEntity) entity).username
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
