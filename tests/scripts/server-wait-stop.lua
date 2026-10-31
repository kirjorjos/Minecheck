-- Dedicated-server script (world-lifecycle integration test):
--  Wait for the CI player (Dev) to JOIN, then wait for them to LEAVE, then stop the server via the console command `stop`.
local player = "Dev"

local joined = minecheck.waitForPlayer(player, 180)
minecheck.expect(joined, "player " .. player .. " joined the server")
if not joined then
  minecheck.fail("player " .. player .. " never joined the server")
end

-- The client leaves when its own script is done; the ceiling is config.defaultTimeout, staged by
-- the harness for the whole run, not a number guessed here.
local left = minecheck.waitForPlayerLeave(player)
minecheck.expect(left, "player " .. player .. " left the server")
if not left then
  minecheck.fail("player " .. player .. " never left the server")
end

minecheck.runCommand("stop")
