-- Handoff phase A (client): generate the save the dedicated server will run on.
--
-- The harness runs this script first, and only after this process has exited does it copy
-- `saves/minecheck_world` into a server setup dir and start the server with
-- `-Ddh.serverSetupDir` pointing at it. That ordering is the whole point: a save copied out from
-- under a live integrated server is a torn copy, so the client has to be gone before the handoff.
--
-- Leave behind something a *freshly generated* server world could not have — a marker block at a
-- fixed position — so the server script can prove which world it is actually running.

local created = minecheck.createWorld({ name = "minecheck_world", seed = 424242 })
minecheck.expect(created, "client generated the handoff world")
if not created then
  minecheck.fail("could not create the handoff world")
end

minecheck.expect(minecheck.waitFor(minecheck.worldGenSettled, 90), "worldgen settled")

-- Cheats are on by default for a Minecheck-created world, so the client can set the marker itself.
local placed = minecheck.runCommand("setblock 0 100 0 minecraft:gold_block")
minecheck.expect(placed.success, "client placed the handoff marker") -- The block update reaches the client's own copy of the chunk a tick or two later, so this waits
-- for what it can see rather than assuming the write was instantaneous.
local marked = minecheck.waitFor(function()
  local b = minecheck.getBlock({ x = 0, y = 100, z = 0 })
  return b ~= nil and b.id == "minecraft:gold_block"
end, 20)
minecheck.expect(marked, "client reads the handoff marker back")

minecheck.log("handoff: world generated and marked, quitting so the harness can stage the save")
minecheck.quit()
