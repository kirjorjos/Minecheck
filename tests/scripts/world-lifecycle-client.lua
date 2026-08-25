-- Phase 1 of the world-lifecycle test:
--    Create a superflat world (client auto-joins), leave it, rejoin the pre-existing world, leave again, quit.
local world = "minecheck_world"

local created = minecheck.createWorld({ name = world })
if not minecheck.expect(created, "created superflat world") then
  minecheck.fail("createWorld failed")
end

local left = minecheck.leaveWorld(30)
if not minecheck.expect(left, "left world after create") then
  minecheck.fail("leaveWorld after create failed")
end
minecheck.sleep(1)

local rejoined = minecheck.joinLocalWorld(world, 60)
if not minecheck.expect(rejoined, "rejoined existing world") then
  minecheck.fail("joinLocalWorld (rejoin) failed")
end

local left2 = minecheck.leaveWorld(30)
if not minecheck.expect(left2, "left world after rejoin") then
  minecheck.fail("leaveWorld after rejoin failed")
end

minecheck.quit()
