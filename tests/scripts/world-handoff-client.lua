-- Handoff phase B (client): join the dedicated server that is running the world this client
-- generated in phase A. The marker block is the proof: it only exists in that save, so a server
-- that generated a world of its own would not have it.

local joined = minecheck.joinServer("localhost:25565", 300)
minecheck.expect(joined, "joined the server running the generated world")
if not joined then
  minecheck.fail("could not join the handoff server")
end

minecheck.expect(minecheck.waitFor(minecheck.worldGenSettled, 90), "joined worldgen settled")

local marked = minecheck.waitFor(function()
  local b = minecheck.getBlock({ x = 0, y = 100, z = 0 })
  return b ~= nil and b.id == "minecraft:gold_block"
end, 60)
minecheck.expect(marked, "the joined server serves the client's own world (marker present)")

minecheck.leaveWorld(60)
minecheck.quit()
