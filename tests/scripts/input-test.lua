-- Input test: exercise mouse + keyboard against the real game in a creative superflat world, then
-- cover the remaining client surface against real game state (side/minecraftVersion/config,
-- wait/sleepUntil, lookAt, the held mouse form, pressCombination, keyPress).
--
--   keyboard: hold space to jump from the surface, land again
--   mouse: look straight down, middle-click pick the grass block under the player
--   mouse: look at the surface block beside the player, left-click break it
--   mouse: look at the surface block on the other side, right-click -> the picked block is placed in the free cell above it
--
-- The player stays on the surface for all of it. A break under its own feet drops it into the hole,
-- and a tap can be observed on two input passes, so that step digs two deep on some runners — the
-- camera ends up below the surface plane and every aim at a top face becomes a side-face hit that
-- places somewhere else. Nowhere here depends on where the player lands.
--
-- Creative is deliberate: pick-block (middle click) puts the block type in hand with no drops involved, and breaking/placing are instant single clicks.

-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

local function expect(cond, name)
  if not minecheck.expect(cond, name) then
    minecheck.fail(name .. " failed")
  end
end

local function blockLabel(b)
  if b == nil then
    return "nothing"
  end
  return b.x .. "," .. b.y .. "," .. b.z
end

local created = minecheck.createWorld({ name = "minecheck_world", seed = 42 })
expect(created, "created superflat world")

local settled = minecheck.waitFor(minecheck.worldGenSettled, 90)
expect(settled, "worldgen settled")

local start = minecheck.getPlayer()
expect(start ~= nil, "player exists")
local startY = math.floor(start.pos.y + 0.5)
minecheck.log("input test: start y = " .. startY)

-- --- keyboard: jump -------------------------------------------------------------
--
-- On the surface the world spawn put the player on, before anything is broken. A jump started while
-- the client's predicted position is being corrected by the server is refused or corrected away:
-- under a loaded runner it leaves no trace on the client at all (measured at one CPU: the client
-- never rose more than 0.25 of a block while the key was held, though a jump did happen on the
-- server). On the settled surface nothing is pending, so the arc is the client's own.
local function aboveSurface()
  local p = minecheck.getPlayer()
  return p ~= nil and p.pos.y > startY + 0.5
end
local jumped = minecheck.press("key.jump", 5, aboveSurface)
expect(jumped ~= nil and jumped.pressed, "space jump")
expect(jumped ~= nil and jumped.released == "cond", "jump observed leaving the ground")
expect(
  minecheck.waitFor(function()
    local p = minecheck.getPlayer()
    return p ~= nil and p.pos.y <= startY + 0.05
  end, 10),
  "player landed back on the surface"
)

-- --- mouse: pick + break the block under the player -------------------------

minecheck.look(90, 0) -- straight down
-- The rotation is applied on the client thread and the crosshair is raycast from the camera, so
-- wait for it to land instead of giving the change a fixed 0.3 s to happen. The wait names the
-- exact block the choreography below acts on, x/z included: "a block under the crosshair" is
-- already true of the pre-look aim, so a wait that loose resolves before the rotation lands.
local feetBlock = { x = math.floor(start.pos.x), y = startY - 1, z = math.floor(start.pos.z) }
expect(
  minecheck.waitFor(function()
    local hit = minecheck.getTargetBlock(start)
    return hit ~= nil and hit.x == feetBlock.x and hit.y == feetBlock.y and hit.z == feetBlock.z
  end, 5),
  "the crosshair landed on the block below the player"
)

local target = minecheck.getTargetBlock(start)
expect(target ~= nil, "crosshair on a block when looking down")
expect(target.y == startY - 1, "crosshair on the block below the player")
minecheck.log("input test: target block y = " .. target.y)

local pickedBlock = minecheck.getBlock(target)
expect(pickedBlock ~= nil and pickedBlock.id ~= "minecraft:air", "read the block the crosshair is on")

