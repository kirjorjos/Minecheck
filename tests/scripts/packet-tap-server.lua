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

-- Forge envelopes the frame behind a SimpleChannel VarInt message index; NeoForge's payload
-- registry does not. One script serves both taps: parse with the index first, then fall back to the
-- bare frame. A misaligned parse throws or yields an empty name, so the fallback only takes over
-- where the envelope really is absent.
local function readTicketedFrame(reader)
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
end

local function readBareFrame(reader)
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
end

minecheck.registerPacketDecoder("minecheck:main", function(bytes)
  -- Handshake/version payloads also travel on this channel; anything unparseable decodes to {} so
  -- the predicate simply skips it instead of failing the run.
  for _, parse in ipairs({ readTicketedFrame, readBareFrame }) do
    local ok, value = pcall(function()
      return parse(minecheck.reader(bytes))
    end)
    if ok and type(value.name) == "string" and #value.name > 0 then
      return value
    end
  end
  return {}
end)

-- As a phase, this decoder replaced the one the packet phase registered for `minecheck:main`, and
-- a raw frame decoded before the replacement is dropped. The client announces its tap phase and
-- waits for this go-ahead, so the first frame is never the one that loses that race (#121).
if _G.MINECHECK_PHASE then
  local entering = minecheck.waitForPacket("tap-entering", 300)
  minecheck.expect(entering ~= false, "the client entered its tap phase with a decoder registered")
  minecheck.expect(minecheck.sendPacket("tap-go"), "told the client the tap is ready")
end

-- 300 s: as a phase this wait starts when the server reaches the tap, minutes before the client
--  does (it is still working through the phases ahead of it on its side).
local ready = minecheck.waitForPacket("tap-ready", 300)
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

-- A phase leaves the server running for the phases after it (see `_G.MINECHECK_PHASE`).
if not _G.MINECHECK_PHASE then
  minecheck.runCommand("stop")
end
