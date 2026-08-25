# Minecheck

A development-helper Minecraft mod that automates the Minecraft client and dedicated server for testing and debugging mods: scripted world setup, entity and block spawning with NBT, GUI and inventory automation, input simulation, and CI-friendly runs — all driven by Lua scripts, from a single Java codebase across Forge, Fabric, and Quilt.

## What it does

- **Client mode** — boots the client (headless for CI, visible for manual work), runs scripted actions (likely starts by entering a world, be it a newly generated one, pre-existing local world, or joining a server): punch a tagged mob, open a GUI, move items between inventory slots, click GUI elements, simulate keypresses with optional hold, wait on conditions, etc.
- **Dedicated-server mode** — when run without a client, scripted server actions: spawn entities, blocks, item drops, or structures with full NBT, run console commands, pause until a specific player joins, stop the server, etc.
- **Harness** — a coordinated run where a server script starts a dedicated server and a client script joins it on localhost. The primary CI workflow.
- **Scripting** — Lua 5.2 (embedded LuaJ) through a single `minecheck` action library.
- **Plugin API** — other mods can register their own script actions and wait conditions.

## Building

### Requirements

- JDK 17+ (JDK 21 recommended)
- No external dependencies — Gradle wrapper included

### Build everything

```sh
./gradlew buildAll
```

### Build a single loader module

```sh
./gradlew :fabric-mod:build
```

### Collect distributable JARs

```sh
./gradlew collectArtifacts
```

### Testing:

```sh
./gradlew testFabric
./gradlew testForge
./gradlew testQuilt
./gradlew test
```

## Lua API

All scripted actions live on the global `minecheck` table. The full reference is in [docs/lua-api.md](docs/lua-api.md).

<details>
<summary><strong>Minimal example</strong></summary>

```lua
-- Join a freshly created world, verify it loaded, then quit.
local ok = minecheck.createWorld({ name = "minecheck_world" })
assert(ok, "createWorld failed")

local inWorld = minecheck.waitFor(function()
    return minecheck.isInWorld()
end, 60)
assert(inWorld, "never entered the world")

minecheck.expect(true, "world loaded")
print("Minecraft version: " .. minecheck.minecraftVersion())
print("Running on: " .. minecheck.side())

minecheck.leaveWorld()
minecheck.quit()
```

</details>

## License

[GPLv3](LICENSE.txt)
