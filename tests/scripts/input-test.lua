-- Input test: exercise mouse + keyboard against the real game in a creative superflat world, then
-- cover the remaining client surface against real game state (side/minecraftVersion/config,
-- wait/sleepUntil, lookAt, the held mouse form, pressCombination, keyPress).
--
--   mouse: look straight down, middle-click pick the grass block, left-click break it -> the player falls into the hole: y is 1 lower keyboard: hold space to jump, wait near the jump peak, right-click to place the picked grass block back -> the player lands on it: y is back up 1
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

local created = minecheck.createWorld({ name = "minecheck_world", seed = 42 })
expect(created, "created superflat world")

local settled = minecheck.waitFor(minecheck.worldGenSettled, 90)
expect(settled, "worldgen settled")

local start = minecheck.getPlayer()
expect(start ~= nil, "player exists")
local startY = math.floor(start.pos.y + 0.5)
minecheck.log("input test: start y = " .. startY)

-- --- mouse: pick + break the block under the player -------------------------

minecheck.look(90, 0) -- straight down
minecheck.sleep(0.3)

local target = minecheck.getTargetBlock(start)
expect(target ~= nil, "crosshair on a block when looking down")
expect(target.y == startY - 1, "crosshair on the block below the player")
minecheck.log("input test: target block y = " .. target.y)

expect(minecheck.mousePress(2), "middle-click picked the grass block")
expect(minecheck.mousePress(0), "left-click broke the block")

local fell = minecheck.waitFor(function()
  local p = minecheck.getPlayer()
  return p ~= nil and p.pos.y < start.pos.y - 0.5
end, 5)
expect(fell, "player fell after breaking the block below")
local lower = minecheck.getPlayer()
local lowerY = math.floor(lower.pos.y + 0.5)
expect(lowerY == startY - 1, "y is 1 lower after the break (got " .. lowerY .. ")")

-- --- keyboard: jump, then place the block back ------------------------------

-- Wait for the landing to be *observed* rather than guessed with a sleep: the fell-wait above
-- passes while the player is still falling. The hole floor is the lowest height reachable, so a
-- sample at that height can only be the landing, never a transit. lowerY is the resting feet
-- height in the hole (startY - 1), which is the deterministic baseline the steps below measure
-- against; lower.pos.y was captured mid-fall and is not.
local landed = minecheck.waitFor(function()
  local p = minecheck.getPlayer()
  return p ~= nil and p.pos.y <= lowerY + 0.05
end, 5)
expect(landed, "player landed in the hole")

-- Jump is a held key, sampled during movement ticks, so a fixed hold length races the tick: if the
-- whole hold falls inside one task batch, no movement tick ever sees it and the jump silently does
-- nothing. Hold until the jump is *observed* instead — a held key keeps the window open across
-- ticks, so one missed tick no longer loses the jump — and the predicate closing is itself the
-- assertion that the player left the ground. The 5 s bound keeps a real failure fast rather than
-- waiting out the default timeout.
--
-- The height that matters is the *placement space*, not just "airborne": the block goes at lowerY
-- and occupies lowerY..lowerY+1, so a click while the feet are still below lowerY+1 places it into
-- the player's own space and the server rejects it (the player then lands back on the hole floor,
-- one block low). The feet must clear lowerY+1 — a jump reaches ~1.32 above its start, so this is
-- an easy window, and it is the exact condition the click needs rather than a timing coincidence.
local clearsPlacementSpace = function()
  local p = minecheck.getPlayer()
  return p ~= nil and p.pos.y > lowerY + 1.0
end
local jumped = minecheck.press("key.jump", 5, clearsPlacementSpace)
expect(jumped ~= nil and jumped.pressed, "space jump")
expect(jumped ~= nil and jumped.released == "cond", "jump observed clearing the placement space")

-- Right-click while the crosshair still targets the hole floor BELOW the player, so the placed
-- block lands under the feet instead of into the player's own space.
expect(minecheck.mousePress(1), "right-click placed the block")

-- The proof the place worked is where the player comes to rest. A bare height check would be
-- satisfied mid-jump (the arc rises past the start y), so wait until the position repeats across
-- two polls — a jump is never still — and only then read the resting height: on the replaced block
-- that is the original y, and without it the player settles one block lower.
local prevY = nil
local settled = minecheck.waitFor(function()
  local p = minecheck.getPlayer()
  if p == nil then
    prevY = nil
    return false
  end
  local atRest = prevY ~= nil and math.abs(p.pos.y - prevY) < 0.001
  prevY = p.pos.y
  return atRest
end, 5)
expect(settled, "player came to rest after the jump")
local final = minecheck.getPlayer()
local finalY = math.floor(final.pos.y + 0.5)
expect(finalY == startY, "y is back to start after the place (got " .. finalY .. ")")
minecheck.log("input test: final y = " .. finalY)

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
expect(cfg.defaultTimeout == 60, "config().defaultTimeout is 60")
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
minecheck.sleep(0.2)
local aimed = minecheck.getTargetBlock(standing)
expect(aimed ~= nil, "lookAt produced a target block")
if aimed then
  expect(aimed.y == feetY - 1, "lookAt aimed at the block below the feet (got " .. aimed.y .. ")")
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
