---
title: Lua API Reference
---

# Minecheck — Lua API Reference

All script actions live on the global `minecheck` table. Functions that need to wait (joining a world, waiting for a player) **suspend the Lua coroutine** each engine tick until the condition is met or the timeout expires — the script never blocks the game's tick loop.

> **Table of contents** — jump to any section:
>
> [Conventions](#conventions) ·
> [config.json](#configjson) ·
> [World entry / exit](#world-entry--exit) ·
> [Server actions](#server-actions) ·
> [Assertions & result reporting](#assertions-and-result-reporting) ·
> [Utilities](#utilities) ·
> [Input (client)](#input-client) ·
> [World / entity queries (client)](#world--entity-queries-client) ·
> [Screenshots (client)](#screenshots-client) ·
> [Packets (both sides)](#packets-both-sides) ·
> [Run result JSON](#run-result-json) ·
> [Coordinators](#coordinators) ·
> [Platform notes](#platform-notes)

---

## Conventions

| Term | Meaning |
|------|---------|
| *timeout* | Seconds (number). If omitted, defaults to `config.defaultTimeout`. All timeouts are **tick-driven** — checked once per game tick, not wall-clock. |
| *boolean* | Lua truthy/falsy. Action returns `true` on success, `false` on timeout or refusal. |
| `nil` | Absent / not-applicable return (Lua's `nil`). |

Actions are split into two worlds:

- **Client** — requires a running `MinecraftClient` instance (headless or visible).
- **Server** — requires a running `MinecraftServer` (dedicated server).

Calling a client-only function on a dedicated server (or vice versa) throws a Lua error immediately.

---

## 64-bit integers

Lua numbers are doubles, so a 64-bit value past 2^53 cannot survive one. Rather than round it silently, every 64-bit integer this API hands a script arrives as an **int64** value that keeps the exact digits and only becomes a number when you use it as one:

- `reader.readLong()`, an `i64` packet argument, and any NBT **long tag** (in `getSlot(...).nbt`, `getBlock(...).nbt`, entity NBT, and plugin return values).

`minecheck.int64(v)` builds one, which is how a script sends a 64-bit value that a literal could not hold. Only 64-bit integers need this: `i32`, `f32` and `f64` are all exactly representable as Lua numbers, so they stay plain numbers.

Exact, always:

```lua
local v = minecheck.int64("72623859790382856")
tostring(v)              -- "72623859790382856"
v:toString()             -- same
v .. ""                 -- same
string.format('%s', v)   -- same
v == 72623859790382856   -- true only against another int64 of the same value;
                         -- a Lua literal has already rounded
v > 72623859790382848    -- true, compared exactly
72623859790382848 < v    -- true, and in this order too
v:eq(v)                  -- true
```

Converted to a number, losing the low bits past 2^53 — this is deliberate, it is what "use it as a number" means:

```lua
v + 1                    -- a plain number (a double)
1 + v, v * 2, v % 3      -- likewise, in both operand orders
-v                       -- a plain number
v:toNumber()             -- the double itself
math.floor(v / 2)        -- the standard library sees a number
```

Two things it cannot do, both consequences of Lua rather than of Minecheck:

- **`type(v)` is `"table"`**, because Lua 5.2 has no integer type and only tables can carry the metatable that makes the arithmetic above work. What matters is that anything *asking for a number* gets one: `math.*`, `string.format('%s','%x','%o','%u')` and every action parameter that takes a number accept it.
- **`72623859790382856 == v` is `false`** when the left operand is a plain number: Lua only tries `__eq` when *both* operands are tables. Write the int64 on the left (`v == 72623859790382856` — and remember that literal has rounded), compare against another int64, or use `v:eq(x)`.

Where a value cannot be used as a number without being wrong, it says so instead of guessing:

- `string.format('%d', v)` raises for a value past 2^31, because this LuaJ build reads `%d` through a 32-bit check. Use `tostring(v)` or `%x`.
- `minecheck.int64("not a number")`, a fractional number, and a decimal string outside 64 signed bits all raise `int64: not a 64-bit integer`.

---

## config.json

Each run reads `config/minecheck/config.json` from the game's run directory. All fields are optional:

```json
{
  "coordinator": "coordinator.lua",
  "script": "my-script.lua",
  "failMode": "exit",
  "defaultTimeout": 60
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `coordinator` | string \| null | `null` | Multi-phase entry script (wins over `script` when set). |
| `script` | string | `""` | Single-phase entry script (used when `coordinator` is absent). |
| `failMode` | string | `"exit"` | `"exit"`: quit/stop on script failure. `"continue"`: log and keep going. |
| `defaultTimeout` | int | `30` | Default timeout in seconds for all wait actions (joins, waits, sleeps). |
| `screenshotWidth` | int | `854` | Captured frame width in pixels (the window the client is pinned to), 320–7680. |
| `screenshotHeight` | int | `480` | Captured frame height in pixels, 240–4320. |
| `screenshotGuiScale` | int | `2` | GUI scale the HUD is laid out at, 1–4. Pinned rather than auto, so HUD geometry does not depend on the window. |
| `screenshotScale` | number | `1.0` | Encode-time downscale for smaller artifacts, `0.25`–`1.0`. Part of a baseline's identity. |
| `screenshotChatVisible` | boolean | `false` | Whether chat may appear in a capture. See [the capture profile](#the-capture-profile-why-a-shot-is-reproducible). |

The mod also accepts a JVM system property `-Dminecheck.script=<name>` to override the entry script at launch time. It wins over whatever the file names — `script` or `coordinator` — and it works on a config that names neither, so a one-off run needs no edit to `config.json`. The name is resolved against the same `minecheck/scripts` directory; a name with no matching file starts nothing.

A missing field, and an explicit JSON `null`, both mean "use the default". A file that is not a JSON object, or whose fields have the wrong type (e.g. `"defaultTimeout": "sixty"`), is an error that names the file and the field and **leaves the file alone** — it is not silently replaced by the defaults, because the defaults run no script at all and the resulting "wrote no run-result.json" hides the real cause. Delete the file to regenerate the template.

A `defaultTimeout` the field cannot hold is reported the same way rather than being altered: it must be a **whole number that fits in a 32-bit int**, so `0.5`, `3.9`, `2147483648` and `9223372036854775807` are all errors naming the field and quoting the value. `0` and a negative number *are* accepted — both are representable, and both mean every wait expires at once (a `timeout` argument at the call site still overrides it).

---

## World entry / exit

### `minecheck.joinLocalWorld(name[, timeout]) → boolean`

Joins an existing singleplayer world by its folder name (under the client's saves directory). Polls until the client is in world **and ready to interact** — the vanilla "downloading terrain" screen has cleared (clicks/keys dispatched before that are swallowed by the screen).

| Parameter | Type | Description |
|-----------|------|-------------|
| `name` | string | World folder name, e.g. `"minecheck_world"`. |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once in world and ready to interact. `false` on timeout.

A world whose settings are not lifecycle-stable — the normal case for a world saved with mod datapacks, which Forge/Fabric enable by default — makes the loader stop on a *"this world has experimental settings"* confirmation before it starts the integrated server. A script cannot click it, so the join answers it with **Proceed** itself. The join request is the confirmation.

---

### `minecheck.createWorld(options[, timeout]) → boolean`

Creates a new **client-side** local world and joins it. Deterministic superflat by default (no structures, no mob spawning, no daylight/weather cycle, no insomnia, seed `0`) — the full normal world-gen options are exposed for flexibility:

```lua
minecheck.createWorld({
    name = "minecheck_world",   -- folder name (default)
    seed = 0,                   -- number | string; string.hashCode() if not parseable
    gamemode = "creative",      -- "survival" | "creative" | "adventure" | "spectator"
    difficulty = "normal",      -- "peaceful" | "easy" | "normal" | "hard"
    structures = false,         -- true | false
    mobSpawning = false,        -- true | false
    allowCheats = true,         -- true | false
    daylightCycle = false,      -- true | false (deterministic default)
    weatherCycle = false,       -- true | false (deterministic default)
    insomnia = false,           -- true | false (deterministic default)
})
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `options` | table | World options (all fields optional; see table above). |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once in world and ready to interact (same loading-screen semantics as `joinLocalWorld`). `false` on timeout.

> **Server note:** There is no server-side `createWorld`. 
> The dedicated server generates its world using the normal server jar + installed world-gen mods.
> Determinism comes from a pinned `level-seed` in `server.properties`; the loader also forces the
> same deterministic gamerules (mob spawning, daylight/weather cycle, insomnia off) at server start.

---

### `minecheck.joinServer(address[, timeout]) → boolean`

Joins a dedicated server over real TCP networking. Waits for the address to actually **accept a connection** before each attempt (Forge's dedicated server does not listen at all until it logs `Done`), then retries the Minecraft-level connect — throttled to 20 s — if the attempt fails. Nothing is attempted while a connection, a login or a world is live, so a retry can never abort a join that is already succeeding.

| Parameter | Type | Description |
|-----------|------|-------------|
| `address` | string | `"host:port"`, e.g. `"localhost:25565"`. |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once connected, in-world, and ready to interact (loading screen cleared). `false` on timeout.

---

### `minecheck.leaveWorld([timeout]) → boolean`

Leaves the current world or server and returns to the **title screen**. Waits until `isInWorld()` is false and `isOnTitleScreen()` is true (vanilla's own no-arg disconnect/clear-level lands on a "Saving world" progress screen, so Minecheck passes an explicit `TitleScreen`).

| Parameter | Type | Description |
|-----------|------|-------------|
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once at the title screen. `false` on timeout.

---

### `minecheck.disconnect([timeout]) → boolean`

**Phase terminator** for coordinators. Identical semantics to `leaveWorld` — leaves the current world/server and waits until the title screen is shown. The name signals a mid-session phase boundary (no clean state is required between phases).

| Parameter | Type | Description |
|-----------|------|-------------|
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once at the title screen. `false` on timeout.

---

### `minecheck.quit() → nil`

Shuts down the client process. Use only as the **final** action in a script — between coordinator phases, use [`disconnect`](#minecheckdisconnected-timeout--boolean) instead.

**Returns:** `nil`.

---

## Server actions

### `minecheck.runCommand(cmd) → table`

Executes a command (no leading `/` needed). On the **dedicated server** it runs as a console command; on the **client** it is sent as the local player's command (game-changing commands need cheats enabled in a local world, or op on a dedicated server).

| Parameter | Type | Description |
|-----------|------|-------------|
| `cmd` | string | The command, e.g. `"stop"`, `"tp @p 0 64 0"`, `"fill ~ ~ ~1 ~8 ~ ~8 minecraft:iron_block"`. |

**Returns:**

```lua
{ success = true, result = <int>, error = nil }
```

- `success` — always `true` (errors are thrown as Lua errors instead).
- `result` — number of targets affected (vanilla convention; `0` for commands
  like `stop` that don't target anyone). On the **client** the result count is
  unavailable, so `result` is always `0` — verify effects (e.g. a screen opening, a block being placeable) instead.

> **Op note:** the client half of the beacon test relies on `op`: the server
> script runs `op <player>` before the client's own commands so the relative
> `/fill` pyramid build is permitted on the dedicated server.

---

### `minecheck.waitForPlayer(name[, timeout]) → boolean`

Blocks until a player with the given name is **online**.

| Parameter | Type | Description |
|-----------|------|-------------|
| `name` | string | Exact player name (case-sensitive). |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` when the player joins. `false` on timeout.

---

### `minecheck.waitForPlayerLeave(name[, timeout]) → boolean`

Blocks until the named player is **no longer online**.

> **Important:** If the player isn't online at call time, this returns `true` *immediately*. 
> In a "wait for join then leave" script, always call [`waitForPlayer`](#minecheckwaitforplayername-timeout--boolean) **first**.

| Parameter | Type | Description |
|-----------|------|-------------|
| `name` | string | Exact player name (case-sensitive). |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once they leave (or were never online). `false` on timeout.

### `minecheck.spawnEntity(id, pos[, nbt]) → table`

**Server-only.** Spawns an entity by registry id at the given position. The entity is initialized through the vanilla command-spawn path, so mobs are fully initialized before insertion and passenger-bearing entities are supported.

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | string | Entity registry id, e.g. `"minecraft:cow"`. |
| `pos` | table | Position `{ x = number, y = number, z = number }`. |
| `nbt` | table | Optional entity NBT. Nested compounds and lists are supported. A long tag must be an [int64](#64-bit-integers) (`minecheck.int64("...")`), since a Lua number would round it before it reached the writer. |

Entity NBT flags work directly — for example, spawn a cow that stands still with `{ NoAI = true }`:

```lua
minecheck.spawnEntity("minecraft:cow", { x = 0.5, y = 100, z = 0.5 }, { NoAI = true })
```

**Returns:**

```lua
{ success = true, id = <entity network id>, error = nil }
```

Unknown ids, invalid NBT, and insertion failures return `{ success = false, id = nil, error = "..." }`.

### `minecheck.spawnBlock(id, pos[, nbt][, replace]) → table`

**Server-only.** Places a block by registry id at the given integer block position. By default, an occupied position is left unchanged; pass `replace = true` to overwrite it. If the block creates a block entity, the optional NBT table is applied to it.

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | string | Block registry id, e.g. `"minecraft:stone"`. |
| `pos` | table | Block position `{ x = number, y = number, z = number }`; coordinates are converted to integers. |
| `nbt` | table | Optional block-entity NBT. |
| `replace` | boolean | Whether to overwrite a non-air block (default `false`). |

**Returns:** `{ success = boolean, error = string | nil }`. An occupied position with `replace = false` is a normal unsuccessful result, not a Lua exception.

### `minecheck.spawnItemDrop(id, pos[, count][, nbt]) → table`

**Server-only.** Spawns an item entity at the given position.

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | string | Item registry id, e.g. `"minecraft:diamond"`. |
| `pos` | table | Position `{ x = number, y = number, z = number }`. |
| `count` | integer | Stack count (default `1`). |
| `nbt` | table | Optional item-stack NBT. |

**Returns:** `{ success = true, id = <entity network id>, error = nil }`, or a failure table with `success = false` and an error message.

### `minecheck.spawnStructure(id, pos) → table`

**Server-only.** Places a loaded structure template at the given integer block position. For example, `"minecraft:igloo/top"` refers to the vanilla igloo-top template. Entities in the template are ignored; use `spawnEntity` when entity placement needs to be controlled explicitly.

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | string | Structure template id, e.g. `"minecraft:igloo/top"`. |
| `pos` | table | Block position `{ x = number, y = number, z = number }`; coordinates are converted to integers. |

**Returns:** `{ success = true, error = nil }` when the template is found and placed, or `{ success = false, error = "..." }` otherwise.

All four spawn actions are server-only; calling one from a client script raises `"server-only function called on client"`.

### `minecheck.findEntities([type][, tag]) → table`

**Server-only.** Lists live entities in the overworld, optionally filtered by entity type and/or scoreboard tag. Useful to confirm a spawned entity is present and where it ended up (e.g. verify a `NoAI` cow has not moved).

| Parameter | Type | Description |
|-----------|------|-------------|
| `type` | string | Entity type registry id filter, e.g. `"minecraft:cow"` (default: any type). |
| `tag` | string | Scoreboard tag filter (default: any tag). |

With no arguments it returns **all** entities in the overworld; with both filters it returns entities matching the type **and** carrying the tag. An unknown type yields an empty table rather than an error.

**Returns:** a (possibly empty) table of entity entries:

```lua
{
  {
    id = 42,
    type = "minecraft:cow",
    pos = { x = 1.5, y = 64, z = -2 },
    tags = { "herd" },
  },
}
```

- `id` — entity network id (matches the `id` returned by [`spawnEntity`](#minecheckspawnentity-id-pos-nbt--table)).
- `type` — entity registry id.
- `pos` — feet position.
- `tags` — the entity's scoreboard tags (possibly empty).

> **Client-side counterpart:** to find an entity from a *client* script — the ones the local player can actually see and reach, which is what you need before aiming at or clicking one — use [`findNearbyEntities`](#world--entity-queries-client) below. It has no tag filter, for the reason given there.

---

## GUI / inventory (client)

### `minecheck.openScreen(id[, pos][, timeout]) → boolean`

Opens a container/handled screen and waits until it is open. `id` selects the screen: `"inventory"` opens the player inventory (via the inventory key); any other id right-clicks a container block — `pos` (`{ x = number, y = number, z = number }`) is looked at first (when omitted, the current aim is used).

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | string | Screen id: `"inventory"`, `"beacon"`, `"chest"`, `"crafting"`, `"furnace"`. |
| `pos` | table | Point to aim at, then right-click (required for block screens). Aim at a block's *centre* — `{ x = bx + 0.5, y = by + 0.5, z = bz + 0.5 }` — since the corner coordinates name a point that a block hit ray grazes instead of entering. |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once [`isScreenOpen`](#minecheckisscreenopenid--boolean) reports the screen, `false` on timeout.

> `"inventory"` is opened with a synthetic tap of the inventory key rather than a container interaction, so it works whether or not anything is nearby. It also works in creative mode: the game opens the creative inventory there, and `isScreenOpen("inventory")` matches it (both inventory screens are siblings in 1.18.2, not subclasses of one another).

```lua
minecheck.openScreen("beacon", { x = 10.5, y = 65.5, z = 8.5 }, 10)
```

### `minecheck.isScreenOpen([id]) → boolean`

Non-blocking check of whether a screen is currently open. With no id (or `nil`), true when **any** container screen is open; a known id only matches that screen.

| Id | Matches |
|----|---------|
| `nil` / `""` | any container/handled screen (not the chat screen) |
| `"inventory"` | the player inventory — the survival inventory screen **or** the creative inventory screen, which are sibling classes in 1.18.2 (see the note on [`openScreen`](#minecheckopenscreenid-pos-timeout--boolean)) |
| `"chat"` | the chat screen (open while typing a message/command) |
| `"beacon"` / `"chest"` / `"crafting"` / `"furnace"` | that container screen |

The `"chat"` id is useful with [`typeText`](#minechecktypetexttext-target--boolean): wait for it to close after pressing Enter to submit the typed text.

### `minecheck.slotCount([id]) → int`

Slot count of the open screen's container (`0` when no matching screen is open). This is the **full vanilla container** — it includes the player's inventory slots, so indices line up exactly with [`getSlot`](#minecheckgetslotindex-id--table): the player inventory has 46 slots (hotbar at 36–44), a single chest 45, and the beacon menu 37 (payment slot at 0, then 27 main + 9 hotbar).

### `minecheck.getSlot(index[, id]) → table`

Reads one slot of the open screen's container.

| Parameter | Type | Description |
|-----------|------|-------------|
| `index` | int | Container slot index (0-based, vanilla order). |
| `id` | string | Optional screen id the open screen must match. |

**Returns:**

```lua
{ empty = true,  id = nil,            count = 0,  nbt = nil }
{ empty = false, id = "minecraft:emerald", count = 64, nbt = { display = { Name = "..." } } }
```

- `id` — item registry id; `nbt` mirrors the stack's NBT compound (nested tables / lists supported).

### `minecheck.clickGui(x, y[, button]) → boolean`

Clicks the currently open screen at scaled GUI coordinates (`0..scaledWidth` / `0..scaledHeight`), dispatching `mouseClicked` directly to the screen (the same call the mouse handler makes) — deterministic, independent of cursor/focus state.

| Parameter | Type | Description |
|-----------|------|-------------|
| `x`, `y` | number | Scaled GUI coordinates (pixels in the current GUI scale). |
| `button` | int | 0 = left (default), 1 = right, 2 = middle. |

**Returns:** `true` when a screen was open and the click was routed to it (regardless of whether the screen consumed it), `false` when no screen is open.

### `minecheck.clickGui(elementId[, button[, index]]) → boolean`

Clicks a specific **GUI element** by name instead of a point. The id is matched against every clickable widget on the open screen, in order:

1. its **label** — the displayed text, lower-cased and trimmed (`"Cancel"`, `"Done"`, …). This is the portable id: the label is the same on every loader and mapping.
2. its **class simple name** — lower-cased (e.g. `buttonwidget`). Only a fallback, because it *is* mapping-dependent (`ButtonWidget` on Fabric/Quilt, `Button` on Forge), so a script that uses it is loader-specific.

The click is dispatched to that widget from its own centre, *not* through the screen's hit-testing — the caller named the element, so an overlapping overlay must not swallow the click. Use the coordinate form when you want real hit-testing.

| Parameter | Type | Description |
|-----------|------|-------------|
| `elementId` | string | The element's label (or class simple name), matched case-insensitively. |
| `button` | int | 0 = left (default), 1 = right, 2 = middle. |
| `index` | int | Which match to click when several share an id, 1-based (default `1`). |

**Returns:** `true` when the element matched and the click was dispatched, `false` when no screen is open or nothing matches.

```lua
minecheck.openScreen("beacon", { x = px, y = py, z = pz })
minecheck.clickGui("Cancel")             -- the cancel button, by label
minecheck.waitFor(function() return not minecheck.isScreenOpen() end, 5)
```

### `minecheck.moveItem(fromSlot, toSlot[, count][, screen]) → boolean`

Moves `count` items (or the whole stack when `count` is omitted) from `fromSlot` to `toSlot` of the open screen's container with vanilla container clicks: a left-click picks the stack up, then — for a partial move — right-clicks drop **one item at a time** into the destination, and the leftover cursor stack is put back into the source slot. The cursor is always left empty (a leftover cursor stack would otherwise be dropped when the screen closes).

| Parameter | Type | Description |
|-----------|------|-------------|
| `fromSlot`, `toSlot` | int | Container slot indices (vanilla order). |
| `count` | int | How many items to move. Omitted/`nil` or `>=` the stack size moves the whole stack; `0` moves nothing. |
| `screen` | string | Optional screen id the open screen must match. |

**Returns:** `true` when the items landed in the destination, `false` when no matching screen is open, `count` is `0`, the source slot is empty, or the destination could not take them all (leftovers stay in the source slot). Raises a Lua error when no matching screen is open.

```lua
-- put the emerald (hotbar slot 0 = container slot 36) into the beacon's payment slot
minecheck.moveItem(36, 0, nil, "inventory")

-- move exactly 3 of a stack, leaving the rest in the source slot
minecheck.moveItem(38, 10, 3, "inventory")
```

Every click waits for the server round-trip to land (the previous click's effect is visible in the container) before the next one, so no click is rejected on a stale container revision. Verify the result with [`getSlot`](#minecheckgetslotindex-id--table) + `waitFor` — the server round-trip is async.

Raises a Lua error when no matching screen is open (or the index is out of range).

#### Moving a recipe ingredient (recipe-viewer ghost slots)

`fromSlot` may be a **ghost-slot reference** instead of an index, which names an ingredient the recipe viewer shows — the viewer's slots count as part of the GUI:

```lua
{ recipe = "minecraft:stick", slot = 0 }              -- ghost slot 0 of that recipe
{ recipe = "minecraft:stick", slot = 0, viewer = "jei" } -- a named viewer (default: the priority one)
```

`slot` is the same 0-based `index` [`getRecipeSlots`](#minecheckgetrecipeslotsrecipeid-viewerid--table) reports.

A ghost slot is a **wanted item**, not a place to take from: viewer slots are display-only, so the move still happens out of the open container — the first real slot holding one of the ingredient's item ids becomes the source (a tag-backed slot lists all its members, so any of them matches).

```lua
-- put one of the stick recipe's first ingredient into container slot 10
minecheck.moveItem({ recipe = "minecraft:stick", slot = 0 }, 10, 1, "inventory")
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `recipe` | string | Recipe registry id the viewer knows (e.g. `"minecraft:stick"`). Required. |
| `slot` | int | The recipe's 0-based ghost-slot index. Required. |
| `viewer` | string | Which viewer to ask (e.g. `"jei"`); default is the priority viewer. |

The count/screen arguments and the return contract are unchanged; a ghost reference additionally returns `false` when no viewer is available, the recipe/slot is unknown to it, the slot resolved to no items (a tag the viewer could not expand), or the container holds none of the ingredient — each miss is logged with its reason.

A ghost reference as the **destination** raises a Lua error (`viewer slots are display-only`): nothing can be put into a slot the viewer merely draws. To fill a container *from* a recipe, use [`fillFromRecipe`](#minecheckfillfromreciperecipeid-maxitems-viewerid--boolean) — that is the viewer's own transfer action.

### `minecheck.typeText(text[, target]) → boolean`

Types `text` into a text input, character by character through the vanilla input path (the same `charTyped` call a real keyboard makes) — deterministic, independent of cursor/focus state. The text is only **typed**, never submitted: send it with a key action, e.g. [`press("enter")`](#minecheckpresskey-hold-until--table) for chat.

| Parameter | Type | Description |
|-----------|------|-------------|
| `text` | string | Characters to type. |
| `target` | string | `nil`/`"field"` = the open screen's focused text field (its first text field when none is focused); `"chat"` = the chat screen, opened first when needed; `"command"` = the chat screen with a guaranteed leading `/` (an already-open chat gets the prefix prepended), so the text submits as a command. |

**Returns:** `true` when the characters were dispatched to a text field, `false` when there is nothing to type into (no screen open, or no text field on it).

For `"chat"`/`"command"` the chat screen is opened when it is not already up, and the action **waits for it** (a few ticks) before typing — so a following `press("enter")` submits the text instead of racing the screen. The open and the typing both run on the client thread; a script that asked for a chat screen and kept typing without waiting would be mutating client state from the wrong thread.

```lua
-- type a command through the chat screen and submit it
minecheck.typeText("give @s minecraft:diamond 2", "command")
minecheck.press("enter")
minecheck.waitFor(function() return not minecheck.isScreenOpen("chat") end, 5)

-- type into the open screen's focused field (its first text field when none is focused); applies to any screen with a text input (anvil rename field, sign/book editor, ...)
minecheck.typeText("Renamed By Script")
```

All seven GUI actions are client-only; calling one from a server script raises `"client-only function called on dedicated server"`. They also all mutate the client on the client thread: script code runs inside a Lua coroutine, which after its first wait/sleep is no longer on the render thread, so **every** action that mutates client state — screens, keyboard/mouse input, the camera, the interaction manager, the network, world entry — dispatches its work there, and the ones that need the outcome suspend the script until it has run (at most one tick each).

---

## Client resources (client)

### `minecheck.reloadResources([timeout]) → boolean`

Asks the client to reload its resources — the reload a player triggers with **F3+T** — and waits until it has finished. Everything registered as a resource-reload listener rebuilds during the reload (EMI, for example, re-bakes its recipe index).

| Parameter | Type | Description |
|-----------|------|-------------|
| `timeout` | number | Max seconds to wait for the reload to settle (default: `config.defaultTimeout`). |

**Returns:** `true` once the reload has finished, `false` on timeout.

The reload runs on the client thread and is asynchronous; the action polls the client until the reload is done, so the next line of the script sees the reloaded state. A dedicated-server client is the usual reason to want one: its recipes are sync'd *after* the client owns the world, so a mod that indexed at world-join may have started too early.

Note that a reload is **not** a universal fix for an empty index: JEI does not rebuild on a resource reload at all (it rebuilds when the server *syncs* recipes), and in a dev client it comes up empty regardless — see the note under [`getRecipeSlots`](#minecheckgetrecipeslotsrecipeid-viewerid--table).

```lua
minecheck.reloadResources(30)
```

Client-only; calling it from a server script raises `"client-only function called on dedicated server"`.

---

## Screenshots (client)

### `minecheck.captureScreenshot(name[, options][, timeout]) → table`

Writes the frame the client is showing to a PNG and returns what was written. The capture is taken on the client's **next rendered frame** — the action yields once per tick while it waits, so the game keeps ticking — and the PNG is encoded and written off the render thread.

```lua
local shot = minecheck.captureScreenshot("world-settled")
minecheck.log(shot.path .. " " .. shot.width .. "x" .. shot.height)

-- Top-left 200×60 GUI pixels (a HUD corner), cropped out of the frame:
local hud = minecheck.captureScreenshot("hud", {
  region = { x = 0, y = 0, w = 200, h = 60, unit = "gui" },
})
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `name` | string | Capture name, and the file name: `"world-settled"` writes `screenshots/world-settled.png`. A trailing `.png` is stripped rather than doubled. May not be empty or contain `/`, `\`, `..` or control characters. A leading `_` marks an **ad-hoc** capture (written, uploaded, never baselined). |
| `options` | table | Optional; unknown keys raise, so a typo cannot silently capture the whole frame. |
| `options.region` | table | Optional crop: `{ x, y, w, h, unit? }`. All four numbers are required and `w`/`h` must be positive. `unit` is `"framebuffer"` (default, real pixels) or `"gui"` (GUI pixels, multiplied by the pinned GUI scale). The region is clamped to the frame; a region with no overlap raises. |
| `timeout` | number | Max seconds to wait for the frame to be written (default: `config.defaultTimeout`). |

**Returns:**

```lua
{ path   = "/…/client/screenshots/world-settled.png",  -- absolute
  width  = 854,                                        -- of the written PNG
  height = 480,
  bytes  = 412933,
  sha256 = "9f2c…" }                                   -- of the written PNG
```

The digest is returned because a capture is judged by its pixels: when a baseline comparison fails, the same `sha256` in the log and in `run-result.json` is what makes the failure diagnosable without downloading images.

#### The capture profile (why a shot is reproducible)

Minecheck pins the settings that decide what a frame looks like, so the same script on the same machine produces the **same picture** every run. The Gradle plugin writes them into the run's `options.txt`, and `config.json` can override the four starred ones for the run:

| Setting | Pinned value | `config.json` field |
|---------|--------------|---------------------|
| Window size | `854 × 480` | `screenshotWidth` / `screenshotHeight` |
| GUI scale | `2` (never auto) | `screenshotGuiScale` |
| Encode downscale | `1.0` (full size) | `screenshotScale` |
| Chat in captures | cleared one frame before the read | `screenshotChatVisible` |
| Graphics mode / AO / entity shadows / biome blend | fancy / on / on / `0` | — |
| FOV, FOV effects, screen effects, view bobbing | `70`, `0`, `0`, off | — |
| Particles / clouds | minimal / off | — |
| VSync / FPS cap | off / `120` | — |
| Gamma / fullscreen | `0.5` / windowed | — |
| Language, subtitles, F3 overlay, tutorial toasts | `en_us`, off, off, off | — |
| Render / simulation distance | `10` | — |

Chat is the one HUD element a script can make nondeterministic (a line of text whose arrival depends on timing, command feedback from the scene setup among them), so a capture without chat **empties the chat lines and reads the first frame drawn after that**. It does that by emptying the HUD rather than by switching chat off in the options: the server refuses every client command while chat visibility is hidden (`chat.disabled.options`), and a suite that cannot run commands cannot set up a scene. `screenshotChatVisible: true` leaves the lines in — the resulting captures are then only as stable as the chat.

Two things are deliberately **not** part of the profile: time of day and weather. They belong to the world the script creates (`createWorld` already pins the seed and disables the daylight/weather cycle), because a profile that changed the world at capture time would make a baseline depend on capture order.

The **sky is the one thing a script should keep out of a baseline**. Minecheck pins the two inputs that would otherwise move it on their own: the client's clock is re-synced to the server's every tick, and the *partial tick* every frame is drawn at is fixed, so the sun angle, the sky gradient and the lightmap that modulates every lit surface are a function of the level's state rather than of when the frame happened to land.

What is left is the level's own lighting easing in. Measured on the shipped suite's world, the frame is nine light levels brighter ten seconds after the world is created than half a second after it, and no two runs arrive at that point after the same number of frames. A scene therefore has to settle on its *own picture* — capture, wait, capture again, and only shoot once two digests agree — rather than on a fixed wait. That is why the shipped suite looks straight down (ground fills the frame) for its world capture and pins the time of day with `/time set` (noon and midnight, where the sky-light curve is flat) — and why the comparison can stay byte-for-byte. The title screen is avoided for the same reason: its background panorama rotates with every rendered frame.

A capture also pins nothing about the **camera** for you. The view is part of the picture, so a deterministic scene sets it explicitly — a fresh world spawns the player at a random spot near the world spawn, and two runs standing a block apart see different terrain:

```lua
minecheck.runCommand("tp @s 0.5 -60 0.5 0 90")  -- position and rotation (yaw 0, pitch 90 = down)
```

What the profile does **not** do is wait for the scene to settle. The action captures the frame that is there; a script that wants a stable picture must already be there —

```lua
minecheck.createWorld({ name = "minecheck_world", seed = 0 })
minecheck.waitFor(minecheck.worldGenSettled, 120)
minecheck.runCommand("time set 6000")
minecheck.runCommand("tp @s 0.5 -60 0.5 0 90")

-- Settle on the picture, not on a timeout: two captures a second apart that agree mean the frame
-- has stopped easing. A leading `_` keeps these probes out of the baselines, and every probe needs
-- its own name.
local settleIndex = 0
local function digest()
  settleIndex = settleIndex + 1
  return minecheck.captureScreenshot("_settle-" .. settleIndex).sha256
end

local function settled()
  local previous = digest()
  for _ = 1, 30 do
    minecheck.wait(1)
    local current = digest()
    if current == previous then
      return true
    end
    previous = current
  end
  return false
end

minecheck.expect(settled(), "the frame stopped changing")
minecheck.captureScreenshot("world-settled")
```

#### Errors

Every failure is a Lua error naming the action:

| Situation | Message |
|-----------|---------|
| A dedicated server calls it | `client-only function called on dedicated server` |
| No framebuffer | `captureScreenshot: no client framebuffer — is the client in a world?` |
| Duplicate name in one run | `captureScreenshot: "world-settled" was already captured in this run` |
| Bad name | `captureScreenshot: invalid capture name "a/b"` |
| Bad region | `captureScreenshot: region needs numeric x, y, w, h` / `… has no overlap with the 854x480 frame` |
| Unknown option or region field | `captureScreenshot: unknown option "hideHud"` |
| Readback/encode failure | `captureScreenshot: framebuffer read failed: <cause>` |
| Timeout (never yielded a frame) | `captureScreenshot: timed out after 30s waiting for the frame` |

Two names can never be used twice in a run: a second capture would either overwrite a baseline's proof or hide which call produced the file.

#### Where captures go, and the baselines that check them

Captures land in the run directory: `src/<loader>/mod/run/client/screenshots/<name>.png`. The suite's committed baselines live in `tests/baselines/<loader>/<name>.png` and are compared by the loader's `test<Loader>Shots` Gradle task after `run<Loader>Shots` produced them. Local runs compare against `tests/local-baselines/<loader>/` (git-ignored) instead, because a picture rendered by a different GPU than CI's says nothing about the code. The two trees are **not interchangeable**: the committed one only ever holds pictures the pinned CI image produced, and only CI compares against it.

The comparison is **byte for byte** — same file or a failure, no tolerance:

```bash
./gradlew testFabricShots                                        # capture + compare (local baseline tree)
./gradlew testFabricShots -Pminecheck.screenshots.update=true    # re-baseline into the active tree
./gradlew testFabricShots -Pminecheck.baselines=ci               # compare against the committed tree
```

A tolerance would spend its allowance on exactly the differences a baseline exists to catch (a HUD pixel that moved, a texture edge that shifted, a half-transparent overlay), and every run would turn into a debate about how much drift is acceptable. The cost of exactness is that a scene has to be static by construction — settled, lit by a flat part of the sky-light curve, camera pinned — which is the script's job, not the comparison's.

A missing baseline is written and **fails** the task (review it, then commit it). A baseline whose capture no longer runs is a warning, not a failure. The captures, the run's `run-result.json` and the baseline trees are uploaded as the `screenshots` artifact on every CI run, so a red build comes with the picture that broke it.

---

## Packets (both sides)

The client and the dedicated server are separate processes with a real connection, so they coordinate over **Minecheck packets** on the `minecheck:main` channel — no files, no extra ports. Because the schema is public and stable, Minecheck doubles as a packet-testing harness for other mods that route payloads through its channel.

These actions work on both sides, which is what lets one script body express a client↔server exchange.

| Lua value | wire type |
|-----------|-----------|
| `nil` | `nil` |
| `true` / `false` | `bool` |
| integer number (fits 32 bits) | `i32` |
| integer number (fits 53 bits) | `i64` |
| fractional number | `f64` |
| string | `string` |
| `minecheck.bytes(s)` | `bytes` |
| [int64](#64-bit-integers) | `i64` when the value is outside the `i32` range, else `i32` |

A plain integer number larger than 2^53 cannot be expressed in Lua at all, and is sent as `f64` rather than as a rounded integer; use `minecheck.int64` when the low bits matter. The value is exact in both directions: an incoming `i64` arrives as an int64 and a small one arrives as a plain number.

> **The received Lua type follows the magnitude, not the sender's type.** An integer is sent with the smallest tag that holds it, so `minecheck.int64("5")` travels as `i32` and the peer's script sees the number `5` — while `minecheck.int64("72623859790382856")` travels as `i64` and the peer sees an int64. Nothing is rounded either way, but do not branch on `type(v)`: compare with `==`/`tostring` instead.

Incoming bytes arrive as a plain Lua string — Lua strings *are* byte sequences, so a payload can be indexed, sliced and compared directly. A value whose tag this Minecheck build does not know is skipped (and logged) rather than failing the packet, so a newer peer cannot break an older reader.

> **Handlers must not yield.** `onPacket` handlers and channel decoders run on the game thread between script steps. Calling `sleep`/`waitFor`/any waiting action inside one would park the game thread; do that work in the main script body instead. An error inside a handler fails the run with a clear message.

### `minecheck.sendPacket(name, ...) → boolean`

Sends a packet: client → server, or server → every connected player. Returns `false` when the channel is unavailable (loader has no networking, not connected yet, no players online).

```lua
minecheck.sendPacket("hello", "ping", 42, true, 2.5, minecheck.bytes("\1\2\3"))
```

### `minecheck.sendPacketTo(playerName, name, ...) → boolean`

Server: sends a packet to one online player. Returns `false` when that player is not online — also on a client, which has no player list to address.

### `minecheck.waitForPacket(name[, predicate][, timeout]) → table | false`

Waits for a packet with that name and returns it as `{ name = "…", args = { … } }` (truthy), or `false` on timeout. The history is not consumed, so a packet that arrived **before** the call (a reply that beat the wait loop) still satisfies it — and re-waiting on the same name returns it again.

When the loader registered **no transport at all**, a packet can never arrive, so this returns `false` at once instead of sitting out the timeout. A channel that merely **is not up yet** is different and still waits — it can come up — so on a client it is worth waiting through the connect.

`predicate` is called with the packet's arguments and gates the match, so repeated packets can be told apart:

```lua
local pong = minecheck.waitForPacket("pong", 60)
local ok = minecheck.waitForPacket("damage", function(amount) return amount > 10 end, 30)
```

### `minecheck.onPacket(name, handler) → nil`

Registers a handler invoked with the packet's arguments whenever a packet with that name is received. Register it **before** the packet can arrive — the handler then observes it even if the script is busy elsewhere.

```lua
local pushed = nil
minecheck.onPacket("push", function(value)
  pushed = value
end)
minecheck.expect(minecheck.waitFor(function() return pushed ~= nil end, 60), "push arrived")
```

### `minecheck.watchPacketChannel(channel) → boolean`

Opts in to observing **another mod's** channel: each payload is handed to that channel's decoder and then behaves like a packet named after the channel. The return value is a claim about *this* channel, not a capability flag: `false` means the channel cannot be observed here, so branch on it and treat it as "not observable" rather than waiting for traffic that will never come.

| Loader | Behaviour |
|--------|-----------|
| Fabric / Quilt | Registers a play-channel receiver for that channel. Fabric API keeps **exactly one handler per channel per connection** — a second registration for the same channel is refused — so this succeeds only for a channel nothing else receives. Minecheck checks it against the live connection (and against every online player on a dedicated server), so a channel that another mod already receives reports `false` instead of installing a handler that is silently shadowed. Minecheck's own `minecheck:main` is claimed by its own transport, so it is never observable this way. |
| Forge (client) | Installs a low-level frame tap *below* the play connection: FML's `SimpleChannel` is single-receiver per direction too, but the tap copies each decoded custom payload's bytes as they pass, so it can observe a channel that something else already receives — `minecheck:main` included. Only the **clientbound** direction is captured. Returns `false` when there is no play connection yet. |
| Forge (dedicated server) | Taps every **player connection** the same way, capturing the **serverbound** direction (what clients send). Connections that appear later are picked up at login, so a channel can be watched before the client joins. Returns `false` only when no server is running yet. |

> **Observing a foreign channel in practice.** On Fabric/Quilt a mod that already receives its own channel cannot be observed: its handler owns the channel for that connection, and Fabric's API has no way to share or chain one. That is a rule of the loader's networking, not a Minecheck limitation, and it is why the Forge tap sits *below* the connection instead. Two consequences: traffic you control is better carried on Minecheck's own channel (`sendPacket`/`waitForPacket`), and a channel is only observable on Fabric/Quilt while nothing else receives on that side of the connection. Use [`isModLoaded`](#minecheckismodloadedmodid--boolean) to tell "the mod is absent" from "the mod owns its channel". |

### `minecheck.registerPacketDecoder(channel, decoderFn) → nil`

Gives the script the raw payload bytes of an observed channel and takes back whatever shape it wants. The decoder's **return value** becomes the packet's single argument.

```lua
if minecheck.watchPacketChannel("other-mod:net") then
  minecheck.registerPacketDecoder("other-mod:net", function(bytes)
    local r = minecheck.reader(bytes)
    return { kind = r.readVarInt(), text = r.readString() }
  end)
end
```

### `minecheck.bytes(value) → table`

Marks a Lua string as raw bytes for `sendPacket` (a plain string is sent as `string`). The marker is opaque; it exists only because Lua has one string type.

A **number** is accepted and coerced to its decimal digits (`minecheck.bytes(5)` is the single byte `'5'`, not the byte `5`) — Lua coerce numbers to strings, and LuaJ reports a number as a string. Every other type raises `bytes: expected a string`. Check `type(v) == "string"` if you need to be sure which you have.

### `minecheck.int64(value) → int64`

An exact 64-bit integer, for values a Lua number cannot hold — see [64-bit integers](#64-bit-integers). A string is parsed exactly; a number is converted, which is exact up to 2^53, so pass the string when all 64 bits matter.

```lua
minecheck.sendPacket("seed", minecheck.int64("72623859790382856"))
```

Invalid input raises `int64: not a 64-bit integer`.

### `minecheck.reader(bytes) → table`

A cursor over a byte string, for decoding another mod's payload. Reading past the end raises a Lua error naming the method.

| Method | Reads |
|--------|-------|
| `readByte()` | One unsigned byte (`0..255`). |
| `readVarInt()` | Minecraft VarInt. |
| `readZigZag()` | Signed value, ZigZag-encoded VarInt. |
| `readInt()` | Fixed big-endian 32-bit integer. |
| `readLong()` | Fixed big-endian 64-bit integer, as an exact [int64](#64-bit-integers) — a Lua number would round it. |
| `readFloat()` / `readDouble()` | Fixed big-endian float/double. |
| `readBool()` | One byte (`0` = false). |
| `readString()` | VarInt byte length then UTF-8 (Minecraft's `writeUtf`). |
| `readBytes(n)` | `n` raw bytes. |
| `readBytesVarInt()` | VarInt byte length then that many bytes. |
| `bytes()` | Everything left. |
| `remaining()` / `offset()` | Bytes left / current cursor position. |

```lua
local r = minecheck.reader(payload)
local version = r.readVarInt()
local name = r.readString()
local rest = r.bytes()
```

---

## Live session (both sides)

A script can open a TCP listener on the loopback interface and serve a program on the same machine — a shell, an editor plugin, another agent — while the game keeps running. This is how you drive a run interactively instead of editing a script and relaunching.

The listener belongs to the script: nothing listens unless the script asks, the port is logged when it binds (the only way to find an ephemeral one), and every handle still open when the script finishes or fails is closed by the engine. All waiting calls yield per tick like every other action, so an idle listener costs one poll per tick and never stalls the game.

### `minecheck.listen([port][, host]) → listener`

| Parameter | Type | Description |
|-----------|------|-------------|
| `port` | number \| nil | Port to bind; `0` (default) asks the OS for an ephemeral one. |
| `host` | string \| nil | Interface to bind; defaults to `127.0.0.1`. |

Binds and logs `Minecheck: live listening on <host>:<port>` at INFO. Raises a Lua error when the address cannot be bound (port already in use).

**Listener methods:**

| Method | Returns | Description |
|--------|---------|-------------|
| `listener:port()` | number | The bound port (the real one, for `listen(0)`). |
| `listener:accept([timeout])` | connection \| nil | Waits for a client (`timeout` = `config.defaultTimeout`); nil on timeout. |
| `listener:isOpen()` | boolean | False once closed. |
| `listener:close()` | nil | Stops listening; idempotent. |

**Connection methods:**

| Method | Returns | Description |
|--------|---------|-------------|
| `conn:recv([timeout])` | string \| nil | One line, without its `\n` (or `\r\n`); nil on timeout **or** when the peer closed — `conn:isOpen()` tells them apart. |
| `conn:send(text)` | boolean | Writes `text` plus a newline; false when the peer is gone. |
| `conn:peer()` | string | The peer's `host:port`. |
| `conn:isOpen()` | boolean | True until the peer closes or you close it. |
| `conn:close()` | nil | Closes the connection; idempotent. |

Frames are UTF-8 lines, capped at 1 MiB: a peer that never sends a newline raises instead of growing the buffer. The protocol on top is the script's own — the socket only carries lines.

```lua
local live = minecheck.listen(0) -- logs the port it got
local conn = live:accept(60)
if conn then
  local line = conn:recv(60)
  conn:send("received: " .. tostring(line))
  conn:close()
end
live:close()
```

### `minecheck.liveAttach([port][, timeout]) → number | nil`

The builtin served breakpoint, on top of the two calls above: listen, log the port, accept one client and serve it until it disconnects, then return the number of chunks served (nil when nobody attached in time).

Each line the client sends is a **Lua chunk evaluated in the running script's globals**, so a chunk can read the script's own state and call any `minecheck` action; the reply is one line, `ok <value>` or `err <message>`. Because the script is parked inside this call, a chunk that blocks (`sleep`, `waitFor`, `joinServer`) suspends the script again and resumes when its condition holds — the game ticks on, and the client waits for that chunk's reply.

```lua
minecheck.log("paused for live input")
local served = minecheck.liveAttach(0, 120) -- port in the log; 120 s to attach
minecheck.log("served " .. tostring(served) .. " chunk(s)")
```

Then, from a shell:

```console
$ nc 127.0.0.1 43521
return minecheck.getPlayer().pos.y
ok 64.0
minecheck.captureScreenshot("from-live")
ok
```

---

## Assertions and result reporting

### `minecheck.expect(cond[, name]) → boolean`

Records a named pass/fail expectation into the run result. **Never aborts the script** — always returns the boolean so you can branch on it.

```lua
local ok = minecheck.expect(someCondition, "my check")
if not ok then
    minecheck.fail("my check failed")
end
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `cond` | any | Evaluated as boolean (`nil`/`false` = fail, anything else = pass). |
| `name` | string | Label for the run result (default: `"expect #<count>"`). |

**Returns:** `true` if `cond` was truthy, `false` otherwise.

---

### `minecheck.fail(message) → never`

Unconditionally marks the current run **failed** with the given message and stops the script. What happens next depends on [`config.failMode`](#configjson):

- `"exit"` (default) — the client quits (`MinecraftClient.stop()`) or the
  dedicated server issues `stop` via the console. The JVM exits cleanly.
- `"continue"` — the script is stopped but the game process stays alive.

| Parameter | Type | Description |
|-----------|------|-------------|
| `message` | string | Failure description (appears in `run-result.json`). |

**Returns:** never (script is terminated).

---

## Utilities

### `minecheck.log(message[, level]) → nil`

Writes a message to the game log under the `Minecheck` logger.

| Parameter | Type | Description |
|-----------|------|-------------|
| `message` | string | Log text. |
| `level` | string | `"info"` (default), `"warn"`, or `"error"`. |

---

### `minecheck.sleep(seconds) → nil`

Sleeps for the given number of seconds (tick-driven, not wall-clock).

| Parameter | Type | Description |
|-----------|------|-------------|
| `seconds` | number | Duration to sleep (required, non-negative). |

---

### `minecheck.wait([seconds]) → nil`

Like `sleep`, but the duration is optional: omit it (or pass `0`) to wait exactly **one tick** — the cheap way to let the world move a frame without guessing a duration.

```lua
minecheck.wait()      -- one tick
minecheck.wait(0.5)   -- half a second
```

---

### `minecheck.waitFor(conditionFn[, timeout]) → boolean`

Polls a Lua function every tick until it returns truthy.

| Parameter | Type | Description |
|-----------|------|-------------|
| `conditionFn` | function | Called each tick; must return a truthy value to stop. |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` when the condition is met. `false` on timeout.

Also exposed as **`minecheck.sleepUntil(conditionFn[, timeout])`** — "sleep until this is true", identical semantics.

```lua
local ok = minecheck.waitFor(function()
    return minecheck.isInWorld()
end, 120)
```

---

### `minecheck.worldGenSettled() → boolean`

True once world chunk generation has settled (no pending chunk work). Designed to be driven by [`minecheck.waitFor`](#minecheckwaitforconditionfn-timeout--boolean): pass it as the condition function and it is re-polled every tick.

| Side | Behavior |
|------|----------|
| Client, in a local/integrated world | `true` once the integrated server's spawn-region (overworld) generation has no pending chunks — including the post-join view-distance re-prep that otherwise leaves the world saved mid-generation. |
| Client, not in a local world (title / connecting) | Returns `true` immediately and logs a console warning. |
| Dedicated server | `true` once that server's own overworld generation has no pending chunks. |

**Returns:** `boolean`.

Typical use: settle worldgen before leaving a freshly-created world, so the save captures fully-generated spawn chunks instead of re-generating them on rejoin:

```lua
minecheck.createWorld({ name = "minecheck_world" })
minecheck.waitFor(minecheck.worldGenSettled, 90)
minecheck.leaveWorld()
```

---

### `minecheck.side() → string`

Returns `"client"` or `"server"` depending on which side the script is running on. Useful for a single coordinator script shared by both.

---

### `minecheck.minecraftVersion() → string`

Returns the Minecraft version string, e.g. `"1.18.2"`.

---

### `minecheck.config() → table`

Returns a read-only snapshot of the current runtime config:

```lua
{
    script         = "my-script.lua",   -- never nil: "" when none is set
    coordinator    = "coordinator.lua", -- nil when none is set (the key is then absent)
    failMode       = "exit",
    defaultTimeout = 60,                 -- number
}
```

`script` is an **empty string**, not `nil`, when the config names no script — including when a coordinator is the entry script, which is what `script` still reports. Only `coordinator` reads as `nil`, so branch on `minecheck.config().script == ""`. Each call returns a fresh table: mutating it does not change the config.

---

## Input (client)

Simulates real keyboard/mouse input through the vanilla input pipeline, so synthetic presses behave exactly like a real player's (keybindings, GUI click handling, held-key movement all apply). Client-only; calling from a server script raises "client-only function called on dedicated server".

All hold-capable actions share one contract:

- With no `hold` and no `until`, the action is a **tap**: the key/button goes down and is released on the next tick — result `{ pressed = true }`.
- With `hold` (number, sec): held down for that long, then released — result `{ pressed = true, held = false, released = "timeout" }`.
- With `until` (function): held down while the predicate is falsy, released when it returns truthy — result `{ pressed = true, held = true, released = "cond" }`.
- With both: released on whichever comes first; `released` reports which one fired.
- If the key/button cannot be resolved (unknown binding id / key name), nothing is pressed and the result is `{ pressed = false, held = false }`.

> **Movement keys are held, not tapped.** Keys like jump/sneak are sampled each movement tick (`isPressed`), so a tap that starts and ends inside one task batch is never seen. Use `press(key, hold)` with a short hold (e.g. `0.25`) for jumps.
>
> **Every hold spans at least one client tick**, even a zero-length one or an `until` predicate that fires on its first call: a down and a release inside a single client tick would never be sampled by the game, so the press would silently do nothing.

### `minecheck.press(key[, hold][, until]) → table`

Simulates a keypress.

| Parameter | Type | Description |
|-----------|------|-------------|
| `key` | string | Key binding id (e.g. `"key.jump"`) — the currently-bound key is used — or a key name (e.g. `"F3"`) lower-cased into the `key.keyboard.*` GLFW namespace. |
| `hold` | number \| nil | Hold the key down for this many seconds. |
| `until` | function \| nil | Release the key when this returns truthy. |

Also accepts the shorthand `press(key, untilFn)`.

> A binding may be bound to a **mouse button** — `key.attack` (left) and `key.use` (right) are, by default — and the press is routed to the mouse handler, so `press("key.attack")` really swings. Bindings bound to a keyboard key go to the keyboard handler as before.

### `minecheck.pressCombination(keys[, hold][, until]) → table`

As `press`, but for a table of keys pressed together, e.g. `{ "key.sneak", "key.jump" }`. **Atomic**: if any key fails to resolve, nothing is pressed and `{ pressed = false }` is returned.

### `minecheck.mouse(button[, hold][, until]) → table`

Mouse button down/up with the same hold/until rules.

| Parameter | Type | Description |
|-----------|------|-------------|
| `button` | number | `0` = left, `1` = right, `2` = middle. |

### `minecheck.keyPress(key) → boolean`

Tap convenience: key down then immediately up. Returns `false` if the key can't be resolved.

### `minecheck.mousePress(button) → boolean`

Tap convenience for a mouse button (`0`/`1`/`2`). Returns `true` once dispatched.

---

## World / entity queries (client)

### `minecheck.getPlayer() → table | nil`

The local player, or `nil` when not in a world:

```lua
{ name = "Dev", pos = { x = 8.5, y = 65.0, z = 8.5 }, dimension = "minecraft:overworld" }
```

### `minecheck.look(pitch, yaw) → boolean`

Sets the camera rotation. `pitch`: `90` = straight down, `-90` = straight up, `0` = horizon. `yaw`: `0` = south, positive toward west (vanilla convention).

### `minecheck.lookAt(x, y, z) → boolean`

Points the camera at the given world position (eye-to-target).

### `minecheck.getTargetBlock(entityName) → table | nil`

Raycasts from the named entity's eyes along its look direction and returns the hit block's position `{ x, y, z }`, or `nil` on a miss. Only the local player is resolvable.

### `minecheck.getBlock(pos) → table | nil`

Reads the block at integer position `pos` (`{ x = number, y = number, z = number }`) from the **client** world.

| Parameter | Type | Description |
|-----------|------|-------------|
| `pos` | table | Block position; non-integer coordinates are floored. |

**Returns:**

```lua
{ id = "minecraft:beacon", nbt = { Levels = 4, Primary = 1, x = 8, y = 64, z = -4 } }
{ id = "minecraft:iron_block" } -- nbt is absent: the block has no block entity
nil -- that position is not loaded on the client
```

- `id` — block registry id (`minecraft:air` for air).
- `nbt` — the block entity's NBT compound (same shape as [`getSlot`](#minecheckgetslotindex-id--table)'s `nbt`); `nil`/absent when the block has no block entity.
- `nil` — the client does not know that position: only chunks the server has sent are readable. **Not the same as air** — after a server-side block change, poll until the read is non-`nil` before asserting.

```lua
local target = minecheck.getTargetBlock(minecheck.getPlayer())
local below = minecheck.getBlock({ x = target.x, y = target.y - 1, z = target.z })
assert(below ~= nil and below.id == "minecraft:iron_block", "pyramid block under the beacon")
```

Client-only; calling it from a server script raises `"client-only function called on dedicated server"`.

### `minecheck.findNearbyEntities([type]) → table`

**Client-only**, the client-side counterpart of [`findEntities`](#minecheckfindentitiestype-tag--table). Lists the entities the client currently knows about — the ones the server has synced into the client's loaded chunks, which is exactly what the local player can see and reach. Use it to *target* something: find it, aim at it with `lookAt`, then act (`press("key.attack")`, `mouse(1)`, …).

| Parameter | Type | Description |
|-----------|------|-------------|
| `type` | string | Entity type registry id filter, e.g. `"minecraft:vex"` (default: every entity the client knows). |

**Returns** the same entry shape as `findEntities` (`id`, `type`, `pos`, `tags`), from the client's own view. Outside a world it returns an empty table instead of erroring.

> **No tag filter, and `tags` is always empty here.** Entity scoreboard tags never reach a client in 1.18.2 — no client or network class calls `Entity.addTag` and no packet carries the tag set — so a client-side filter on a tag (or an entry's `tags`) could only ever be empty. Two consequences:
>
> - To tell a client-side target apart from worldgen mobs, filter by a **type that cannot spawn naturally** (the harness's punch test uses `minecraft:vex`), or poll an entry's `id` once the server has told you which one to use.
> - When a scoreboard tag is the handle, do the lookup **server-side** with `findEntities` and coordinate over the packet channel (`sendPacket` / `waitForPacket`).

### Punching a mob (the spec's "punch a mob with a particular tag")

There is no single `punch` action, deliberately: it is the composition of three actions, so a script decides reach, cooldown and retries itself.

```lua
-- a vex never spawns naturally, so its type is a usable client-side handle
local list = minecheck.findNearbyEntities("minecraft:vex")
assert(#list == 1, "the target is on screen")
local t = list[1]

minecheck.lookAt(t.pos.x, t.pos.y + 0.4, t.pos.z) -- pos is the feet; a vex is 0.8 tall
minecheck.press("key.attack", 0.7)               -- hold past the attack cooldown, then release
```

The harness's `query` suite runs exactly this end-to-end: the server spawns and tags a vex, arms the client player (`gamemode survival` plus a sword), cues the client over the packet channel once its own assertions are done, and afterwards proves the target died (`findEntities("minecraft:vex", "punchtarget")` is empty). Two side-split details matter: a **creative** player's attacks deal no damage, and a full-damage swing needs the attack **cooldown** respected — which is what `hold` is for.

### `minecheck.recipeViewer([viewerId]) → table | nil`

Client: the recipe-viewer mod Minecheck is bridged to, or `nil` when no viewer is installed/ready:

```lua
{ id = "emi" }
```

`viewerId` (e.g. `"emi"`, `"jei"`, `"rei"`) reports that specific installed viewer instead of the priority one; `nil` when it is not installed. With several viewers installed, registration order is the priority order (a viewer that can transfer recipes wins).

> **Viewer id vs mod id.** The ids above are Minecheck's viewer ids. They are not always the loader's mod id: REI's mod is `roughlyenoughitems`, so [`isModLoaded`](#minecheckismodloadedmodid--boolean) must be asked for that id (`minecheck.isModLoaded("roughlyenoughitems")`), while `recipeViewer("rei")` selects the bridge.

Recipe viewers are **soft dependencies** — Minecheck never bundles one. A script should treat `nil` as "this feature is unavailable here" rather than an error:

```lua
local viewer = minecheck.recipeViewer()
if viewer == nil then
  minecheck.log("no recipe viewer installed; skipping the recipe checks")
end
```

### `minecheck.getRecipeSlots(recipeId[, viewerId]) → table`

Client: the **ghost** ingredient slots the viewer shows for the recipe with registry id
`recipeId` (e.g. `"minecraft:stick"`), as a list in slot order:

```lua
{
  { index = 0, items = { "minecraft:oak_planks", "minecraft:birch_planks", ... } },
  { index = 1, items = {} }, -- a slot the viewer could not resolve to items
}
```

- `index` — position in the recipe's ingredient list (0-based).
- `items` — the item registry ids that satisfy the slot. A tag-backed slot lists its members, so this is usually longer than one.

Returns an empty table when the recipe is unknown, the viewer has not indexed it, or no viewer is available. `viewerId` reads the named viewer rather than the priority one.

```lua
local slots = minecheck.getRecipeSlots("minecraft:stick")
assert(#slots > 0, "the stick recipe has ghost slots")
```

### `minecheck.fillFromRecipe(recipeId[, maxItems[, viewerId]]) → boolean`

Client: asks the viewer to fill the **currently open container** from the named recipe (the same action as the viewer's own transfer/fill button). `maxItems` caps how many items may be placed; `0` (the default) uses the viewer's own choice. Viewers without an item cap fall back to their default transfer — JEI transfers whole sets, and REI transfers the whole recipe (its transfer handlers take no item cap), so on those `maxItems` is accepted and ignored. `viewerId` drives the named viewer rather than the priority one.

Returns `false` when no viewer is available, the recipe is unknown, no container screen is open, or that container cannot take the recipe.

```lua
minecheck.openScreen("inventory")
if minecheck.fillFromRecipe("minecraft:stick") then
  minecheck.log("viewer filled the container")
end
```

All three are client-only; calling them from a server script raises
`"client-only function called on dedicated server"`.

### `minecheck.isModLoaded(modId) → boolean`

True when the loader has that mod loaded (e.g. `"jei"`), on either side. The way to branch around a soft dependency: `recipeViewer(id)` cannot tell "not installed" from "installed but not ready", while this reports "not installed" exactly.

```lua
if minecheck.isModLoaded("jei") then
  assert(minecheck.recipeViewer("jei") ~= nil, "JEI is installed but not bridged")
end
```

> **Viewer indexes.** Reading slots needs the viewer itself to have indexed the recipe, which Minecheck cannot influence. In a dev client JEI 11.6.0.1024 can come up with an **empty recipe index** while otherwise loading fine: it has its ingredients and categories (measured: 1133 items, 12 categories) but registers 0 recipes — on a local world as well as on a dedicated-server client, and neither a client resource reload ([`reloadResources`](#minecheckreloadresourcestimeout--boolean)) nor a server-side datapack reload (which does restart JEI) changes it. In that state `getRecipeSlots` returns empty for every recipe while `recipeViewer("jei")` still reports JEI as bridged, so treat empty slots as *no data yet*, not as *no viewer* — branch on [`isModLoaded`](#minecheckismodloadedmodid--boolean) for that. EMI and REI index from the client's synced recipes and have no such gap. Each provider logs a one-shot index line on the first read — `JEI index: …` / `REI index: …` — which makes the difference visible in the log.

### `minecheck.isInWorld() → boolean`

True when the client is currently in a world (not on the title screen).

### `minecheck.isOnTitleScreen() → boolean`

True when the client is at the title screen.

---

## Plugin API (for other mods)

Minecheck is extensible: another mod can add its own script actions and wait conditions. The contract lives in `platform-api` (`net.minecheck.platform.api.plugin`) and is **plain Java** — no LuaJ type crosses it, so a plugin never compiles against LuaJ.

### Discovery

A plugin implements `MinecheckPlugin` and declares itself as a `ServiceLoader` provider by shipping

```
META-INF/services/net.minecheck.platform.api.plugin.MinecheckPlugin
```

with one implementation class name per line. Discovery is loader-agnostic — the mod jar shares Minecheck's classpath on Fabric, Quilt and Forge, so no per-loader entrypoint or metadata is needed. It runs once per engine start and is best-effort: a provider file that cannot be read, or a plugin whose `register()` throws, is logged (`SEVERE`) and skipped, with anything it had already registered rolled back. One broken third-party mod cannot break a run.

```java
package com.example.mymod;

public final class MyPlugin implements MinecheckPlugin {
  @Override
  public String id() {
    return "mymod";
  }

  @Override
  public void register(MinecheckRegistry registry) {
    registry.action("greet", call -> PluginStep.done("hello " + call.requireString(1)));
  }
}
```

### Registering

| Call | Meaning |
|------|---------|
| `registry.action(name, action)` | `minecheck.<name>(...)` runs the action. |
| `registry.waitCondition(name, cond)` | `minecheck.<name>(...)` yields each tick until `cond` holds, then returns `true` — or `false` after `config.defaultTimeout` seconds. |
| `registry.dedicatedServer()` | `true` on a dedicated server, `false` on a client — branch on it so a client-only plugin registers nothing on a server (and vice versa). |

Names must be valid Lua identifiers (`[A-Za-z_][A-Za-z0-9_]*`) and unique across every plugin; actions and wait conditions share one namespace. A name that collides with a **built-in** action is refused loudly when the engine starts (`SEVERE: … collides with an existing minecheck.<name> — skipped`) and the built-in wins — no third-party mod can silently shadow `minecheck.log` or `minecheck.expect`.

Every registered name is also indexed under its plugin id, as `minecheck.ext.<id>.<name>`. The `minecheck.ext` table always exists (empty when nothing is installed), so a script can index it without a nil guard.

### Actions

`PluginAction.run(call)` runs on the game thread inside the script's coroutine and returns a `PluginStep`:

- `PluginStep.done()` / `PluginStep.done(result)` — finished; `result` is handed to the script.
- `PluginStep.waiting()` — call me again next tick. This is how a plugin waits (for a chunk, an entity, another mod's event) without blocking the game.

Returning `null`, or throwing, becomes a Lua error that fails the run; return `PluginStep.done(errorValue)` instead when the script should decide.

### Arguments

`PluginCall` is 1-based, matching Lua:

| Lua argument | Java value |
|--------------|------------|
| `nil` / absent | `null` (and `isNil(i)` is true) |
| boolean | `Boolean` |
| number | `Double` |
| string | `String` |
| `{1, 2, 3}` (array) | `List<Object>` |
| any other table | `Map<String, Object>` (keys stringified, values converted recursively) |

Read them with `optString`/`optNumber`/`optBool` (with a fallback), `requireString`/`requireNumber` (which raise a Lua error naming the expected type), `tableArg(i)` for the map form, or `arg(i)` for the untyped value.

A `PluginStep.done(result)` value converts back the same way — `Map`/`List`/array become a table, anything else becomes `toString()` — so a plugin's own value class still shows up in the script rather than silently turning into `nil`. The one exception is `java.lang.Long`, which becomes an exact [int64](#64-bit-integers) rather than a `Double` that would round it past 2^53.

### Wait conditions

`PluginWaitCondition.satisfied(call)` is polled once per game tick, with the script's coroutine yielded in between, so a Lua call blocks the script without blocking the game. It runs on the game thread and must not suspend.

A plugin that registered a `ready` wait condition next to the `greet` action above is used from Lua exactly like a built-in — no `ext` lookup needed when the name is unambiguous:

```lua
assert(minecheck.ready(), "mymod never became ready")
local greeting = minecheck.greet("script")
minecheck.log(greeting)
-- the same action through the namespaced index (useful when two plugins claim similar names)
minecheck.log("namespaced: " .. minecheck.ext.mymod.greet("again"))
```

---

## Run result JSON

Every run writes `config/minecheck/run-result.json` when the script finishes (or fails). The Gradle plugin reads this to determine pass/fail:

```json
{
    "script": "coordinator.lua",
    "finished": true,
    "failed": false,
    "expects": [
        { "name": "create world", "ok": true },
        { "name": "rejoin world", "ok": true },
        { "name": "join+leave server", "ok": true }
    ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `script` | string | Entry script filename. |
| `finished` | bool | `true` if the script ran to the end (or hit a terminal `fail`). |
| `failed` | bool | `true` if `fail` was called or a Lua error occurred. |
| `failureMessage` | string | First failure message (from `fail()`). **Omitted entirely when there is none** — Gson drops nulls, so a passing run has no `failureMessage` key at all. |
| `expects` | array | All recorded expectations, in order, each `{ name, ok }`. |

There is no `passed`/`total` pair in the file: the plugin counts them from `expects` when it reads the result.

The plugin's verdict, in full:

- A run **passes** when `finished == true`, `failed == false`, and every recorded `expect` is `ok`. A run that finished but *lost* an expectation is not green — that is how `PASSED (6/7)` used to slip through.
- A missing `run-result.json` is **always** a failure, including in a suite declared to expect failure: a run that reports nothing has proved nothing.
- When the phase is paired with a server script, that server's result is required too. "Secondary" describes its role in the pairing, not whether its result is checked.
- A suite declared `expectFailure` passes only when the run did not pass (unfinished, failed, **or** a lost expectation), and fails outright when the run genuinely passed.

---

## Coordinators

A coordinator is a multi-phase Lua script that uses a **single long-lived client session** to run several actions in sequence, returning to the title screen between phases via `minecheck.disconnect()`. This avoids restarting the client between scenarios — there is no clean-state requirement.

```lua
local function runPhase(name, fn)
    minecheck.log("phase: " .. name)
    local ok, err = pcall(fn)
    minecheck.expect(ok, name)
    if ok then
        local d = minecheck.disconnect(30)
        minecheck.expect(d, name .. " (back to title)")
    else
        minecheck.log("phase failed: " .. tostring(err), "error")
        return false
    end
    return true
end

-- Phase A: create a world, leave it
runPhase("create world", function()
    local c = minecheck.createWorld({ name = "minecheck_world" })
    if not c then minecheck.fail("createWorld failed") end
end)

-- Phase B: rejoin the saved world, leave it
runPhase("rejoin world", function()
    local j = minecheck.joinLocalWorld("minecheck_world", 90)
    if not j then minecheck.fail("joinLocalWorld failed") end
end)

-- Phase C: join the parallel dedicated server, leave it
runPhase("join+leave server", function()
    local j = minecheck.joinServer("localhost:25565", 120)
    if not j then minecheck.fail("joinServer failed") end
end)

minecheck.quit()  -- shut down once all phases are done
```

Key points:

- **`disconnect` not `quit`** between phases — `quit` shuts down the process.
- **`pcall` around each phase** — a failing phase is recorded and skipped; the next phase continues.
- **One process, one session** — no restart overhead; the title screen is the only clean-state boundary.

---

## Platform notes

| Feature | Fabric | Quilt | Forge |
|---------|--------|-------|-------|
| `joinLocalWorld` | ✅ | ✅ | ✅ |
| `createWorld` | ✅ | ✅ | ✅ |
| `joinServer` | ✅ with retry | ✅ with retry | ✅ with retry |
| `disconnect` | ✅ | ✅ | ✅ |
| `quit` | ✅ | ✅ | ✅ |
| `runCommand` | ✅ | ✅ | ✅ |
| `waitForPlayer` / `waitForPlayerLeave` | ✅ | ✅ | ✅ |
| `spawnEntity` / `spawnBlock` / `spawnItemDrop` / `spawnStructure` | ✅ | ✅ | ✅ |
| `findEntities` (server) + `findNearbyEntities` (client) | ✅ | ✅ | ✅ |
| `openScreen` / `isScreenOpen` / `slotCount` / `getSlot` / `clickGui` / `moveItem` / `typeText` | ✅ | ✅ | ✅ |
| `press` / `pressCombination` / `mouse` / `keyPress` / `mousePress` | ✅ | ✅ | ✅ |
| `getPlayer` / `look` / `lookAt` / `getTargetBlock` / `getBlock` | ✅ | ✅ | ✅ |
| `isModLoaded` | ✅ | ✅ | ✅ |
| `recipeViewer` / `getRecipeSlots` / `fillFromRecipe` (soft dep on a viewer) | ✅ EMI, JEI, REI | ✅ EMI | ✅ EMI, JEI, REI |
| `watchPacketChannel` (another mod's channel) | ✅ only channels nothing else receives | ✅ only channels nothing else receives | ✅ frame tap below the channel (client: clientbound; server: serverbound) |
| `config` / `side` / `minecraftVersion` / `wait` / `sleepUntil` / `keyPress` / `lookAt` / `mouse` / `pressCombination` / `sendPacketTo` | ✅ | ✅ | ✅ |
| Third-party plugins (`minecheck.ext`, `ServiceLoader` discovery) | ✅ | ✅ | ✅ |
| Headless client (Xvfb) | ✅ | ✅ | ✅ |

Every action in this document is exercised end-to-end by a lifecycle suite on all three loaders — none is only unit-tested. The `test*Input` suite carries the client-side odds and ends (`config`, `side`, `minecraftVersion`, `wait`, `sleepUntil`, `lookAt`, `mouse`, `pressCombination`, `keyPress`, 35 expects), and `test*Packet` covers `sendPacketTo`, `watchPacketChannel`'s per-loader answer and `side`/`config` on the dedicated server (15 client + 17 server expects). The `watchPacketChannel` pair is the one place a script asserts a **loader-dependent** result: a free channel must be observable everywhere, while Minecheck's own already-owned channel must be refused on Fabric/Quilt and tapped on Forge (the script compares against `isModLoaded("forge")`).

All functions share a single implementation in `common/`; loader-specific platform adapters live in each loader module and are selected at boot time.

The `joinServer` retry loop (throttled at 2 s) was added to handle Forge's "server is still starting" rejection during the dedicated-server boot — it retries until the connection is accepted or the timeout expires.
