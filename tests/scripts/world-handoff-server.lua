-- Handoff phase C (dedicated server): the harness staged the client's save into this run dir and
-- started the server with `-Ddh.serverSetupDir`, so this world came from the client instead of
-- being generated here. The marker block is what proves that: `execute if block` succeeds only in
-- the save the client created.
--
-- The staged save is the flat world the client built: the marker sits well above its surface, and
-- the height is read from the loaded world below rather than declared here.
local player = "Dev"

local function check(result, name)
  minecheck.expect(result, name)
  if not result then
    minecheck.fail(name)
  end
end

check(minecheck.waitForPlayer(player, 180), "player " .. player .. " joined the handoff server")
check(minecheck.waitFor(minecheck.worldGenSettled, 180), "worldgen settled before the marker check")

-- The marker's column is not necessarily the server's spawn column, and an unloaded chunk has no
-- height: force-load it through the platform (no `forceload` command, no teleport), then take the
-- surface from the world.
check(minecheck.loadChunk(0, 0), "the marker's column is force-loaded")
local SURFACE_Y = minecheck.groundHeight(0, 0)
check(SURFACE_Y ~= nil, "the marker's surface is known")
local MARKER_Y = SURFACE_Y + 160

-- `runCommand` reports `success` for "the command ran", not for "the condition held": a
-- conditional `execute if` that fails — or that runs against a chunk the server has not loaded yet
-- — returns 0 successes without throwing. So the count is the assertion, and it is polled.
-- The marker's column was loaded above, so the conditional check below can see it.
local function markerPresent()
  return minecheck.runCommand("execute if block 0 " .. MARKER_Y .. " 0 minecraft:gold_block").result >= 1
end
check(minecheck.waitFor(markerPresent, 90), "server is running the world the client generated (marker block present)")

-- No explicit timeout: the client's own script decides when it leaves, so the wait's ceiling is
-- config.defaultTimeout (staged by the harness for the run), not a guess made here.
check(minecheck.waitForPlayerLeave(player), "client left after the handoff check")
minecheck.runCommand("stop")
