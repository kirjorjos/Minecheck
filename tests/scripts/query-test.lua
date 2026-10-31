-- Dedicated-server script (entity query integration test):
--  Wait for the CI player to join, place a platform in the player's loaded ticking chunk, spawn a NoAI cow on it, tag it with a unique tag, and verify minecheck.findEntities returns it by id / type / tag / position. Also asserts the deterministic gamerules the loader applies at server start.
--
-- Worldgen may leave natural mobs in the generated chunks, so every assertion targets the tagged cow we spawn (never whole-world counts).
local player = "Dev"
local joined = minecheck.waitForPlayer(player, 180)
if not joined then
  minecheck.fail("player " .. player .. " never joined the query-test server")
end

local settled = minecheck.waitFor(minecheck.worldGenSettled, 180)
if not settled then
  minecheck.fail("worldgen did not settle before spawning")
end

local function check(result, name)
  minecheck.expect(result, name)
  if not result then
    minecheck.fail(name)
  end
end

local function len(t)
  local n = 0
  for _ in pairs(t) do
    n = n + 1
  end
  return n
end

local function hasEntity(list, id)
  for _, e in pairs(list) do
    if e.id == id then
      return e
    end
  end
  return nil
end

-- Deterministic-world gamerules are applied by the loader at server start.
-- {@code gamerule <name>} returns 1 while the rule is true, 0 once false.
local function ruleIsFalse(name)
  return minecheck.runCommand("gamerule " .. name).result == 0
end
check(ruleIsFalse("doMobSpawning"), "doMobSpawning is false")
check(ruleIsFalse("doDaylightCycle"), "doDaylightCycle is false")
check(ruleIsFalse("doWeatherCycle"), "doWeatherCycle is false")
check(ruleIsFalse("doInsomnia"), "doInsomnia is false")

-- No entity carries the tag yet.
local none = minecheck.findEntities(nil, "querytag")
check(len(none) == 0, "no entity has the tag before tagging")

-- The joined player is an entity too: find them to learn where they stand, then build the platform 2 blocks east so the cow lands in the same loaded ticking chunk as the player (fixed far-away platforms proved unreliable).
local px, py, pz = nil, nil, nil
for _, e in pairs(minecheck.findEntities()) do
  if e.type == "minecraft:player" then
    px, py, pz = e.pos.x, e.pos.y, e.pos.z
    break
  end
end
check(px ~= nil, "joined player is findable with a position")

-- The player is standing on solid ground, but 2 blocks east the terrain may be a hill or tree — spawning there would suffocate the cow. Probe the actual surface from high above: the first non-air block (scanning down) is the top of the highest terrain, and the platform is built on top of it.
local bx = math.floor(px) + 2
local bz = math.floor(pz)
local function isAir(x, y, z)
  return minecheck.runCommand("execute if block " .. x .. " " .. y .. " " .. z .. " minecraft:air").result == 1
end
local surfY = nil
for y = math.floor(py) + 48, -60, -1 do
  if not isAir(bx, y, bz) then
    surfY = y
    break
  end
end
check(surfY ~= nil, "found surface level beside the player")
local platform = minecheck.spawnBlock("minecraft:stone", { x = bx, y = surfY + 1, z = bz }, {}, true)
check(platform.success, "platform block placed beside the player")

-- Spawn the NoAI cow directly ON the platform (feet at surfY + 2): no fall, no suffocation, and NoAI keeps it standing exactly where it lands.
local cow = minecheck.spawnEntity("minecraft:cow", { x = bx + 0.5, y = surfY + 2, z = bz + 0.5 }, { NoAI = true })
check(cow.success, "spawnEntity cow succeeded")
check(cow.id ~= nil and cow.id > 0, "spawnEntity returned an entity id")

-- findEntities by type: the cow's network id shows up.
local byType = minecheck.waitFor(function()
  return hasEntity(minecheck.findEntities("minecraft:cow"), cow.id) ~= nil
end, 10)
check(byType, "findEntities(type) sees the spawned cow by id")

-- Tag the cow (all cows for simplicity; only ours has the id we track).
local tagged = minecheck.runCommand("tag @e[type=minecraft:cow] add querytag")
check(tagged.result >= 1, "tag command ran")

local function ourCow()
  return hasEntity(minecheck.findEntities(nil, "querytag"), cow.id)
end

