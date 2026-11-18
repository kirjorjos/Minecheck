-- Camera test: the five camera actions against a client whose config installs the camera in
-- `assist`, with the game's own window hidden and the run's view window on (see `cameraMode` and
-- `viewWindow` in the client-features run's config.json). A lifecycle run has no human at the
-- controls, so the camera is entirely Lua's here.
--
-- A client that did not install the camera answers `cameraMode() == "off"` and raises from every
-- other action — the documented contract — so a run staged with the default config passes this
-- phase by checking that contract instead of failing on an action that cannot work.
local function expect(cond, name)
  if not minecheck.expect(cond, name) then
    minecheck.fail(name)
  end
end

local mode = minecheck.cameraMode()
if mode == "off" then
  minecheck.log("camera test: cameraMode is off — the camera actions are not installed")
  local ok, err = pcall(minecheck.cameraToggle)
  expect(
    not ok and tostring(err):find("camera is off") ~= nil,
    "a camera action raises while the camera is off (got " .. tostring(ok and "no error" or err) .. ")"
  )
  return
end
expect(mode == "assist", "cameraMode is assist (got " .. tostring(mode) .. ")")
expect(minecheck.config().viewWindow == true, "viewWindow is on for this run")
expect(minecheck.isInWorld(), "the camera phase runs in a world")

-- Detached: no pose, and an action aimed at the camera is inert rather than an error.
expect(minecheck.isCameraActive() == false, "the camera starts detached")
expect(minecheck.cameraPose() == nil, "a detached camera reports no pose")
expect(minecheck.cameraMove(1, 0, 0) == false, "cameraMove is inert while detached")

-- Attach, and the pose is a copy of the player's eye — not the origin.
local attached = minecheck.cameraToggle()
expect(attached == true, "cameraToggle() attached the camera")
expect(minecheck.isCameraActive() == true, "isCameraActive follows the attach")
local pose = minecheck.cameraPose()
expect(pose ~= nil, "an attached camera reports a pose")
for _, field in ipairs({ "x", "y", "z", "yaw", "pitch" }) do
  expect(type(pose[field]) == "number", "cameraPose()." .. field .. " is a number")
end
local player = minecheck.getPlayer()
expect(
  pose.y >= player.pos.y and pose.y <= player.pos.y + 3,
  string.format("the camera starts at the player's eye (camera y %.2f, player y %.2f)", pose.y, player.pos.y)
)

-- Idempotent by contract: asking for the state the camera is already in moves nothing.
expect(minecheck.cameraToggle(true) == true, "cameraToggle(true) is idempotent")
local again = minecheck.cameraPose()
expect(again.x == pose.x and again.z == pose.z, "an idempotent attach does not move the camera")

-- Forward is the yaw-only look direction.
local before = minecheck.cameraPose()
expect(minecheck.cameraMove(2, 0, 0) == true, "cameraMove reports the camera attached")
local forward = minecheck.cameraPose()
local travelled = math.abs(forward.x - before.x) + math.abs(forward.z - before.z)
expect(travelled > 0.1, string.format("cameraMove moved the camera forward (moved %.3f blocks)", travelled))

-- Up is world Y, whatever the camera is looking at.
local level = minecheck.cameraPose()
expect(minecheck.cameraMove(0, 2, 0) == true, "cameraMove reports the camera attached when flying up")
local raised = minecheck.cameraPose()
expect(raised.y > level.y + 0.1, string.format("cameraMove up raised the camera (%.3f -> %.3f)", level.y, raised.y))

-- Right is forward turned ninety degrees: it must move the camera, and not along the forward axis
-- it just travelled.
local rightward = minecheck.cameraPose()
expect(minecheck.cameraMove(0, 0, 2) == true, "cameraMove reports the camera attached when strafing")
local strafed = minecheck.cameraPose()
expect(
  math.abs(strafed.x - rightward.x) + math.abs(strafed.z - rightward.z) > 0.1,
  "cameraMove right moved the camera sideways"
)

-- A refused argument is an error the script can read, and the pose survives it.
local ok, err = pcall(minecheck.cameraMove, 0 / 0, 0, 0)
expect(not ok, "cameraMove refuses a non-finite amount")
expect(
  tostring(err):find("finite number") ~= nil,
  "cameraMove names the argument it refused (got " .. tostring(err) .. ")"
)
expect(minecheck.cameraPose() ~= nil, "the camera kept its pose after the refusal")

-- Detach: the player is the viewpoint again, and the camera is inert.
expect(minecheck.cameraToggle(false) == false, "cameraToggle(false) detached the camera")
expect(minecheck.isCameraActive() == false, "isCameraActive is false after the detach")
expect(minecheck.cameraPose() == nil, "a detached camera reports no pose again")
expect(minecheck.cameraMove(1, 0, 0) == false, "cameraMove is inert after the detach")

-- The flight was the camera's, not the player's.
local back = minecheck.getPlayer()
expect(back ~= nil, "the player is the client's viewpoint again")
expect(
  math.abs(back.pos.y - player.pos.y) < 0.5,
  string.format("the player did not fly with the camera (y %.2f -> %.2f)", player.pos.y, back.pos.y)
)
