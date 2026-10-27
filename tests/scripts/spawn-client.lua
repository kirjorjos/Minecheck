-- Client half of the spawn integration test:
--  Join the dedicated server, let the server script verify its spawns, then leave.

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

local joined = minecheck.joinServer("localhost:25565", 120)
if not minecheck.expect(joined, "joined spawn-test server") then
  minecheck.fail("joinServer failed")
end

local left = minecheck.leaveWorld(60)
minecheck.expect(left, "left spawn-test server")

minecheck.quit()
