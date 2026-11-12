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
  -- A command is a server tick's work, so it cannot land while the clock is held: hand the loop
  -- back before sending it. The settle that follows stops it again.
  minecheck.resumeTicks()
  local result = minecheck.runCommand(cmd)
  minecheck.log(cmd .. " -> success=" .. tostring(result.success))
  minecheck.expect(result.success == true, "ran /" .. cmd)
end

-- The picture eases for several hundred ticks after a world is created (its lighting is still
-- settling), and how many of those ticks a run gets through before a capture depends on how fast
-- the runner draws frames — so sampling the picture until two frames happen to agree settles at a
-- different count on every machine, and wrote ~30 ad-hoc PNGs into the capture directory each run.
-- Own the clock instead: stop the server this client runs and advance it by ticks the script
-- chooses. Step in chunks and stop once two consecutive samples leave the picture identical: the
-- sample points are tick counts rather than wall-clock moments, so where a settle stops is a
-- property of the scene and not of the machine, and a scene that has converged after two hundred
-- ticks does not pay for four hundred.
--
-- The chunk is the caller's to choose (a scene that eases in a few ticks can be sampled every few
-- ticks; one whose lighting creeps can step further between reads), because how far apart the
-- samples are is a property of the scene, not of this helper. The cap and the chunk are both
-- per-call, so `settle` never decides for a scene how many ticks to step.
local SETTLE_CHUNK = 100
local SETTLE_MAX_TICKS = 400

local function settle(label, opts)
  opts = opts or {}
  local chunk = opts.chunk or SETTLE_CHUNK
  local maxTicks = opts.max or SETTLE_MAX_TICKS
  if chunk < 1 then
    minecheck.fail("settle chunk must be at least one tick, got " .. tostring(chunk))
  end
  minecheck.pauseTicks()
  local ticks = 0
  local previous
  while ticks < maxTicks do
    local step = math.min(chunk, maxTicks - ticks)
    minecheck.stepTicks(step)
    ticks = ticks + step
    -- `captureFrame` reads the picture without encoding or writing it: a probe costs a readback and
    -- a hash, which is what makes asking after every chunk cheaper than always stepping the cap.
    local frame = minecheck.captureFrame()
    if previous ~= nil and frame.sha256 == previous then
      break
    end
    previous = frame.sha256
  end
  minecheck.log(label .. " settled after " .. ticks .. " ticks")
  -- The client ticks on while the server stands still: give it a few frames of the now-frozen world
  -- to apply the stepped state and finish its own lightmap pass before the caller captures.
  minecheck.wait(0.5)
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

-- Spend the runner's CPU rather than its wall clock: with no tick cap the world runs at whatever
-- milliseconds-per-tick the machine manages, so the ticks between the scene steps stop being a
-- twenty-a-second floor. The captures themselves are still taken on a held clock.
minecheck.uncappedTicks()

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

-- Leave the clock running: a held server never finishes the shutdown this quit starts.
minecheck.resumeTicks()
minecheck.quit()
