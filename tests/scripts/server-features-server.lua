-- Merged "server features" session, dedicated-server half.
--
-- Runs the server side of the suites in server-features-client.lua, in the same order. Each phase
-- script is unchanged apart from skipping its own `stop`/player-leave wait (`_G.MINECHECK_PHASE`),
-- which is what keeps one server alive for all of them instead of booting one per suite.
--
-- The phases synchronise themselves over Minecheck's packet channel and over the game state the
-- client waits on: beacon's gives land before the client reads its slots, query's `punch_ready` cue
-- comes only after the target has settled, packet answers the client's hello, and spawn needs
-- nothing from the client beyond it being joined.

local function phase(name)
  minecheck.log("phase: " .. name)
  minecheck.runScript(name)
end

phase("beacon-test.lua")
phase("query-test.lua")
phase("packet-test.lua")
if minecheck.isModLoaded("forge") then
  phase("packet-tap-server.lua")
end
phase("spawn-test.lua")

local left = minecheck.waitForPlayerLeave("Dev")
minecheck.expect(left, "client left after the merged session")
minecheck.runCommand("stop")
