local player = "Dev"
local joined = minecheck.waitForPlayer(player, 180)
if not joined then
  minecheck.fail("player " .. player .. " never joined the beacon-test server")
end

local settled = minecheck.waitFor(minecheck.worldGenSettled, 180)
if not settled then
  minecheck.fail("worldgen did not settle before setup")
end

local function check(result, name)
  minecheck.expect(result, name)
  if not result then
    minecheck.fail(name)
  end
end

minecheck.runCommand("op " .. player)

local g1 = minecheck.runCommand("give " .. player .. " minecraft:emerald 1")
check(g1.result > 0, "emerald given")
local g2 = minecheck.runCommand(
  "give " .. player .. ' minecraft:iron_sword{display:{Name:\'{"text":"Minecheck Test Sword","italic":false}\'}} 1'
)
check(g2.result > 0, "named sword given")
local g3 = minecheck.runCommand("give " .. player .. " minecraft:redstone 8")
check(g3.result > 0, "8 redstone given (partial moveItem check)")

minecheck.log("beacon test: player op'd and items given, waiting for client to leave")
-- No explicit timeout: the client is a separate process running its own script, so how long it
-- needs is not something this script can know. A hard-coded 90 s here failed a CI run whose client
-- was still finishing a Java-side probe at 93 s. The ceiling is config.defaultTimeout, which the
-- harness stages for the whole run.
local left = minecheck.waitForPlayerLeave(player)
check(left, "client left after beacon test")
minecheck.runCommand("stop")
