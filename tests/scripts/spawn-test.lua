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

-- entity (spawned with NoAI so it stands still, keeping all tests deterministic)
local cow = minecheck.spawnEntity("minecraft:cow", { x = 0.5, y = 100, z = 0.5 }, { NoAI = true })
check(cow.success, "spawnEntity cow succeeded")
check(cow.id ~= nil and cow.id > 0, "spawnEntity returned an entity id")
local cowFound = minecheck.waitFor(function()
  return minecheck.runCommand("execute if entity @e[type=minecraft:cow]").result > 0
end, 5)
check(cowFound, "cow exists on the server")
local cowNoAi = minecheck.runCommand("execute if entity @e[type=minecraft:cow,nbt={NoAI:1b}]")
check(cowNoAi.result > 0, "cow has NoAI flag applied")

-- block (first placement into air succeeds; a second without replace must refuse)
local stone = minecheck.spawnBlock("minecraft:stone", { x = 5, y = 100, z = 5 })
check(stone.success, "spawnBlock stone succeeded")
local stoneThere = minecheck.runCommand("execute if block 5 100 5 minecraft:stone")
check(stoneThere.result == 1, "stone block is at 5 100 5")

local occupied = minecheck.spawnBlock("minecraft:dirt", { x = 5, y = 100, z = 5 })
check(not occupied.success, "spawnBlock without replace refuses an occupied position")
local stillStone = minecheck.runCommand("execute if block 5 100 5 minecraft:stone")
check(stillStone.result == 1, "occupied block was not overwritten")

local replaced = minecheck.spawnBlock("minecraft:dirt", { x = 5, y = 100, z = 5 }, {}, true)
check(replaced.success, "spawnBlock with replace=true overwrites")
local nowDirt = minecheck.runCommand("execute if block 5 100 5 minecraft:dirt")
check(nowDirt.result == 1, "block was replaced with dirt")

-- item drop
local diamond = minecheck.spawnItemDrop("minecraft:diamond", { x = 0.5, y = 102, z = 0.5 }, 3)
check(diamond.success, "spawnItemDrop diamond succeeded")
check(diamond.id ~= nil and diamond.id > 0, "spawnItemDrop returned an entity id")
local itemFound = minecheck.waitFor(function()
  return minecheck.runCommand('execute if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:diamond"}}]').result
    > 0
end, 5)
check(itemFound, "diamond item drop exists on the server")

-- structure
local igloo = minecheck.spawnStructure("minecraft:igloo/top", { x = 10, y = 100, z = 10 })
check(igloo.success, "spawnStructure igloo/top succeeded")

minecheck.log("spawn test: all expects done, waiting for client to leave")
local left = minecheck.waitForPlayerLeave(player, 60)
check(left, "client left after spawn test")
minecheck.runCommand("stop")
