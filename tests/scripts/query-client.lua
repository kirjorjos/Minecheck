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

local joined = minecheck.joinServer("localhost:25565", 300)
if not minecheck.expect(joined, "joined query-test server") then
  minecheck.fail("joinServer failed")
end

-- Client-side entity query. The server spawns a vex for this phase: vexes never spawn naturally in
-- the overworld, so the type is a handle the client can actually use to tell our target apart from
-- worldgen mobs.
local targetId = nil
local seen = minecheck.waitFor(function()
  local found = minecheck.findNearbyEntities("minecraft:vex")
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
  for _, e in pairs(minecheck.findNearbyEntities("minecraft:vex")) do
    if e.id == targetId then
      return e
    end
  end
  return nil
end

-- The server has finished reading the target's state and cues us over Minecheck's packet channel.
-- Attacking any earlier would move the target under the server's own "settled and still" assertions.
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
  -- pos is the entity's feet; a vex is 0.8 tall, so its middle is 0.4 up.
  minecheck.lookAt(t.pos.x, t.pos.y + 0.4, t.pos.z)
  minecheck.press("key.attack", 0.7)
end
minecheck.expect(killed, "punched the target to death")
minecheck.expect(#minecheck.findNearbyEntities("minecraft:vex") == 0, "the client no longer sees it")

local left = minecheck.leaveWorld(60)
minecheck.expect(left, "left query-test server")

minecheck.quit()