-- Creative is deliberate: pick-block (middle click) puts the block type in hand with no drops
-- involved, and breaking is an instant single click.
--
-- Both clicks act on the crosshair target the *renderer* last computed, not on a per-tick raycast,
-- and a miss is a silent early return in the game's pick/use path. Right after a look the crosshair
-- can still hold the pre-look aim, which is exactly what a software-rendered runner produces: one
-- press lands on nothing and the click is simply gone. Press until the effect is observed instead —
-- each press spans a tick, so the presses themselves are what let the crosshair catch up.

-- The creative inventory screen is not the survival one: slots 0..44 are the item-list tabs and the
-- player's hotbar is 45..53 (slotCount 54), so a read at 36..44 returns tab contents and says
-- nothing about the hand.
local function hotbarHolds(itemId)
  if not minecheck.openScreen("inventory", nil, 10) then
    return false
  end
  local found = minecheck.waitFor(function()
    for i = 45, 53 do
      local s = minecheck.getSlot(i, "inventory")
      if s ~= nil and not s.empty and s.id == itemId then
        return true
      end
    end
    return false
  end, 10)
  minecheck.press("Escape")
  minecheck.waitFor(function()
    return not minecheck.isScreenOpen()
  end, 5)
  return found
end

local picked = false
for _ = 1, 3 do
  for _ = 1, 20 do
    minecheck.mousePress(2)
  end
  picked = hotbarHolds(pickedBlock.id)
  if picked then
    break
  end
  minecheck.look(90, 0)
  minecheck.waitFor(function()
    local hit = minecheck.getTargetBlock(start)
    return hit ~= nil and hit.x == target.x and hit.y == target.y and hit.z == target.z
  end, 5)
end
expect(picked, "middle-click put the picked block in the hotbar")

-- A press acts on the *renderer's* crosshair, not on the raycast the script can compute: the
-- crosshair is refreshed once per frame, so waiting for the script's own raycast to carry the block
-- says nothing about what the next press will hit. Wait for the crosshair itself.
expect(
  minecheck.waitFor(function()
    local hit = minecheck.getCrosshairBlock()
    return hit ~= nil and hit.x == target.x and hit.y == target.y and hit.z == target.z
  end, 30),
  "the crosshair reached the block below (crosshair on " .. blockLabel(minecheck.getCrosshairBlock()) .. ")"
)

-- Break the block *beside* the player's column rather than the one under its feet. A tap is only
-- guaranteed to be observed by one input pass; a runner that observes two breaks the newly exposed
-- block below as well, which under the player's own feet digs a two-deep pit (measured on CI: the
-- feet went to startY-2) and drops the camera below the surface. Beside the player the second break
-- is harmless — its column is never stood on.
local dugPos = { x = target.x, y = target.y, z = target.z - 1 }
minecheck.lookAt(dugPos.x + 0.5, dugPos.y + 1.0, dugPos.z + 0.5)
-- lookAt is dispatched to the client thread; wait for the crosshair to arrive rather than assuming
-- 0.2 s is enough for the rotation to apply.
minecheck.waitFor(function()
  local hit = minecheck.getTargetBlock(minecheck.getPlayer())
  return hit ~= nil and hit.x == dugPos.x and hit.y == dugPos.y and hit.z == dugPos.z
end, 5)
local dugAim = minecheck.getTargetBlock(minecheck.getPlayer())
expect(
  dugAim ~= nil and dugAim.x == dugPos.x and dugAim.y == dugPos.y and dugAim.z == dugPos.z,
  "aimed at the surface block beside the player"
)
-- A click acts on the renderer's crosshair, which is refreshed once per frame: wait for the crosshair
-- itself to carry the block before spending a press on it.
expect(
  minecheck.waitFor(function()
    local hit = minecheck.getCrosshairBlock()
    return hit ~= nil and hit.x == dugPos.x and hit.y == dugPos.y and hit.z == dugPos.z
  end, 30),
  "the crosshair reached the block to break (crosshair on " .. blockLabel(minecheck.getCrosshairBlock()) .. ")"
)
local broke = false
for _ = 1, 20 do
  local b = minecheck.getBlock(dugPos)
  if b ~= nil and b.id == "minecraft:air" then
    broke = true
    break
  end
  minecheck.mousePress(0)