-- findEntities by tag: exactly our cow (no other entity carries the tag).
local byTag = minecheck.waitFor(function()
  return ourCow() ~= nil
end, 5)
check(byTag, "findEntities(tag) sees the tagged cow")
local taggedInfo = ourCow()
check(taggedInfo ~= nil and taggedInfo.type == "minecraft:cow", "tagged entry reports type")
check(taggedInfo ~= nil and taggedInfo.tags[1] == "querytag", "tagged entry reports the tag")
check(
  taggedInfo ~= nil and taggedInfo.pos.x == bx + 0.5 and taggedInfo.pos.z == bz + 0.5,
  "tagged entry reports the spawn x/z"
)

-- Combined type + tag filter, and a miss for other types.
local both = minecheck.findEntities("minecraft:cow", "querytag")
check(hasEntity(both, cow.id) ~= nil, "findEntities(type, tag) matches the tagged cow")
local misses = minecheck.findEntities("minecraft:zombie", "querytag")
check(len(misses) == 0, "findEntities(type, tag) misses other types")

-- The NoAI cow must not move after settling: two consecutive reads report the same position.
local lastX, lastY, lastZ = nil, nil, nil
local still = minecheck.waitFor(function()
  local e = ourCow()
  if e == nil then
    return false
  end
  if lastX ~= nil and e.pos.x == lastX and e.pos.y == lastY and e.pos.z == lastZ then
    return true
  end
  lastX, lastY, lastZ = e.pos.x, e.pos.y, e.pos.z
  return false
end, 15)
check(still, "findEntities(tag) reports the cow settled and still")

-- --- client punch phase -----------------------------------------------------
-- The spec's "punch a mob with a particular tag" example, driven from the *client*: the client
-- finds the target through its own view of the world, aims at it, and attacks until it dies.
--
-- A vex is the test double because it never spawns naturally in the overworld, so a client-side
-- type query can only match the one we spawn. A scoreboard tag could not be the client-side handle
-- at all: vanilla never syncs entity tags to clients.
local vx = math.floor(px)
local vz = math.floor(pz)
local vy = math.floor(py) + 2 -- Guaranteed air for the spawn (the player's own column above their head; a tree or ceiling there
-- would otherwise swallow the target). `fill` reports the blocks it *changed*, so it is not
-- asserted — an already-empty cell is just as good, and the `execute if block` test below is what
-- actually matters.
minecheck.runCommand(
  "fill " .. vx .. " " .. vy .. " " .. vz .. " " .. vx .. " " .. (vy + 1) .. " " .. vz .. " minecraft:air"
)
check(
  minecheck.runCommand("execute if block " .. vx .. " " .. vy .. " " .. vz .. " minecraft:air").result == 1,
  "target cell is air"
)
local vex = minecheck.spawnEntity(
  "minecraft:vex",
  { x = vx + 0.5, y = vy, z = vz + 0.5 },
  { NoAI = true, NoGravity = true }
)
check(vex.success, "spawnEntity vex succeeded")
check(minecheck.runCommand("tag @e[type=minecraft:vex] add punchtarget").result >= 1, "marked the punch target")
-- Survival plus a real weapon: a creative player's attacks deal no damage, and a bare fist needs ten
-- hits on a vex. `gamemode` reports the players it *changed*, so an already-survival player returns
-- 0 — assert the resulting mode instead.
minecheck.runCommand("gamemode survival " .. player)
check(
  minecheck.runCommand("execute if entity @a[gamemode=survival,name=" .. player .. "]").result == 1,
  "client player is in survival"
)
check(
  minecheck.runCommand("give " .. player .. " minecraft:netherite_sword 1").result >= 1,
  "client player given a sword"
)
-- Knockback immunity: without it every hit pushes the target out from under the crosshair. The
-- attribute command takes a single-entity selector, so `limit=1` is required (`@e[...]` alone is
-- rejected as "allows more than one" even when only one entity matches).
check(
  minecheck.runCommand(
    "attribute @e[type=minecraft:vex,tag=punchtarget,limit=1] minecraft:generic.knockback_resistance base set 1.0"
  ).result >= 1,
  "punch target is knockback-immune"
)

-- Cue the client over Minecheck's own channel — the documented cross-process signal. Sent only now:
-- the client attacks as soon as it sees the target, and an earlier attack would move it under the
-- "settled and still" assertions above.
minecheck.log("query test: all server expects done, cueing the client to punch")
check(minecheck.sendPacket("punch_ready"), "cued the client over the packet channel")

-- The client's punch phase runs in another process and decides when it leaves; the ceiling comes
-- from config.defaultTimeout rather than a number guessed here.
local left = minecheck.waitForPlayerLeave(player)
check(left, "client left after query test")
-- The client only leaves once the target is gone, so its absence proves a real player attack killed
-- it (findEntities by tag, the only side where tags exist).
check(#minecheck.findEntities("minecraft:vex", "punchtarget") == 0, "the punched target died")
minecheck.runCommand("stop")
