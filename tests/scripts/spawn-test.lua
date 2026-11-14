-- Dedicated-server script (spawn integration test):
--  Wait for the CI player to join, spawn an entity, block, item drop and structure
--  near world spawn, verify each, then stop the server.
local player = "Dev"
local joined = minecheck.waitForPlayer(player, 180)
if not joined then
  minecheck.fail("player " .. player .. " never joined the spawn-test server")
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

-- The harness generates the dedicated server's world with the flat preset (`level-type=flat`,
-- layers from `tests/flat-world.json`): bedrock at -64, two layers of dirt, grass on top at -61, so
-- the surface is SURFACE_Y. Everything
-- below is written relative to it, so a version whose preset moves the ground moves the fixture with
-- it instead of leaving the fixture inside the world.
local SURFACE_Y = -60
-- High enough above that surface that no terrain, tree or structure can be at the fixture's
-- coordinates. The fixture clears its own volume below, so this is a choice of neighbourhood rather
-- than a load-bearing height.
local FIXTURE_Y = SURFACE_Y + 160

-- entity (spawned with NoAI so it stands still, keeping all tests deterministic)
local cow = minecheck.spawnEntity("minecraft:cow", { x = 0.5, y = FIXTURE_Y, z = 0.5 }, { NoAI = true })
check(cow.success, "spawnEntity cow succeeded")
check(cow.id ~= nil and cow.id > 0, "spawnEntity returned an entity id")
local cowFound = minecheck.waitFor(function()
  return minecheck.runCommand("execute if entity @e[type=minecraft:cow]").result > 0
end, 5)
check(cowFound, "cow exists on the server")
local cowNoAi = minecheck.runCommand("execute if entity @e[type=minecraft:cow,nbt={NoAI:1b}]")
check(cowNoAi.result > 0, "cow has NoAI flag applied")

-- The fixture owns its volume: clear it once, so "the first placement goes into air" is a
-- property of this script rather than of whatever worldgen or an earlier phase left at these
-- coordinates.
minecheck.runCommand("fill 0 " .. (FIXTURE_Y - 1) .. " 0 12 " .. (FIXTURE_Y + 4) .. " 12 minecraft:air")
-- And wait until that volume answers a block query: on a fast join the server may not have the
-- spawn-region chunks loaded yet, and a placement into an unloaded chunk fails without saying why.
local volumeReady = minecheck.waitFor(function()
  return minecheck.runCommand("execute if block 5 " .. FIXTURE_Y .. " 5 minecraft:air").result == 1
end, 120)
if not volumeReady then
  minecheck.fail("the spawn volume never became air and loaded")
end

-- block (first placement into air succeeds; a second without replace must refuse)
local BX, BZ = 5, 5
local function blockAtIs(block)
  return minecheck.runCommand("execute if block " .. BX .. " " .. FIXTURE_Y .. " " .. BZ .. " " .. block).result == 1
end

local stone = minecheck.spawnBlock("minecraft:stone", { x = BX, y = FIXTURE_Y, z = BZ })
check(stone.success, "spawnBlock stone succeeded")
check(blockAtIs("minecraft:stone"), "stone block is at the fixture position")

local occupied = minecheck.spawnBlock("minecraft:dirt", { x = BX, y = FIXTURE_Y, z = BZ })
check(not occupied.success, "spawnBlock without replace refuses an occupied position")
check(blockAtIs("minecraft:stone"), "occupied block was not overwritten")

local replaced = minecheck.spawnBlock("minecraft:dirt", { x = BX, y = FIXTURE_Y, z = BZ }, {}, true)
check(replaced.success, "spawnBlock with replace=true overwrites")
check(blockAtIs("minecraft:dirt"), "block was replaced with dirt")

-- item drop
local diamond = minecheck.spawnItemDrop("minecraft:diamond", { x = 0.5, y = FIXTURE_Y + 2, z = 0.5 }, 3)
check(diamond.success, "spawnItemDrop diamond succeeded")
check(diamond.id ~= nil and diamond.id > 0, "spawnItemDrop returned an entity id")
local itemFound = minecheck.waitFor(function()
  return minecheck.runCommand('execute if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:diamond"}}]').result
    > 0
end, 5)
check(itemFound, "diamond item drop exists on the server")

-- structure
local igloo = minecheck.spawnStructure("minecraft:igloo/top", { x = 10, y = FIXTURE_Y, z = 10 })
check(igloo.success, "spawnStructure igloo/top succeeded")

-- A phase leaves the session running: the coordinator owns the player-leave wait and the stop (see
-- `_G.MINECHECK_PHASE` in docs/lua-api.md).
if not _G.MINECHECK_PHASE then
  minecheck.log("spawn test: all expects done, waiting for client to leave")
  -- How long the client's own script needs is not knowable here (it is another process), so the
  -- ceiling is config.defaultTimeout, which the harness stages for the whole run, not a guess.
  local left = minecheck.waitForPlayerLeave(player)
  check(left, "client left after spawn test")
  minecheck.runCommand("stop")
end
