-- Client half of the entity query integration test:
--  Join the dedicated server, assert what the *client* can see through its own view of the world,
--  then punch the server's target to death (the spec's "punch a mob with a particular tag" example,
--  with the type standing in for the tag because vanilla never syncs entity tags to clients).

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

-- A phase is already in the world; only the coordinator joins and leaves (see `_G.MINECHECK_PHASE`).
local phase = _G.MINECHECK_PHASE ~= nil
if not phase then
  local joined = minecheck.joinServer("localhost:25565", 300)
  if not minecheck.expect(joined, "joined query-test server") then
    minecheck.fail("joinServer failed")
  end
end

-- Client-side entity query. The server spawns a mob that never occurs naturally for this phase: a
-- vex on 1.11 and later, a snow golem where the line predates it (one only exists when a player
-- builds it), so the type is a handle the client can use to tell our target from worldgen mobs.
local TARGET_TYPE = (tonumber((minecheck.minecraftVersion():match("^%d+%.(%d+)")) or "") or 0) < 11
    and "minecraft:snow_golem"
  or "minecraft:vex"
local targetId = nil
local seen = minecheck.waitFor(function()
  local found = minecheck.findNearbyEntities(TARGET_TYPE)
  if #found == 1 then
    targetId = found[1].id
    return true
  end
  return false
end, 180)
if not minecheck.expect(seen, "client sees the server-spawned target") then
  minecheck.fail("the client never saw the punch target")
end
minecheck.log("query test: client sees target id " .. tostring(targetId))

local function target()
  for _, e in pairs(minecheck.findNearbyEntities(TARGET_TYPE)) do
    if e.id == targetId then
      return e
    end
  end
  return nil
end

-- The server has finished reading the target's state and cues us over Minecheck's packet channel.
-- Attacking any earlier would move the target under the server's own "settled and still" assertions
-- — and, as a phase, this wait is also what keeps the client from swinging before the server has put
-- the sword in its hand. The channel replays history, so a cue sent while the client was still in the
-- beacon phase still satisfies this.
local cued = minecheck.waitForPacket("punch_ready", 180)
if not minecheck.expect(cued ~= false, "server cued the punch phase") then
  minecheck.fail("no punch cue from the server")
end

-- Aim at it and hold attack until it is gone, re-finding it on every swing so a knockback nudge
-- (or the aim being a frame stale) cannot leave the crosshair behind.
local killed = false
for _ = 1, 40 do
  local t = target()
  if t == nil then
    killed = true
    break
  end
  -- The target can end up out of reach: a line without `NoGravity` drops it from the cell above
  -- the player's head, and in a merged session the player stands on a six-block beacon pyramid, so
  -- it lands at the bottom of the tower. Step to it rather than swinging at air — an op client can,
  -- and a client that is not op simply stays where it is and the swings below are unchanged.
  local p = minecheck.getPlayer()
  if p ~= nil then
    local dx = t.pos.x - p.pos.x
    local dy = t.pos.y - p.pos.y
    local dz = t.pos.z - p.pos.z
    if dx * dx + dy * dy + dz * dz > 9 then
      minecheck.runCommand("tp @s " .. string.format("%.2f %.2f %.2f", t.pos.x - 1.5, t.pos.y, t.pos.z))
      minecheck.waitFor(function()
        local now = minecheck.getPlayer()
        local still = target()
        return now ~= nil and still ~= nil and math.abs(now.pos.x - (still.pos.x - 1.5)) < 1.5
      end, 5)
    end
  end
  -- pos is the entity's feet, so aim a little above them rather than at the feet themselves.
  minecheck.lookAt(t.pos.x, t.pos.y + 0.4, t.pos.z)
  minecheck.press("key.attack", 0.7)
end
minecheck.expect(killed, "punched the target to death")
minecheck.expect(#minecheck.findNearbyEntities(TARGET_TYPE) == 0, "the client no longer sees it")

if not phase then
  local left = minecheck.leaveWorld(60)
  minecheck.expect(left, "left query-test server")
  minecheck.quit()
end
