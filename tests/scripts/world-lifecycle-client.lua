-- Standalone single-phase client script: create a superflat world (the client auto-joins), leave
-- it, rejoin the pre-existing world, leave again, quit.
--
-- Superseded by `coordinator.lua`, which runs the same create/rejoin/join-server lifecycle in one
-- long-lived session via `minecheck.disconnect()`. Kept as a worked example; nothing in the build
-- runs this file directly.

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

local world = "minecheck_world"

local created = minecheck.createWorld({ name = world })
if not minecheck.expect(created, "created superflat world") then
  minecheck.fail("createWorld failed")
end

local left = minecheck.leaveWorld(30)
if not minecheck.expect(left, "left world after create") then
  minecheck.fail("leaveWorld after create failed")
end
-- leaveWorld returns once the player is gone; the title screen is what follows it, and waiting for
-- that is what the rejoin below actually needs.
minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "back on the title screen")

local rejoined = minecheck.joinLocalWorld(world, 300)
if not minecheck.expect(rejoined, "rejoined existing world") then
  minecheck.fail("joinLocalWorld (rejoin) failed")
end

local left2 = minecheck.leaveWorld(30)
if not minecheck.expect(left2, "left world after rejoin") then
  minecheck.fail("leaveWorld after rejoin failed")
end

minecheck.quit()
