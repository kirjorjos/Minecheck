-- Handoff phase A (client): generate the save the dedicated server will run on.
--
-- The harness runs this script first, and only after this process has exited does it copy
-- `saves/minecheck_world` into a server setup dir and start the server with
-- `-Ddh.serverSetupDir` pointing at it. That ordering is the whole point: a save copied out from
-- under a live integrated server is a torn copy, so the client has to be gone before the handoff.
--
-- Leave behind something a *freshly generated* server world could not have — a marker block at a
-- fixed position — so the server script can prove which world it is actually running.
--
-- The world this client creates is flat, from that same layer declaration: the surface is the top
-- of a stack counted up from the bottom of the world, so its height is the line's own — -60 where
-- the layers start at -64, as on 1.18, and 4 on 1.16.5, which starts them at y=0. The marker sits
-- well above that surface, where the generator leaves air on either. The height is read from the
-- world below, so all three handoff scripts agree by construction rather than by a copied constant.

local created = minecheck.createWorld({ name = "minecheck_world", seed = 424242 })
minecheck.expect(created, "client generated the handoff world")
if not created then
  minecheck.fail("could not create the handoff world")
end

minecheck.expect(minecheck.waitFor(minecheck.worldGenSettled, 90), "worldgen settled")

-- A fresh world spawns its player away from 0,0 on 1.16.5 (measured at ~113,139), so the marker's
-- column is not necessarily loaded on this client and its heightmap has no answer yet. Stepping
-- sideways onto 0,0 at the height the player already stands at loads it on both sides — the ground
-- there is the same flat layer stack. A client cannot request a chunk (`loadChunk` is server-only),
-- so the move is the only lever and `isChunkLoaded` is how the read below knows it is safe.
minecheck.runCommand("tp @s 0.5 ~ 0.5")
minecheck.expect(
  minecheck.waitFor(function()
    return minecheck.isChunkLoaded(0, 0)
  end, 90),
  "the marker's chunk arrived from the server"
)
local SURFACE_Y = minecheck.groundHeight(0, 0)
minecheck.expect(SURFACE_Y ~= nil, "the marker's surface is known")
local MARKER_Y = SURFACE_Y + 160

-- Cheats are on by default for a Minecheck-created world, so the client can set the marker itself.
local placed = minecheck.runCommand("setblock 0 " .. MARKER_Y .. " 0 minecraft:gold_block")
minecheck.expect(placed.success, "client placed the handoff marker")
-- The block update reaches the client's own copy of the chunk a tick or two later, so this waits
-- for what it can see rather than assuming the write was instantaneous.
local marked = minecheck.waitFor(function()
  local b = minecheck.getBlock({ x = 0, y = MARKER_Y, z = 0 })
  return b ~= nil and b.id == "minecraft:gold_block"
end, 20)
minecheck.expect(marked, "client reads the handoff marker back")

minecheck.log("handoff: world generated and marked, quitting so the harness can stage the save")
minecheck.quit()
