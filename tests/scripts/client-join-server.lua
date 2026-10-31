-- Standalone single-phase client script (no coordinator): join the dedicated server, leave, quit.
--
-- Kept as the worked example of the `script` entry point (a coordinator supersedes it when one is
-- configured) and as the client half of the world-lifecycle scenario, which the `coordinator.lua`
-- suite now covers end-to-end in one session. Nothing in the build runs this file directly.

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

local joined = minecheck.joinServer("localhost:25565", 300)
if not minecheck.expect(joined, "joined dedicated server") then
  minecheck.fail("joinServer failed")
end

local left = minecheck.leaveWorld(30)
if not minecheck.expect(left, "left server") then
  minecheck.fail("leaveWorld from server failed")
end

minecheck.quit()
