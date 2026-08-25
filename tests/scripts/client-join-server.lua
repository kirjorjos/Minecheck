-- Phase 3 of the world-lifecycle test:
--  Join the dedicated server, then leave and quit.
local joined = minecheck.joinServer("localhost:25565", 60)
if not minecheck.expect(joined, "joined dedicated server") then
  minecheck.fail("joinServer failed")
end

local left = minecheck.leaveWorld(30)
if not minecheck.expect(left, "left server") then
  minecheck.fail("leaveWorld from server failed")
end

minecheck.quit()
