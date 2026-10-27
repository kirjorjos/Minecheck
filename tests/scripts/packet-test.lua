-- Packet integration test, dedicated-server half:
--  verify the shape of the client's packet, echo it back, push a packet the client observes via
--  onPacket, wait for the client's "done", then stop the server.

local hello = minecheck.waitForPacket("hello", 180)
minecheck.expect(hello ~= false, "received the client's hello")
if hello then
  minecheck.expect(hello.args[1] == "ping", "hello arg 1 is the string ping")
  minecheck.expect(hello.args[2] == 42, "hello arg 2 is the number 42")
  minecheck.expect(hello.args[3] == true, "hello arg 3 is the boolean true")
  minecheck.expect(hello.args[4] == 2.5, "hello arg 4 is the float 2.5")
  minecheck.expect(#hello.args[5] == 3, "hello arg 5 is 3 raw bytes")

  -- A 64-bit value has to cross the wire exactly: a Lua number would have rounded
  -- 72623859790382856 to 72623859790382848 before it was ever sent.
  minecheck.expect(
    tostring(hello.args[6]) == "72623859790382856",
    "hello arg 6 is an exact int64 (got " .. tostring(hello.args[6]) .. ")"
  )

  -- Echoing the received int64 back proves the round trip in both directions.
  minecheck.expect(
    minecheck.sendPacket("pong", "echo", 42, true, 2.5, minecheck.bytes("\1\2\3"), hello.args[6]),
    "sent pong"
  )
end

-- Server-side surface: side/config, and the targeted half of the packet surface. sendPacketTo
-- addresses one named player instead of broadcasting, and must say so when that player is absent.
minecheck.expect(minecheck.side() == "server", "side is server")
local cfg = minecheck.config()
minecheck.expect(cfg ~= nil and cfg.script == "packet-test.lua", "config().script is the running server script")
minecheck.expect(cfg.failMode == "exit" and cfg.defaultTimeout == 60, "config() reports failMode/defaultTimeout")
minecheck.expect(cfg.coordinator == nil, "config().coordinator is absent for a plain server script")

minecheck.expect(
  minecheck.sendPacketTo("Dev", "targeted", "only-for-you", 7),
  "sendPacketTo delivered to the joined player"
)
minecheck.expect(
  minecheck.sendPacketTo("NoSuchPlayer", "targeted", "ignored") == false,
  "sendPacketTo to an absent player returns false"
)

-- A dedicated server can watch a free foreign channel on every loader: Fabric/Quilt register a
-- play-channel receiver for it (whose success means no other handler owns it), Forge installs a
-- frame tap on each player connection (see packet-tap-server).
local observed = minecheck.watchPacketChannel("other-mod:test")
minecheck.expect(observed, "a dedicated server can watch a free foreign channel")

-- Minecheck's own channel is already owned by its transport. Fabric/Quilt cannot install a second
-- receiver for it and must say so; Forge's per-player frame tap sits below the channel and says yes.
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

minecheck.expect(minecheck.sendPacket("push", "from-server"), "pushed a server-initiated packet")

local done = minecheck.waitForPacket("done", 180)
minecheck.expect(done ~= false, "client reported done")

minecheck.runCommand("stop")
