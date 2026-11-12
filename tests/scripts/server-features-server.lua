-- Merged "server features" session, dedicated-server half.
--
-- Runs the server side of the suites in server-features-client.lua, in the same order. Each phase
-- script is unchanged apart from skipping its own `stop`/player-leave wait (`_G.MINECHECK_PHASE`),
-- which is what keeps one server alive for all of them instead of booting one per suite.
--
-- The phases are gated on each other: a phase may only start once the client has finished the
-- matching one. Without that the server runs its whole script in a few seconds while the client is
-- still booting and working through beacon — and query's setup (survival, the sword in the client's
-- hand) lands under the client's own reads. The client says when it is done over the packet channel,
-- whose history is replayed, so a message that arrives early is still seen.

local function phase(name)
  minecheck.log("phase: " .. name)
  minecheck.runScript(name)
end

-- 600 s: the client's beacon phase alone takes about a minute and a half on a loaded runner, and the
-- ceiling here is only about noticing a client that died, not a budget for it.
local function after(clientPhase)
  local done = minecheck.waitForPacket("client-done:" .. clientPhase, 600)
  minecheck.expect(done ~= false, "the client finished " .. clientPhase)
end

phase("beacon-test.lua")
after("beacon-client.lua")
phase("query-test.lua")
after("query-client.lua")
phase("packet-test.lua")
after("packet-client.lua")
-- The Forge frame tap runs in its own session on this line (see server-features-client.lua and #121),
-- so this session has no tap phase.
phase("spawn-test.lua")

local left = minecheck.waitForPlayerLeave("Dev")
minecheck.expect(left, "client left after the merged session")
minecheck.runCommand("stop")
