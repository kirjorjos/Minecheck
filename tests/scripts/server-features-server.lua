-- Merged "server features" session, dedicated-server half.
--
-- Runs the server side of the suites in server-features-client.lua, in the same order. Each phase
-- script is unchanged apart from skipping its own `stop`/player-leave wait (`_G.MINECHECK_PHASE`),
-- which is what keeps one server alive for all of them instead of booting one per suite.
--
-- The phases are gated on each other in both directions. The server says when a phase's setup is
-- done (the client may not assert `op`, a give, survival or a spawned target before that), and the
-- client says when it has finished the matching phase (the server may not start the next one, and
-- its setup must not land under the client's own reads — query's gamemode change and sword, for
-- one). Both cues travel over the packet channel, whose history is replayed, so a message that
-- arrives early is still seen.

local function cue(clientPhase)
  minecheck.expect(
    minecheck.sendPacket("server-ready:" .. clientPhase),
    "told the client the setup for " .. clientPhase .. " is done"
  )
end

local function phase(serverScript, clientPhase)
  minecheck.log("phase: " .. serverScript)
  minecheck.runScript(serverScript)
  -- The client half runs in another process and asserts what this setup produced (`op`, the gives,
  -- survival and the sword in hand, the spawned target). Tell it the setup is done; without this cue
  -- those asserts run against whatever the previous phase left behind, and a loaded runner loses
  -- the 30 s give windows outright ("server gave the 8-redstone stack", the emerald handshake).
  cue(clientPhase)
end

-- The packet phases are a two-sided exchange, not a setup the client reads: their first act is a
-- wait for the other side's packet, so cueing the client after running them deadlocks the session
-- until that wait times out. Cue first — the client has nothing of ours to look at yet anyway.
local function exchange(serverScript, clientPhase)
  minecheck.log("phase: " .. serverScript)
  cue(clientPhase)
  minecheck.runScript(serverScript)
end

-- 600 s: the client's beacon phase alone takes about a minute and a half on a loaded runner, and the
-- ceiling here is only about noticing a client that died, not a budget for it.
local function after(clientPhase)
  local done = minecheck.waitForPacket("client-done:" .. clientPhase, 600)
  minecheck.expect(done ~= false, "the client finished " .. clientPhase)
end

phase("beacon-test.lua", "beacon-client.lua")
after("beacon-client.lua")
phase("query-test.lua", "query-client.lua")
after("query-client.lua")
exchange("packet-test.lua", "packet-client.lua")
after("packet-client.lua")
if minecheck.isModLoaded("forge") then
  exchange("packet-tap-server.lua", "packet-tap-client.lua")
  after("packet-tap-client.lua")
end
phase("spawn-test.lua", "spawn-client.lua")
-- Every spawn expect is done, so the client may leave now. It waits for this before `leaveWorld`:
-- the phase's own `waitForPlayer` is the first thing it does, and the client's spawn phase is a
-- no-op, so without the cue the client was already gone when this phase started and the run failed
-- with "player Dev never joined the spawn-test server" (#129).
minecheck.expect(minecheck.sendPacket("server-done:spawn-test.lua"), "told the client the spawn phase is done")

local left = minecheck.waitForPlayerLeave("Dev")
minecheck.expect(left, "client left after the merged session")
minecheck.runCommand("stop")
