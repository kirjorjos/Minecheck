-- Dedicated-server script (world-lifecycle integration test):
--  Wait for the CI player (Dev) to JOIN, then wait for them to LEAVE, then stop the server via the console command `stop`.
local player = "Dev"

local joined = minecheck.waitForPlayer(player, 180)
minecheck.expect(joined, "player " .. player .. " joined the server")
if not joined then
  minecheck.fail("player " .. player .. " never joined the server")
end

local left = minecheck.waitForPlayerLeave(player, 180)
minecheck.expect(left, "player " .. player .. " left the server")
if not left then
  minecheck.fail("player " .. player .. " never left the server")
end

minecheck.runCommand("stop")
