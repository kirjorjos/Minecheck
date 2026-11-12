-- Merged "client features" session.
--
-- The two client-only suites each booted a client to run a minute of assertions in a superflat world
-- they created themselves. They compose without a server and without a disconnect: `input-test.lua`
-- creates the creative world, and `live-test.lua` attaches to the world that is already open (its
-- `createWorld` is skipped in phase mode) and serves the live driver from it.
--
-- `input-test.lua` runs first on purpose: it needs creative mode and an empty hotbar, which is
-- exactly the state a freshly created world leaves behind.

local function phase(name)
  minecheck.log("phase: " .. name)
  minecheck.runScript(name)
end

phase("input-test.lua")
phase("live-test.lua")

minecheck.leaveWorld(30)
minecheck.quit()
