-- Phases:
--   phase A: create a superflat world, leave it          (title screen)
--   phase B: rejoin the existing local world, leave it    (title screen)
--   phase C: join the parallel dedicated server, leave it (title screen)
--   end:     quit (the one and only process exit)

local function runPhase(name, fn)
  minecheck.log("phase: " .. name)
  local ok, err = pcall(fn)
  minecheck.expect(ok, name)
  if ok then
    local d = minecheck.disconnect(30)
    minecheck.expect(d, name .. " (back to title)")
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
