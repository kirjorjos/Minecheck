-- Screenshot corpus: the captures that have committed baselines, plus one ad-hoc region capture.
--
-- Nothing here "waits N seconds and hopes": the script makes the picture static and then waits for
-- the frame itself to stop moving. The world is a superflat with the daylight and weather cycles
-- off, the time of day is pinned with a command (noon or midnight — the two times where the sky
-- light the lightmap derives is on a flat part of its curve), the camera is set, and a capture is
-- taken only once two consecutive frames report the same digest (see `settle`), so a slow machine
-- settles later instead of shooting a different picture. The chat lines are cleared by the capture
-- itself, which reads the first frame drawn after that (see `captureScreenshot` in docs/lua-api.md).

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

-- The picture eases for several seconds after a world is created (its lighting is still settling),
-- and two runs reach the same point after different frame counts, so a fixed wait either shoots
-- mid-ease or wastes seconds. Settle on the picture instead: capture, wait, capture again, and stop
-- once two digests agree. The probes are ad-hoc (`_`), so they are uploaded but never baselined.
local settleCount = 0
local function settle(label)
  local previous
  -- 60 probes, not 30: after a world is created its lighting fades over a few hundred ticks, and at
  -- the client's real twenty ticks a second that is longer in wall-clock time than it was when the
  -- tick rate was accidentally driven by the frame rate. The bound is a budget, not a wait — a
  -- settled frame returns after two probes.
  for _ = 1, 60 do
    settleCount = settleCount + 1
    local probe = minecheck.captureScreenshot("_settle-" .. label .. "-" .. settleCount)
    if previous ~= nil and probe.sha256 == previous then
      minecheck.log("settled after " .. settleCount .. " probe(s)")
      return true
    end
    previous = probe.sha256
    minecheck.wait(0.25)
  end
  minecheck.fail("the frame never settled for " .. label)
  return false
end

local created = minecheck.createWorld({ name = "minecheck_world", seed = 0 })
if not minecheck.expect(created, "created the screenshot world") then
  minecheck.fail("createWorld failed")
end
minecheck.expect(minecheck.waitFor(minecheck.isInWorld, 60), "joined the world")
minecheck.expect(minecheck.waitFor(minecheck.worldGenSettled, 120), "world generation settled")

-- The camera is part of the picture, so it is pinned too: a fresh world spawns the player at a
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
settle("world")
local world = shot("world-settled")
minecheck.expect(world.width == frameWidth, "world-settled is " .. frameWidth .. " px wide")
minecheck.expect(world.height == frameHeight, "world-settled is " .. frameHeight .. " px tall")

-- 2. The same view at midnight. This is the capture that proves the clock is pinned: the sky light
-- the lightmap derives is at the bottom of its curve here, so a client left to advance its own
-- clock between server syncs would render a different picture.
command("time set 18000")
-- The command sets the *server's* time; the client learns it from the sync a tick or two later, and
-- the sky and lightmap ease to it, so settle on the digest rather than assuming a wait is enough.
settle("night")
local night = shot("world-night")
minecheck.expect(night.width == frameWidth, "world-night is " .. frameWidth .. " px wide")

-- 3. A container screen over the world: proves a GUI is captured, not just the terrain. A crafting
-- table rather than the player inventory, because in creative the inventory is
-- CreativeInventoryScreen and its tabs are item models: the compass and clock needles are
-- re-derived on every frame, which measured 24-56 pixels moving on *every* consecutive frame — a
-- picture no amount of settling makes reproducible. A block menu has no such element.
command("setblock 1 -60 0 minecraft:crafting_table")
local tablePos = { x = 1, y = -60, z = 0 }
minecheck.expect(
  minecheck.waitFor(function()
    return minecheck.getBlock(tablePos) ~= nil
  end, 10),
  "the crafting table reached the client"
)

-- Aim at the block's centre, not the position that names it: (1, -60, 0) is a corner of the cube, so
-- a ray to it grazes the edge and the click that opens the menu hits nothing. `openScreen` with a
-- position aims and clicks in one step, so the aim is confirmed here instead and the click follows
-- the crosshair.
local tableCentre = { x = 1.5, y = -59.5, z = 0.5 }
minecheck.expect(minecheck.lookAt(tableCentre.x, tableCentre.y, tableCentre.z), "aimed at the table")
minecheck.expect(
  minecheck.waitFor(function()
    local hit = minecheck.getTargetBlock(minecheck.getPlayer())
    return hit ~= nil and hit.x == 1 and hit.y == -60 and hit.z == 0
  end, 10),
  "the crosshair is on the table"
)
local opened = minecheck.openScreen("crafting", nil, 10)
if minecheck.expect(opened, "opened the crafting table") then
  settle("crafting")
  local crafting = shot("crafting-open")
  minecheck.expect(crafting.width == frameWidth, "crafting-open is " .. frameWidth .. " px wide")
  minecheck.press("escape")
  -- The HUD capture below needs the world back, so wait for the screen to be gone rather than
  -- assuming the keypress was consumed by the next frame.
  minecheck.expect(
    minecheck.waitFor(function()
      return not minecheck.isScreenOpen(nil)
    end, 5),
    "the crafting screen closed"
  )
  settle("hud")
else
  minecheck.fail("openScreen(crafting) failed")
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
