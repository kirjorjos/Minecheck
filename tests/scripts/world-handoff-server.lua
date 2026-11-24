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
-- Read the ground beside the marker, not under it: the marker is itself a blocking block, so
-- once it exists, the marked column's heightmap answers with the marker instead of the ground. The
-- later phases would then place and look for their own marker 160 blocks too high — outside the
-- world on 1.16.5. The flat world's surface is the same at both columns.
-- A chunk can be "loaded" before its heightmap is primed: on the forge client the first read here
-- answers 0, and the same read two seconds later answers 4. Zero is not a surface in these worlds
-- (their layers start at 0 or -64), so a non-zero height is the heightmap answering.
local SURFACE_Y
local function surfaceKnown()
  local height = minecheck.groundHeight(1, 0)
  SURFACE_Y = height
  return height ~= nil and height ~= 0
end
check(minecheck.waitFor(surfaceKnown, 90), "the marker's surface is known")
-- 1.4.7's world is 128 blocks tall, so a marker 160 above the surface would be outside it: this
-- line's marker goes as high as its height allows and still sits above the heightmap's read.
local MARKER_Y = SURFACE_Y + (minecheck.minecraftVersion():match("^1%.4%.") and 100 or 160)
minecheck.observe("surfaceY", SURFACE_Y)
minecheck.observe("markerY", MARKER_Y)
minecheck.observe("markerChunkLoaded", minecheck.isChunkLoaded(0, 0))

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
