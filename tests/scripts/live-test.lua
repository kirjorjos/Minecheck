-- Live session: the script opens a loopback listener, a program on the same machine attaches, sends
-- Lua chunks and reads their replies.
--
-- The driver is the lifecycle task's own TCP client (tests/scripts/live-driver.txt holds the chunks
-- and the replies it expects); this script serves them with minecheck.liveAttach and then asserts
-- what the chunks did. Nothing here is CI-specific: the same two calls are how a person attaches a
-- shell to a running suite.
--
--   nc 127.0.0.1 <port>        (port is in the client log: "live listening on 127.0.0.1:...")

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

local function expect(cond, name)
  if not minecheck.expect(cond, name) then
    minecheck.fail(name .. " failed")
  end
end

-- A world first, so a served chunk has game state to read and the session is served from a running
-- game rather than the title screen.
expect(minecheck.createWorld({ name = "minecheck_world", seed = 42 }), "created superflat world")
expect(minecheck.waitFor(minecheck.worldGenSettled, 90), "worldgen settled")

minecheck.log("live test: serving (port is logged above)")

-- Serve exactly one client: the driver attaches, sends its chunks, disconnects. The count is the
-- script's side of the handshake — a session that served nothing is a failure even if the driver
-- never complained.
local served = minecheck.liveAttach(0, 120)
expect(served ~= nil, "a live client attached")
expect(served == 3, "the client sent 3 chunks (got " .. tostring(served) .. ")")

-- The chunks ran in *this* script's globals: that is what makes a live session useful for debugging
-- a specific run, and it is asserted from the script's side rather than trusted from the driver's.
expect(_G.driverTouched == true, "a served chunk wrote to the script's globals")
expect(
  _G.driverSawSide == "client",
  "a served chunk could call the API (saw side " .. tostring(_G.driverSawSide) .. ")"
)
expect(
  _G.driverSawPlayer == "Dev",
  "a served chunk could read game state (saw player " .. tostring(_G.driverSawPlayer) .. ")"
)

-- The listener is closed when the session ends: binding the same port again must work immediately,
-- and nothing about it outlives the script (the engine closes whatever is still open anyway).
local again = minecheck.listen(0)
expect(again ~= nil and again:port() > 0, "a new listener opens after the session closed")
again:close()

local viaRaw = minecheck.listen(0)
expect(viaRaw ~= nil, "listen() returns a handle without a client")
expect(viaRaw:accept(0) == nil, "accept() returns nil when nobody connects within the timeout")
viaRaw:close()

minecheck.log("live test: session served and closed")
minecheck.quit()
