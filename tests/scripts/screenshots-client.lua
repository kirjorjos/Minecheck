-- Screenshot corpus: the captures that have committed baselines, plus one ad-hoc region capture.
--
-- Nothing here "waits for the picture to settle": the script makes the picture static instead. The
-- world is a superflat with the daylight and weather cycles off, the time of day is pinned with a
-- command (noon or midnight — the two times where the sky light the lightmap derives is on a flat
-- part of its curve, so a capture a frame later is the same picture), the camera is set and then a
-- few ticks pass so its tick-to-tick interpolation has arrived, and the chat lines are cleared by
-- the capture itself, which reads the first frame drawn after that (see `captureScreenshot` in
-- docs/lua-api.md).

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen.
-- On a loader that never shows that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

local profile = minecheck.config()
-- The frame the profile pins, and the encode-time downscale the config asks for — the size a
-- full-frame capture must come back as.
local frameWidth = profile.screenshotWidth
local frameHeight = profile.screenshotHeight
local guiScale = profile.screenshotGuiScale
local scale = profile.screenshotScale

--- Captures `name` and checks the returned metadata against the profile.
local function shot(name, options)
  local capture = minecheck.captureScreenshot(name, options)
  minecheck.expect(capture.bytes > 0, name .. " wrote a non-empty PNG")
  minecheck.expect(capture.sha256 ~= "", name .. " reports a digest")
  minecheck.log(
    name
      .. " -> "
      .. capture.path
      .. " "
      .. capture.width
      .. "x"
      .. capture.height
      .. " "
      .. capture.bytes
      .. "B "
      .. capture.sha256
  )
  return capture
end

--- Runs a scene-setting command and fails the run when the server did not take it.
local function command(cmd)
  local result = minecheck.runCommand(cmd)
  minecheck.log(cmd .. " -> success=" .. tostring(result.success))
  minecheck.expect(result.success == true, "ran /" .. cmd)
end

--- Digests the frame one capture at a time, under an ad-hoc name (`_` ⇒ never baselined).
local settleIndex = 0
local function frameDigest()
  settleIndex = settleIndex + 1
  return minecheck.captureScreenshot("_settle-" .. settleIndex).sha256
end

--- Waits until the picture has stopped changing, i.e. two captures a second apart agree.
---
--- The level's lighting keeps easing for a while after the world is created: measured on this
--- corpus, the frame is nine levels brighter ten seconds in than it is half a second in, and no two
--- runs reach that point after the same number of frames. Once it has arrived, two captures of the
--- same scene are byte-identical, within a run and across runs — which is the property the
--- committed baselines rest on. Settling on the digest rather than on a fixed wait keeps that true
--- on a runner whose frames are far slower than a developer's.
local function settledFrame()
  local previous = frameDigest()
  for _ = 1, 30 do
    minecheck.wait(1)
    local current = frameDigest()
    if current == previous then
      return true
    end
    previous = current
  end
  return false
end

local created = minecheck.createWorld({ name = "minecheck_world", seed = 0 })
if not minecheck.expect(created, "created the screenshot world") then
  minecheck.fail("createWorld failed")
end
minecheck.expect(minecheck.waitFor(minecheck.isInWorld, 60), "joined the world")
minecheck.expect(minecheck.waitFor(minecheck.worldGenSettled, 120), "world generation settled")

-- The clock has to be *stopped*, not just moved. `time set` jumps the day time and then time runs
-- on, so the day time at the moment of a capture depended on how many ticks the scene setup
-- happened to take: the sun angle, the sky light the lightmap derives from it and the clock item's
-- dial all moved with it, which is a one-or-two-level difference across the frame and a rotated
-- dial in the inventory shot. With the cycle off, `time set` is the time of every frame from then
-- on, and the picture is a function of the world, not of the run's tick count.
command("gamerule doDaylightCycle false")
command("gamerule doWeatherCycle false") -- The camera is part of the picture, so it is pinned too: a fresh world spawns the player at a
-- random spot near the world spawn, and two runs standing a block apart see different terrain. The
-- teleport sets the position and the rotation (yaw 0, pitch 90 = straight down) in one go.
local function atPinnedSpot()
  local player = minecheck.getPlayer()
  return player ~= nil and player.pos.x == 0.5 and player.pos.y == -60.0 and player.pos.z == 0.5
