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

The mod also accepts a JVM system property `-Dminecheck.script=<name>` to override the entry script at launch time.

---

## World entry / exit

### `minecheck.joinLocalWorld(name[, timeout]) → boolean`

Joins an existing singleplayer world by its folder name (under the client's saves directory). Polls until `isInWorld()` is true.

| Parameter | Type | Description |
|-----------|------|-------------|
| `name` | string | World folder name, e.g. `"minecheck_world"`. |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once in world. `false` on timeout.

---

### `minecheck.createWorld(options[, timeout]) → boolean`

Creates a new **client-side** local world and joins it. Deterministic superflat by default (no structures, no mob spawning, seed `0`) — the full normal world-gen options are exposed for flexibility:

```lua
minecheck.createWorld({
    name = "minecheck_world",   -- folder name (default)
    seed = 0,                   -- number | string; string.hashCode() if not parseable
    gamemode = "creative",      -- "survival" | "creative" | "adventure" | "spectator"
    difficulty = "normal",      -- "peaceful" | "easy" | "normal" | "hard"
    structures = false,         -- true | false
    mobSpawning = false,        -- true | false
    allowCheats = true,         -- true | false
})
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `options` | table | World options (all fields optional; see table above). |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once in world. `false` on timeout.

> **Server note:** There is no server-side `createWorld`. 
> The dedicated server generates its world using the normal server jar + installed world-gen mods.
> Determinism comes from a pinned `level-seed` in `server.properties`.

---

### `minecheck.joinServer(address[, timeout]) → boolean`

Joins a dedicated server over real TCP networking. Automatically **retries** the connection (throttled to 2 s) when the server is still booting (Forge rejects connections with "server is still starting" until the `Done` line).

| Parameter | Type | Description |
|-----------|------|-------------|
| `address` | string | `"host:port"`, e.g. `"localhost:25565"`. |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` once connected and in-world. `false` on timeout.

---

### `minecheck.leaveWorld([timeout]) → boolean`

Leaves the current world or server and returns to the **title screen**. Waits until `isInWorld()` is false.

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

Executes a console command on the dedicated server (no leading `/` needed).

| Parameter | Type | Description |
|-----------|------|-------------|
| `cmd` | string | The command, e.g. `"stop"`, `"tp @p 0 64 0"`. |

**Returns:**

```lua
{ success = true, result = <int>, error = nil }
```

- `success` — always `true` (errors are thrown as Lua errors instead).
- `result` — number of targets affected (vanilla convention; `0` for commands
  like `stop` that don't target anyone).

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

### `minecheck.waitFor(conditionFn[, timeout]) → boolean`

Polls a Lua function every tick until it returns truthy.

| Parameter | Type | Description |
|-----------|------|-------------|
| `conditionFn` | function | Called each tick; must return a truthy value to stop. |
| `timeout` | number | Max seconds to wait (default: `config.defaultTimeout`). |

**Returns:** `true` when the condition is met. `false` on timeout.

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

Returns the Minecraft version string, e.g. `"1.19.2"`.

---

### `minecheck.config() → table`

Returns a read-only view of the current runtime config:

```lua
{
    script         = "my-script.lua",   -- or nil if using coordinator
    coordinator    = "coordinator.lua", -- or nil
    failMode       = "exit",
    defaultTimeout = 60,
}
```

---

## Run result JSON

Every run writes `config/minecheck/run-result.json` when the script finishes (or fails). The Gradle plugin reads this to determine pass/fail:

```json
{
    "script": "coordinator.lua",
    "finished": true,
    "failed": false,
    "failureMessage": null,
    "expects": [
        { "name": "create world", "ok": true },
        { "name": "rejoin world", "ok": true },
        { "name": "join+leave server", "ok": true }
    ],
    "passed": 3,
    "total": 3
}
```

| Field | Type | Description |
|-------|------|-------------|
| `script` | string | Entry script filename. |
| `finished` | bool | `true` if the script ran to the end (or hit a terminal `fail`). |
| `failed` | bool | `true` if any `expect` recorded a failure, `fail` was called, or a Lua error occurred. |
| `failureMessage` | string \| null | First failure message (from `fail()`). |
| `expects` | array | All recorded expectations, in order. |
| `passed` | int | Count of expects where `ok = true`. |
| `total` | int | Total number of expects recorded. |

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
| Headless client (Xvfb) | ✅ | ✅ | ✅ |

All functions share a single implementation in `common/`; loader-specific platform adapters live in each loader module and are selected at boot time.

The `joinServer` retry loop (throttled at 2 s) was added to handle Forge's "server is still starting" rejection during the dedicated-server boot — it retries until the connection is accepted or the timeout expires.
