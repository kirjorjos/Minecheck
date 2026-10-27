-- Forge frame-tap test, dedicated-server half:
--  The server half of the tap: watch Minecheck's OWN channel through the server-side frame tap and
--  decode the raw *serverbound* frame without any help from Minecheck's own transport. Then reply to
--  the client's own (client-side) tap, wait for its verdict, and stop the server.
--
--  Unlike Fabric/Quilt, no loaders' play-channel API can hand Minecheck a channel that is already
--  received (one handler per channel per direction), so the tap sits in front of vanilla's Connection
--  on each player connection and copies the decoded custom payload as it passes.

local observed = minecheck.watchPacketChannel("minecheck:main")
minecheck.expect(observed, "the server frame tap observes minecheck:main")
if not observed then
  minecheck.fail("watchPacketChannel returned false on a Forge dedicated server")
end

minecheck.registerPacketDecoder("minecheck:main", function(bytes)
  -- The decoder walks Forge's envelope first: SimpleChannel writes a VarInt message index, then the
  -- message payload — which for us is a Minecheck frame { int32 discriminator; String name; ... }.
  -- Handshake/version payloads also travel on this channel; anything unparseable decodes to {} so the
  -- predicate simply skips it instead of failing the run.
  local ok, value = pcall(function()
    local reader = minecheck.reader(bytes)
    reader.readVarInt() -- Forge SimpleChannel message index
    local discriminator = reader.readInt()
    local name = reader.readString()
    local argc = reader.readVarInt()
    local first = nil
    if argc >= 1 then
      reader.readVarInt()
      reader.readByte()
      first = reader.readZigZag()
    end
    return { discriminator = discriminator, name = name, first = first }
  end)
  if ok then
    return value
  end
  return {}
end)

local ready = minecheck.waitForPacket("tap-ready", 120)
minecheck.expect(ready ~= false, "client asked for tap traffic")

-- The same frame, as the tap saw it on the wire, with the argument the client actually sent.
local raw = minecheck.waitForPacket("minecheck:main", function(decoded)
  return decoded.name == "tap-ready"
end, 120)
minecheck.expect(raw ~= false, "the server tap saw the client's raw frame")
if raw then
  minecheck.expect(raw.args[1].discriminator == 0, "raw frame carries the script discriminator")
  minecheck.expect(raw.args[1].first == 4321, "raw frame payload decodes to 4321")
end

minecheck.expect(minecheck.sendPacket("tapped", 1234, "payload"), "sent the packet to be tapped")

local done = minecheck.waitForPacket("tap-done", 120)
minecheck.expect(done ~= false, "client finished the tap test")

minecheck.runCommand("stop")
