-- Handoff phase C (dedicated server): the harness staged the client's save into this run dir and
-- started the server with `-Ddh.serverSetupDir`, so this world came from the client instead of
-- being generated here. The marker block is what proves that: `execute if block` succeeds only in
-- the save the client created.
local player = "Dev"

local function check(result, name)
  minecheck.expect(result, name)
  if not result then
    minecheck.fail(name)
  end
end

check(minecheck.waitForPlayer(player, 180), "player " .. player .. " joined the handoff server")
check(minecheck.waitFor(minecheck.worldGenSettled, 180), "worldgen settled before the marker check")

-- `runCommand` reports `success` for "the command ran", not for "the condition held": a
-- conditional `execute if` that fails — or that runs against a chunk the server has not loaded yet
-- — returns 0 successes without throwing. So the count is the assertion, and it is polled.
local function markerPresent()
  return minecheck.runCommand("execute if block 0 100 0 minecraft:gold_block").result >= 1
end
check(minecheck.waitFor(markerPresent, 90), "server is running the world the client generated (marker block present)")

check(minecheck.waitForPlayerLeave(player, 120), "client left after the handoff check")
minecheck.runCommand("stop")
