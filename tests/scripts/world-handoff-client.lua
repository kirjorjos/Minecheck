-- Handoff phase B (client): join the dedicated server that is running the world this client
-- generated in phase A. The marker block is the proof: it only exists in that save, so a server
-- that generated a world of its own would not have it.
--
-- The world is the flat one the generating client built: the marker sits well above its surface, and
-- the height is read from the joined world below rather than declared here.
local joined = minecheck.joinServer("localhost:25565", 300)
minecheck.expect(joined, "joined the server running the generated world")
if not joined then
  minecheck.fail("could not join the handoff server")
end

minecheck.expect(minecheck.waitFor(minecheck.worldGenSettled, 90), "joined worldgen settled")

-- A joined client only receives chunks around itself, and the marker's column is not at every
-- line's spawn: step sideways onto 0,0 at the height the player already stands at (a flat world has
-- the same ground there, so the move cannot drop them into it). A client cannot ask for a chunk —
-- `loadChunk` is server-only for exactly that reason — so the move is what makes the server send it,
-- and `waitForChunk` is the wait for it to arrive.
minecheck.runCommand("tp @s 0.5 ~ 0.5")
minecheck.expect(minecheck.waitForChunk(0, 0, 90), "the marker's chunk arrived from the server")
local SURFACE_Y = minecheck.groundHeight(0, 0)
minecheck.expect(SURFACE_Y ~= nil, "the marker's surface is known")
local MARKER_Y = SURFACE_Y + 160

local marked = minecheck.waitFor(function()
  local b = minecheck.getBlock({ x = 0, y = MARKER_Y, z = 0 })
  return b ~= nil and b.id == "minecraft:gold_block"
end, 60)
minecheck.expect(marked, "the joined server serves the client's own world (marker present)")

minecheck.leaveWorld(60)
minecheck.quit()
