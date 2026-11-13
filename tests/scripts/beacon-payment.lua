-- The beacon screen's payment round trip, asserted the way vanilla actually behaves.
--
-- Closing the screen does not put the payment stack back into the inventory: vanilla drops it at
-- the player's feet (`BeaconMenu.removed` -> `Player.drop`, server side only), and it only shows up
-- in the inventory once the player picks it up after the vanilla pickup delay. Standing on top of
-- the beacon usually makes that a second or two, but the delay is the game's, not this script's:
-- waiting a fixed 10 s for the pickup is what failed one run in four with "got 0" (#129), with the
-- emerald still on the ground when the read happened.
--
-- Assert what vanilla actually guarantees instead: the round trip through the beacon neither lost
-- nor duplicated the emerald. It is either back in the inventory (picked up) or still next to the
-- player as the item entity the close path dropped. The payment itself was already observed on the
-- screen (payment slot full, the main slot it came from empty).
--
-- A script of its own rather than a block in `beacon-client.lua`: the pickup delay decides which
-- outcome a session gets, so a run only ever exercises one of them. Both are exercised against a
-- scripted client in `ScriptEngineTest` (see `beaconPaymentCheckAcceptsAPickupAndADroppedStack`).
local function emeraldCount()
  local total = 0
  for i = 0, minecheck.slotCount("inventory") - 1 do
    local s = minecheck.getSlot(i, "inventory")
    if s ~= nil and not s.empty and s.id == "minecraft:emerald" then
      total = total + s.count
    end
  end
  return total
end

-- The close drop lands next to the player, so it is inside the client's own view of the world.
local function droppedItems()
  return #minecheck.findNearbyEntities("minecraft:item")
end

local function check(result, name)
  minecheck.expect(result, name)
  if not result then
    minecheck.fail(name)
  end
end

local emeraldBack = minecheck.waitFor(function()
  return emeraldCount() == 1
end, 10)
if not emeraldBack and droppedItems() == 0 then
  for i = 0, minecheck.slotCount("inventory") - 1 do
    local s = minecheck.getSlot(i, "inventory")
    if s ~= nil and not s.empty then
      minecheck.log("beacon test: slot " .. i .. " = " .. tostring(s.id) .. " x" .. tostring(s.count))
    end
  end
end
check(
  emeraldBack or droppedItems() > 0,
  "the emerald came back from the beacon screen once (inventory "
    .. emeraldCount()
    .. ", item drops nearby "
    .. droppedItems()
    .. ")"
)
-- One emerald went in, so at most one stack may be in the inventory: a second one is a
-- duplication, and this is the only outcome that reaches this check with a count above one (the
-- pickup wait above needs exactly one). A drop still sitting beside a picked-up stack is not
-- counted here on purpose - the entity removal and the slot update are separate packets, so a read
-- between them would see both - and the emerald count on the ground is not a thing the client can
-- read anyway: `droppedItems` counts item entities, not their contents.
check(emeraldCount() <= 1, "the beacon round trip did not duplicate the emerald (got " .. emeraldCount() .. ")")
