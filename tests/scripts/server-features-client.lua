-- Merged "server features" session, client half.
--
-- The suites below each used to boot their own client, wait for its own dedicated server, and quit;
-- every one of them paid ~60 s to boot a JVM and load a world before doing a minute of work. They all
-- join the same server and each leaves the session in a state the next one can use (an inventory, a
-- punched-out entity, a closed screen), so they run here as phases of one session instead.
--
-- Ordering is deliberate and must match server-features-server.lua phase for phase:
--   beacon  — the server's `give`s land in a clean hotbar; the client reads exact slots (36..44)
--   query   — the server puts the player in survival and cues the punch; last, because it changes
--             the gamemode and hands out a second sword
--   packet  — a fresh hello/pong exchange on Minecheck's own channel
--   tap     — Forge only: the same channel observed through the frame tap
--   spawn   — the work is all server-side; the client only has to be there
--
-- Each phase script is the same file the standalone task runs, so there is one copy of the
-- assertions; `_G.MINECHECK_PHASE` tells them not to quit the session or re-join a server.

local function phase(name)
  minecheck.log("phase: " .. name)
  minecheck.runScript(name)
end

phase("beacon-client.lua")
phase("query-client.lua")
phase("packet-client.lua")
-- The frame tap is a Forge-only observation path; on the other loaders the whole channel API is
-- exercised by packet-client above (`watchPacketChannel("minecheck:main")` is asserted there).
if minecheck.isModLoaded("forge") then
  phase("packet-tap-client.lua")
end
phase("spawn-client.lua")

minecheck.expect(minecheck.leaveWorld(60), "left the server after every phase")
minecheck.quit()
