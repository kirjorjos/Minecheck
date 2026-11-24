package net.minecheck.loader.forge;

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
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Answers the parts of the newer command syntax 1.7.10 cannot execute.
 *
 * <p>{@code /execute if block} and {@code /execute if entity} are 1.13 syntax that 1.7.10 parses as
 * {@code execute} with an entity named {@code if}, which answers zero — a zero that reads as a
 * failed condition rather than an unsupported command. {@code /data get entity} and a bare {@code
 * /gamerule <name>} query are missing here for the same reason, and {@code /tag} goes further: this
 * line has no scoreboard tags at all, so the tags a script writes are kept here and read back from
 * here.
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
   * The tag a script spawned an entity with, keyed by entity id.
   *
   * <p>A field this line has no home for — {@code Attributes} arrives in 1.6 — is not written onto
   * the entity, so a script that spawned one with an attribute and read it back found nothing. The
   * tag is kept here and answers a {@code data get} path the entity's own NBT does not have, the
   * same arrangement the scoreboard tags above use.
   */
  private static final Map<Integer, NBTTagCompound> SPAWN_TAGS = new ConcurrentHashMap<>();

  /** Remembers what an entity was spawned with, for the fields this line cannot store on it. */
  static void rememberSpawnTag(int entityId, NBTTagCompound tag) {
    SPAWN_TAGS.put(entityId, tag);
  }

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

  /** {@code give <player> <item> [count] [data] [tag]}, including the inline tag. */
  private static final Pattern GIVE_COMMAND =
      Pattern.compile(
          "^give\\s+(\\S+)\\s+(\\S+)(?:\\s+(\\d+))?(?:\\s+(\\d+))?(?:\\s+(\\{.*\\}))?\\s*$");

  /** {@code setblock} as a script writes it, with {@code ~} still to be resolved. */
  private static final Pattern SETBLOCK_COMMAND =
      Pattern.compile(
          "^setblock\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+([A-Za-z0-9_.:\\-]+)"
              + "(?:\\s+(\\d+))?(?:\\s+(\\{.*\\}))?$");

  /** {@code setblock} with absolute coordinates, which is what reaches the world write. */
  private static final Pattern SETBLOCK =
      Pattern.compile(
          "^setblock\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+([A-Za-z0-9_.:\\-]+)"
              + "(?:\\s+(\\d+))?(?:\\s+\\{.*)?$");

  private static final Pattern TELEPORT =
      Pattern.compile("^tp\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+\\S+\\s+\\S+$");

  private LegacyCommandShim() {}

  /** The tags a script has set on an entity, as it would read them back. */
  static Set<String> tagsOf(Entity entity) {
    Set<String> tags = TAGS.get(entity.entityId);
    if (tags == null) {
      return Collections.emptySet();
    }
    synchronized (tags) {
      return Collections.unmodifiableSet(new HashSet<>(tags));
    }
  }

  private static boolean addTag(Entity entity, String tag) {
    Set<String> tags;
    // Not `computeIfAbsent`: its mapping function is a lambda, and the Java 7 downgrade of this
    // module turns that overload into a stub that throws (see RunResult.passed).
    synchronized (TAGS) {
      tags = TAGS.get(entity.entityId);
      if (tags == null) {
        tags = ConcurrentHashMap.newKeySet();
        TAGS.put(entity.entityId, tags);
      }
    }
    return tags.add(tag);
  }

  private static boolean removeTag(Entity entity, String tag) {
    Set<String> tags = TAGS.get(entity.entityId);
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
    // The aliases are replaced inside the command, not applied to the whole string: a `setblock`
    // naming a 1.13 id is the command this line rejects ("There is no such block with name
    // minecraft:oak_sign"), and it is only that one argument that needs the old name.
    for (Map.Entry<String, String> alias : MinecheckNames.legacyAliases().entrySet()) {
      out = out.replace(alias.getKey(), alias.getValue());
    }
    return out;
  }

  /**
   * A command rewritten into the commands this line can run, which can be more than one.
   *
   * <p>Two things the newer lines' parser accepts do not exist here: {@code /fill} (1.8) and the
   * six-argument teleport (1.8). A {@code /fill} becomes one {@code /setblock} per block — the
   * shape the newer lines' {@code /fill} is a shorthand for anyway — and the teleport's angles are
   * dropped rather than faked. Coordinates are resolved against the runner's feet, so the relative
   * form means the same thing it does on the newer lines.
   */
  static List<String> expandForLegacy(
      String command, String playerName, double feetX, double feetY, double feetZ) {
    String rewritten = command.replace("@s", playerName);
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
    // `/setblock` does not exist on this line either, so a line that uses one is rewritten to
    // absolute coordinates and answered by the shim when it is dispatched. The coordinates have to
    // be resolved here, because the dispatch runs where the runner's feet are not known.
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
              .append(MinecheckNames.legacyId(setblock.group(4)));
      if (setblock.group(5) != null) {
        absolute.append(' ').append(setblock.group(5));
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
    String block = MinecheckNames.legacyId(fill.group(7));
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

  /**
   * Writes a block for a {@code /setblock} or {@code /fill} line, which this line has no commands
   * for.
   *
   * <p>Accepts both the absolute form a client's rewrite produces and the {@code fill} form a
   * script writes directly. A metadata argument is accepted and ignored: every block a fixture
   * names is the metadata-zero form the newer lines' default state resolves to.
   */
  static Integer setBlock(World world, String command) {
    String trimmed = command.trim();
    Matcher fill = FILL.matcher(trimmed);
    if (fill.matches()) {
      int placed = 0;
      for (String single : expandForLegacy(trimmed, "", 0.0, 0.0, 0.0)) {
        Integer result = setBlock(world, single);
        if (result != null) {
          placed += result;
        }
      }
      return placed;
    }
    Matcher match = SETBLOCK.matcher(trimmed);
    if (!match.matches()) {
      return null;
    }
    if (world == null) {
      return 0;
    }
    int x = Integer.parseInt(match.group(1));
    int y = Integer.parseInt(match.group(2));
    int z = Integer.parseInt(match.group(3));
    int blockId = blockId(match.group(4));
    // A scene can be built away from any player, and this line answers false for a position in a
    // chunk that is not loaded, so the chunk is read through first.
    if (world.getChunkProvider() instanceof ChunkProviderServer) {
      ((ChunkProviderServer) world.getChunkProvider()).loadChunk(x >> 4, z >> 4);
    }
    world.setBlockAndMetadataWithNotify(x, y, z, blockId, 0);
    return 1;
  }

  /**
   * The block id a {@code setblock} argument stands for.
   *
   * <p>Three forms reach here: a name a fixture writes ({@code minecraft:iron_block}), the numeric
   * id this line's own scripts write, and {@code air}, which is id 0 and has no entry in this
   * line's block table — {@code setblock ... air} is how a fixture clears a position, so it has to
   * resolve rather than be reported as an unknown block.
   */
  private static int blockId(String name) {
    Block block = MinecheckNames.block(name);
    if (block != null) {
      return block.blockID;
    }
    String bare = name.indexOf(':') >= 0 ? name.substring(name.indexOf(':') + 1) : name;
    if ("air".equals(bare)) {
      return 0;
    }
    try {
      int id = Integer.parseInt(bare);
      if (id >= 0 && (id == 0 || (id < Block.blocksList.length && Block.blocksList[id] != null))) {
        return id;
      }
    } catch (NumberFormatException ignored) {
      // Reported below, the way an unknown name is.
    }
    throw new IllegalArgumentException("unknown block: " + name);
  }

  /**
   * A {@code give} that carries an inline tag, answered here because this line's own command stops
   * at the damage value.
   *
   * <p>1.7.10's {@code /give <player> <itemid> <amount> [damage]} has no tag argument, so the named
   * sword the beacon phase gives arrived unnamed: the phase reads {@code display.Name} off the
   * stack and found nothing. Reading the tag back off the command and attaching it to the stack is
   * the only way the name reaches the item on this line. A {@code give} without a tag is left to
   * the command manager, which already handles it.
   */
  static Integer give(World world, String command) {
    Matcher match = GIVE_COMMAND.matcher(command.trim());
    if (!match.matches() || match.group(5) == null) {
      return null;
    }
    MinecraftServer server = MinecraftServer.getServer();
    if (server == null) {
      return null;
    }
    EntityPlayerMP player = server.getConfigurationManager().getPlayerForUsername(match.group(1));
    if (player == null) {
      return 0;
    }
    Item item = MinecheckNames.item(match.group(2));
    if (item == null) {
      // The numeric form is what this line's own scripts write, after `rewriteGive`.
      try {
        int id = Integer.parseInt(match.group(2));
        item = id >= 0 && id < Item.itemsList.length ? Item.itemsList[id] : null;
      } catch (NumberFormatException ignored) {
        item = null;
      }
    }
    if (item == null) {
      throw new IllegalArgumentException("unknown item: " + match.group(2));
    }
    int count = match.group(3) == null ? 1 : Integer.parseInt(match.group(3));
    int data = match.group(4) == null ? 0 : Integer.parseInt(match.group(4));
    ItemStack stack = new ItemStack(item, count, data);
    stack.setTagCompound(JsonToNbt.parse(normalizeQuotes(match.group(5))));
    player.inventory.addItemStackToInventory(stack);
    return 1;
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
   * Rewrites a {@code /give} into the form 1.4.7 parses, or returns the command unchanged when it
   * cannot be.
   *
   * <p>The item is an <em>id</em> on this line: a 1.13 name reaches the parser as the amount and is
   * answered {@code "'minecraft:emerald' is not a valid number"} — which is how the merged
   * server-features session's `give`s silently did nothing and left the client waiting for a
   * handshake that never came. The name is resolved through the same table the spawn actions use,
   * and a block resolves to its item form. An inline tag may sit before or after the amount, where
   * this line takes it last.
   */
  static String rewriteGive(String command) {
    String trimmed = command.trim();
    if (!trimmed.startsWith("give ")) {
      return command;
    }
    int open = trimmed.indexOf('{');
    String head =
        open < 0 ? trimmed.substring("give ".length()) : trimmed.substring("give ".length(), open);
    String[] parts = head.trim().split("\\s+");
    if (parts.length < 2) {
      return command;
    }
    String item = legacyItemId(parts[1]);
    if (item == null) {
      return command;
    }
    String count = parts.length > 2 ? parts[2] : "1";
    String data = parts.length > 3 ? parts[3] : "0";
    String tag = "";
    if (open >= 0) {
      int close = matchingBrace(trimmed, open);
      if (close < 0) {
        return command;
      }
      tag = " " + normalizeQuotes(trimmed.substring(open, close + 1));
      String after = trimmed.substring(close + 1).trim();
      if (!after.isEmpty()) {
        count = after;
      }
    }
    // <player> <item> [amount] [data] [dataTag]: the item is an id here, and the tag is last.
    return "give " + parts[0] + " " + item + " " + count + " " + data + tag;
  }

  /**
   * The numeric id this line's {@code /give} takes for an item name, or {@code null} when it has
   * none.
   */
  private static String legacyItemId(String id) {
    if (id.matches("\\d+")) {
      return id;
    }
    Item item = MinecheckNames.item(id);
    if (item == null) {
      Block block = MinecheckNames.block(id);
      // 1.4.7 has no `Item.getItemFromBlock`: a block's item form is registered under its own
      // block id in the item table, and that is the id `/give` takes.
      item =
          block == null || block.blockID >= Item.itemsList.length
              ? null
              : Item.itemsList[block.blockID];
    }
    return item == null ? null : Integer.toString(item.itemID);
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
    // A rule this line does not have reads as "off": the suite's question is only ever "is it
    // disabled", and `hasRule` is what tells an absent rule from a present one.
    net.minecraft.world.GameRules rules = world.getGameRules();
    String value =
        rules.hasRule(match.group(1)) ? rules.getGameRuleStringValue(match.group(1)) : "";
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
    NBTTagCompound entityTag = new NBTTagCompound();
    found.get(0).writeToNBT(entityTag);
    NBTBase node = path(entityTag, match.group(2));
    if (node == null) {
      NBTTagCompound spawned = SPAWN_TAGS.get(found.get(0).entityId);
      node = spawned == null ? null : path(spawned, match.group(2));
    }
    if (node == null) {
      return 0;
    }
    return MinecheckNames.isNumber(node) ? (int) Math.round(MinecheckNames.asDouble(node)) : 1;
  }

  /** The tag at a dotted NBT path, or {@code null} when the tree has no such node. */
  private static NBTBase path(NBTBase root, String dotted) {
    NBTBase node = root;
    for (String step : dotted.split("\\.")) {
      node = child(node, step);
      if (node == null) {
        return null;
      }
    }
    return node;
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
      if (index >= list.tagCount()) {
        return null;
      }
      // This line's list exposes no element at an index: it answers by type, so a list of compounds
      // — the only list a path continues into — is read through the compound accessor.
      try {
        value = list.tagAt(index);
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
    // `blockId` also answers `air`, which this line's block table has no entry for: a fixture
    // tests "is this cell air" after clearing it, and the test read as false for every cell. An
    // unknown name is still a no-match rather than an error.
    int expected;
    try {
      expected = blockId(match.group(4));
    } catch (IllegalArgumentException unknown) {
      return 0;
    }
    // An unloaded chunk has no blocks to test: report no match rather than loading it here, which
    // is the same answer the conditional command gives on the lines that support it.
    if (world.getChunkProvider() instanceof ChunkProviderServer
        && !((ChunkProviderServer) world.getChunkProvider()).chunkExists(x >> 4, z >> 4)) {
      return 0;
    }
    // This line has no block states, so the block is what a state comparison resolves to.
    return world.getBlockId(x, y, z) == expected ? 1 : 0;
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
      for (Object o : world.loadedEntityList) {
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
        String name = EntityList.getEntityString(entity);
        if (name == null || !types.contains(MinecheckNames.modernEntityId(name))) {
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
    return entity instanceof EntityPlayer ? ((EntityPlayer) entity).getCommandSenderName() : "";
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
