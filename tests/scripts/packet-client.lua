-- Packet integration test, client half:
--  join the parallel dedicated server, send a typed packet, receive the server's reply (including
--  raw bytes through minecheck.reader), observe a server-pushed packet via onPacket, and report done.

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

local joined = minecheck.joinServer("localhost:25565", 300)
if not minecheck.expect(joined, "joined the dedicated server") then
  minecheck.fail("joinServer failed")
end

-- Registered before the send: the handler must see the server-initiated packet.
local pushed = nil
minecheck.onPacket("push", function(value)
  pushed = value
end)

local sent =
  minecheck.sendPacket("hello", "ping", 42, true, 2.5, minecheck.bytes("\1\2\3"), minecheck.int64("72623859790382856"))
minecheck.expect(sent, "sent hello")

local pong = minecheck.waitForPacket("pong", 60)
minecheck.expect(pong ~= false, "received pong")
if pong then
  minecheck.expect(pong.args[1] == "echo", "pong arg 1 is the echoed string")
  minecheck.expect(pong.args[2] == 42, "pong arg 2 is the echoed number")
  minecheck.expect(pong.args[3] == true, "pong arg 3 is the echoed boolean")
  minecheck.expect(pong.args[4] == 2.5, "pong arg 4 is the echoed float")
  local reader = minecheck.reader(pong.args[5])
  minecheck.expect(reader.readBytes(3) == "\1\2\3", "pong arg 5 is the raw bytes sent")
  -- The int64 the server echoed back: exact text, and equal to a freshly built int64. A plain
  -- number literal here would have rounded, which is the whole reason the type exists.
  minecheck.expect(
    tostring(pong.args[6]) == "72623859790382856",
    "pong arg 6 is the returned int64, exactly (got " .. tostring(pong.args[6]) .. ")"
  )
  minecheck.expect(pong.args[6] == minecheck.int64("72623859790382856"), "pong arg 6 equals the same int64")
end

-- Targeted send: the server addresses this client by name, so this packet arrives even though it
-- was never broadcast.
local targeted = minecheck.waitForPacket("targeted", 60)
minecheck.expect(targeted ~= false, "received the targeted packet")
if targeted then
  minecheck.expect(targeted.args[1] == "only-for-you", "targeted arg 1 is the string")
  minecheck.expect(targeted.args[2] == 7, "targeted arg 2 is the number 7")
end

local handlerRan = minecheck.waitFor(function()
  return pushed == "from-server"
end, 60)
minecheck.expect(handlerRan, "onPacket saw the server push")

-- Foreign-channel observation. Fabric/Quilt register a play-channel receiver for the channel, which
-- succeeds only for a channel nothing else receives (Fabric's API keeps one handler per channel per
-- connection); Forge installs a frame tap below the channel. This channel is free, so every loader
-- must report it and hand payloads to a decoder.
local observed = minecheck.watchPacketChannel("other-mod:test")
minecheck.expect(observed, "watching a free foreign channel succeeds on this client")
if observed then
  minecheck.registerPacketDecoder("other-mod:test", function(bytes)
    return minecheck.reader(bytes).readVarInt()
  end)
end

-- The same call on a channel that is already owned: Minecheck's own transport holds
-- `minecheck:main`. On Fabric/Quilt a second handler for it cannot be installed, so the answer must
-- be false there; Forge's frame tap sits *below* the channel and reports true. The invariant is that
-- a claimed channel is never reported as watched while the handler would be silently shadowed.
local own = minecheck.watchPacketChannel("minecheck:main")
minecheck.expect(
  own == minecheck.isModLoaded("forge"),
  "an already-owned channel is refused on Fabric/Quilt and tapped on Forge"
)
if own then
  minecheck.registerPacketDecoder("minecheck:main", function()
    return {}
  end)
end

minecheck.sendPacket("done")

local left = minecheck.leaveWorld(30)
minecheck.expect(left, "left the server")
minecheck.quit()