end
expect(broke, "left-click broke the block beside the player")
local afterBreak = minecheck.getPlayer()
expect(
  math.abs(afterBreak.pos.y - start.pos.y) < 0.05,
  string.format("player stayed on the surface after the break (y %.2f, was %.2f)", afterBreak.pos.y, start.pos.y)
)

-- The block goes into the free cell above the untouched surface block on the *other* side: the game
-- refuses any placement that would overlap the player's bounding box, and the cell above a block the
-- player stands beside is both free and outside that box. Aiming at that block's top face from the
-- surface makes the click's own face UP, which is what picks the cell above it.
local besidePos = { x = target.x, y = target.y, z = target.z + 1 }
local placePos = { x = besidePos.x, y = besidePos.y + 1, z = besidePos.z }
minecheck.lookAt(besidePos.x + 0.5, besidePos.y + 1.0, besidePos.z + 0.5)
minecheck.waitFor(function()
  local hit = minecheck.getTargetBlock(minecheck.getPlayer())
  return hit ~= nil and hit.x == besidePos.x and hit.y == besidePos.y and hit.z == besidePos.z
end, 5)
local besideAim = minecheck.getTargetBlock(minecheck.getPlayer())
expect(
  besideAim ~= nil and besideAim.x == besidePos.x and besideAim.y == besidePos.y and besideAim.z == besidePos.z,
  "aimed at the block the placed block goes beside"
)
-- The wait that decides whether this step can pass at all on a loaded runner: a press acts on the
-- renderer's crosshair, which is only as fresh as the last frame. Waiting for the crosshair to carry
-- the block first makes the press the last step rather than a retry.
expect(
  minecheck.waitFor(function()
    local hit = minecheck.getCrosshairBlock()
    return hit ~= nil and hit.x == besidePos.x and hit.y == besidePos.y and hit.z == besidePos.z
  end, 30),
  "the crosshair reached the block to place beside (crosshair on " .. blockLabel(minecheck.getCrosshairBlock()) .. ")"
)

-- Use is a *held* input, not an edge: the game reads the use button while it is down, so a one-tick
-- tap is only a real attempt if a tick's input pass happens to fall inside that tick — the same
-- shape of race the jump above avoids by holding its key. Hold the button for a quarter second per
-- attempt instead, which is several ticks of down state and several of the game's own repeats, and
-- re-check the crosshair before each one: a press acts on the crosshair as it stands at that tick,
-- so an attempt spent on a frame that raycast a different block places where this loop is not
-- looking. A refusal then costs a tick rather than the attempt.
local function crosshairOn(pos)
  local hit = minecheck.getCrosshairBlock()
  return hit ~= nil and hit.x == pos.x and hit.y == pos.y and hit.z == pos.z
end
local placedBlock = nil
for attempt = 1, 20 do
  local b = minecheck.getBlock(placePos)
  if b ~= nil and b.id ~= "minecraft:air" then
    placedBlock = b
    break
  end
  minecheck.waitFor(function()
    return crosshairOn(besidePos)
  end, 10)
  minecheck.mouse(1, 0.25)
end
local restingAfterPlace = minecheck.getPlayer()
expect(
  placedBlock ~= nil,
  "right-click placed the block (20 holds; crosshair on "
    .. blockLabel(minecheck.getCrosshairBlock())
    .. ", target cell "
    .. blockLabel(placePos)
    .. ", player at "
    .. string.format("%.2f,%.2f,%.2f", restingAfterPlace.pos.x, restingAfterPlace.pos.y, restingAfterPlace.pos.z)
    .. ")"
)
expect(
  placedBlock ~= nil and placedBlock.id == pickedBlock.id,
  "the placed block is the picked block (got " .. tostring(placedBlock and placedBlock.id) .. ")"
)

local resting = minecheck.getPlayer()
local restingY = math.floor(resting.pos.y + 0.5)
expect(restingY == startY, "player stayed on the surface after the place (got " .. restingY .. ")")
minecheck.log("input test: final y = " .. restingY)

