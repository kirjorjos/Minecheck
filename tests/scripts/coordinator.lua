-- Phases:
--   phase A: create a superflat world, leave it          (title screen)
--   phase B: rejoin the existing local world, leave it    (title screen)
--   phase C: join the parallel dedicated server, leave it (title screen)
--   end:     quit (the one and only process exit)

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

-- Third-party plugins extend the surface through minecheck.ext; the table always exists (empty when
-- no plugin is installed on the classpath), so a script can index it without a nil guard.
minecheck.expect(type(minecheck.ext) == "table", "minecheck.ext is a table")

local function runPhase(name, fn)
  minecheck.log("phase: " .. name)
  local ok, err = pcall(fn)
  minecheck.expect(ok, name)
  if ok then
    local d = minecheck.disconnect(30)
    minecheck.expect(d, name .. " (back to title)")
    minecheck.expect(minecheck.isOnTitleScreen(), name .. " (isOnTitleScreen)")
    minecheck.expect(minecheck.isInWorld() == false, name .. " (isInWorld false)")
  else
    minecheck.log("phase failed: " .. tostring(err), "error")
    return false
  end
  return true
end

-- phase A: create + leave a fresh world
runPhase("create world", function()
  local c = minecheck.createWorld({ name = "minecheck_world" })
  if not c then
    minecheck.fail("createWorld failed")
  end

  local settled = minecheck.waitFor(minecheck.worldGenSettled, 90)
  if not settled then
    minecheck.fail("worldgen did not settle before leave")
  end

  minecheck.expect(minecheck.isInWorld(), "isInWorld after createWorld")
  minecheck.expect(minecheck.isOnTitleScreen() == false, "isOnTitleScreen false in a world")
end)

-- phase B: rejoin the saved world
runPhase("rejoin world", function()
  local j = minecheck.joinLocalWorld("minecheck_world", 90)
  if not j then
    minecheck.fail("joinLocalWorld failed")
  end
end)

-- phase C: join the parallel dedicated server on localhost
runPhase("join+leave server", function()
  local j = minecheck.joinServer("localhost:25565", 120)
  if not j then
    minecheck.fail("joinServer failed")
  end
end)

minecheck.quit()
