-- Forge frame-tap test, client half:
--  Observe Minecheck's OWN channel through the low-level play-connection tap (the Forge-only
--  observation path) and decode the raw frame without any help from Minecheck's own transport.
--
--  The decoder walks Forge's envelope first: SimpleChannel writes a VarInt message index, then the
--  message payload — which for us is a Minecheck frame { int32 discriminator; String name; ... }.

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

local joined = minecheck.joinServer("localhost:25565", 120)
if not minecheck.expect(joined, "joined the dedicated server") then
  minecheck.fail("joinServer failed")
end

local observed = minecheck.watchPacketChannel("minecheck:main")
minecheck.expect(observed, "the frame tap observes minecheck:main")
if not observed then
  minecheck.fail("watchPacketChannel returned false on a Forge client")
end

minecheck.registerPacketDecoder("minecheck:main", function(bytes)
  -- Handshake/version payloads also travel on this channel; anything unparseable decodes to {} so
  -- the predicate simply skips it instead of failing the run.
  local ok, value = pcall(function()
    local reader = minecheck.reader(bytes)
    reader.readVarInt() -- Forge SimpleChannel message index
    local discriminator = reader.readInt()
    local name = reader.readString()
    local argc = reader.readVarInt()
    local first = nil
    if argc >= 1 then
      -- Each value is length-framed: VarInt frameLength, then the tag byte, then the payload.
      reader.readVarInt()
      reader.readByte()
      first = reader.readZigZag() -- Minecheck i32 arguments are ZigZag VarInts
    end
    return { discriminator = discriminator, name = name, first = first }
  end)
  if ok then
    return value
  end
  return {}
end)

-- The argument is what proves the *server-side* tap decoded this frame on the wire (4321, not
-- the 1234 the server sends back).
minecheck.expect(minecheck.sendPacket("tap-ready", 4321), "asked the server for tap traffic")

local frame = minecheck.waitForPacket("minecheck:main", function(decoded)
  return decoded.name == "tapped"
end, 60)
minecheck.expect(frame ~= false, "the tap saw the server's raw frame")
if frame then
  minecheck.expect(frame.args[1].discriminator == 0, "raw frame carries the script discriminator")
  minecheck.expect(frame.args[1].first == 1234, "raw frame payload decodes to 1234")
end

minecheck.sendPacket("tap-done")

local left = minecheck.leaveWorld(30)
minecheck.expect(left, "left the server")
minecheck.quit()