end

local function stand()
  command("tp @s 0.5 -60 0.5 0 90")
  -- The command travels to the server and back, so the move lands a tick or two later.
  minecheck.expect(minecheck.waitFor(atPinnedSpot, 5), "the player stands on the pinned spot")
  local player = minecheck.getPlayer()
  minecheck.log(
    string.format("player at %.3f %.3f %.3f in %s", player.pos.x, player.pos.y, player.pos.z, player.dimension)
  )
end

-- 1. The world at noon, looking down: ground, the pinned HUD and the held-block highlight in one
-- frame, with the sky's light at the flat top of its curve.
command("time set 6000")
stand()
minecheck.expect(settledFrame(), "the frame stopped changing before the first capture")
local world = shot("world-settled")
minecheck.expect(world.width == frameWidth, "world-settled is " .. frameWidth .. " px wide")
minecheck.expect(world.height == frameHeight, "world-settled is " .. frameHeight .. " px tall")

-- 2. The same view at midnight. This is the capture that proves the clock is pinned: the sky light
-- the lightmap derives is at the bottom of its curve here, so a client left to advance its own
-- clock between server syncs would render a different picture.
command("time set 18000")
minecheck.wait(0.5)
local night = shot("world-night")
minecheck.expect(night.width == frameWidth, "world-night is " .. frameWidth .. " px wide")

-- 3. A container screen over the world: proves a GUI is captured, not just the terrain.
--
-- A chest, not the player inventory: every inventory screen carries something that keeps moving.
-- The creative one draws item models as its tab icons, and the clock/compass models pick their frame
-- from a continuously-derived angle — they landed a needle apart between two runs even with the
-- clock pinned and the frame settled. The survival one animates its player preview, so a capture of
-- it never stops changing either. A chest's panel is slots and a title: static by construction.
command("time set 6000")
command("setblock 2 -60 0 minecraft:chest")
-- `setblock` returns when the *server* has the block; the client learns about it from the chunk
-- packet a tick or two later. Clicking before that raycasts into air, and a missed interact is a
-- silent early return in the game's use path — the screen simply never opens, one run in three.
-- Wait for the client's own copy of the world instead of assuming the packet has landed.
minecheck.expect(
  minecheck.waitFor(function()
    local block = minecheck.getBlock({ x = 2, y = -60, z = 0 })
    return block ~= nil and block.id == "minecraft:chest"
  end, 5),
  "the client sees the chest before it is clicked"
)
stand()
-- `pos` is the point to aim at, not a block id: an integer would name the block's corner, and the
-- ray would graze it and report whatever air block shares that corner. Aim at the centre.
local opened = minecheck.openScreen("chest", { x = 2.5, y = -59.5, z = 0.5 })
if minecheck.expect(opened, "opened the chest") then
  minecheck.expect(settledFrame(), "the chest screen stopped changing before its capture")
  local chest = shot("chest-open")
  minecheck.expect(chest.width == frameWidth, "chest-open is " .. frameWidth .. " px wide")
  minecheck.press("Escape")
  minecheck.wait(0.1)
else
  minecheck.fail("openScreen(chest) failed")
end

-- 4. An ad-hoc crop (a leading `_` keeps it out of the baselines): the bottom strip of the HUD in
-- GUI pixels, to prove the region path resolves through the pinned GUI scale.
local hud = shot("_hud-region", {
  region = { x = 0, y = 200, w = frameWidth / guiScale, h = 40, unit = "gui" },
})
minecheck.expect(hud.width == math.floor(frameWidth * scale + 0.5), "_hud-region is the cropped width, not the frame")
minecheck.expect(
  hud.height == math.floor(40 * guiScale * scale + 0.5),
  "_hud-region is the cropped height, not the frame"
)

minecheck.quit()