-- --- surface coverage: predicates, config, timing, key taps, held mouse ----------
-- Independent of the break/place choreography above, so it runs last: side / minecraftVersion /
-- config, wait / sleepUntil, lookAt + getTargetBlock, the held form of mouse, pressCombination,
-- and keyPress. Each one is asserted against game state rather than just "it returned".

expect(minecheck.side() == "client", "side is client")
local version = minecheck.minecraftVersion()
expect(
  type(version) == "string" and string.match(version, "^%d+%.%d+") ~= nil,
  "minecraftVersion looks like a version (got " .. tostring(version) .. ")"
)

local cfg = minecheck.config()
expect(cfg ~= nil and cfg.script == "input-test.lua", "config().script is the running script")
expect(cfg.failMode == "exit", "config().failMode is exit")
expect(cfg.defaultTimeout == 300, "config().defaultTimeout is 300")
expect(cfg.coordinator == nil, "config().coordinator is absent for a plain script")

-- wait(): no argument waits exactly one tick, a number waits that many seconds. Both must hand
-- control back to the game rather than only look like they did.
minecheck.wait()
minecheck.wait(0.05)
expect(minecheck.isInWorld(), "still in the world after wait()")

-- sleepUntil is the waitFor alias, timeout result included.
expect(
  minecheck.sleepUntil(function()
    return true
  end, 5),
  "sleepUntil returns true when the condition holds"
)
expect(minecheck.sleepUntil(function()
  return false
end, 0.3) == false, "sleepUntil returns false on timeout")

-- lookAt: aim at a point below the feet and confirm the crosshair lands on the block under them.
local standing = minecheck.getPlayer()
local feetY = math.floor(standing.pos.y + 0.5)
expect(minecheck.lookAt(standing.pos.x, standing.pos.y - 2.0, standing.pos.z), "lookAt aimed")
-- Same as the look-down above: the aim is applied on the client thread, so wait for the crosshair
-- to carry the exact block the assertion reads rather than for "some" block to be under it.
local underFeet = { x = math.floor(standing.pos.x), y = feetY - 1, z = math.floor(standing.pos.z) }
minecheck.waitFor(function()
  local hit = minecheck.getTargetBlock(standing)
  return hit ~= nil and hit.x == underFeet.x and hit.y == underFeet.y and hit.z == underFeet.z
end, 5)
local aimed = minecheck.getTargetBlock(standing)
expect(aimed ~= nil, "lookAt produced a target block")
if aimed then
  expect(
    aimed.x == underFeet.x and aimed.y == underFeet.y and aimed.z == underFeet.z,
    "lookAt aimed at the block below the feet (got " .. aimed.x .. "," .. aimed.y .. "," .. aimed.z .. ")"
  )
end

-- mouse: the held form (as opposed to the mousePress tap used above). Middle-click picks the
-- block the lookAt aiming is now pointing at.
local picked = minecheck.mouse(2, 0.05)
expect(type(picked) == "table" and picked.pressed == true, "mouse(2) pressed and released")

-- pressCombination: both keys go down, and an unresolvable key makes the whole thing a no-op
-- that releases whatever it had already pressed.
local combo = minecheck.pressCombination({ "key.sneak", "key.jump" }, 0.1)
expect(type(combo) == "table" and combo.pressed == true, "pressCombination pressed both keys")
local noop = minecheck.pressCombination({ "key.jump", "not.a.real.key" })
expect(type(noop) == "table" and noop.pressed == false, "an invalid key makes the combination a no-op")

-- keyPress: a real tap of the inventory key opens the screen, and the same key closes it again.
-- (Vanilla has no `key.escape` binding in 1.19.2, so the inventory key is the close path here.)
expect(minecheck.keyPress("key.inventory"), "keyPress tapped the inventory key")
expect(
  minecheck.waitFor(function()
    return minecheck.isScreenOpen("inventory")
  end, 5),
  "keyPress opened the inventory screen"
)
expect(minecheck.keyPress("key.inventory"), "keyPress tapped the inventory key again")
expect(
  minecheck.waitFor(function()
    return not minecheck.isScreenOpen("inventory")
  end, 5),
  "the inventory key closed the inventory screen"
)

expect(minecheck.isInWorld(), "still in the world after the screen opened and closed")

minecheck.quit()
