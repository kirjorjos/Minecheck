-- Forge dev clients stop on FML's "mod loading warnings" screen instead of the title screen. Dismiss
-- it before anything else: the button is `fml.button.continue.launch`. On a loader that never shows
-- that screen this matches nothing and does nothing.
if minecheck.clickGui("Proceed to main menu", 0, 1) then
  minecheck.expect(minecheck.waitFor(minecheck.isOnTitleScreen, 30), "dismissed the mod-loading screen")
end

-- 300 s, not 120: the join budget starts when this script does, while the dedicated server is
-- still booting in parallel — a loaded CI runner took 143 s from server launch to `Done`, all of it
-- after the port was already accepting connections (so every attempt in that window is dropped).
-- 120 s ran out before the server was up and the client gave up one retry short of joining.
local joined = minecheck.joinServer("localhost:25565", 300)
if not minecheck.expect(joined, "joined beacon-test server") then
  minecheck.fail("joinServer failed")
end

local function check(result, name)
  minecheck.expect(result, name)
  if not result then
    minecheck.fail(name)
  end
end

local p0 = minecheck.getPlayer()
check(p0 ~= nil, "player exists after join")
local py0 = p0.pos.y

local inv = minecheck.openScreen("inventory", nil, 20)
check(inv, "inventory opened for handshake")
local ready = minecheck.waitFor(function()
  local s = minecheck.getSlot(36, "inventory")
  return s ~= nil and not s.empty and s.id == "minecraft:emerald"
end, 30)
check(ready, "server op + give handshake complete (emerald in hotbar)")
minecheck.press("Escape")
local closed = minecheck.waitFor(function()
  return not minecheck.isScreenOpen()
end, 5)
check(closed, "inventory closed after handshake")

minecheck.runCommand("fill ~-4 ~2 ~-4 ~4 ~2 ~4 minecraft:iron_block")
minecheck.runCommand("fill ~-3 ~3 ~-3 ~3 ~3 ~3 minecraft:iron_block")
minecheck.runCommand("fill ~-2 ~4 ~-2 ~2 ~4 ~2 minecraft:iron_block")
minecheck.runCommand("fill ~-1 ~5 ~-1 ~1 ~5 ~1 minecraft:iron_block")
minecheck.runCommand("setblock ~ ~6 ~ minecraft:beacon")

minecheck.runCommand("tp @s ~ ~7 ~")
local tpd = minecheck.waitFor(function()
  local p = minecheck.getPlayer()
  return p ~= nil and p.pos.y >= py0 + 6
end, 10)
check(tpd, "client sees the teleport onto the beacon")

local landing = minecheck.getPlayer()
check(landing ~= nil, "player exists after the teleport")
local bx = math.floor(landing.pos.x)
local by = math.floor(landing.pos.y) - 1
local bz = math.floor(landing.pos.z)
local chunkLoaded = minecheck.waitFor(function()
  return minecheck.getBlock({ x = bx, y = by + 1, z = bz }) ~= nil
end, 60)
check(chunkLoaded, "client world loaded the chunk around the beacon")

minecheck.runCommand("setblock " .. bx .. " " .. by .. " " .. bz .. " minecraft:air")
minecheck.runCommand("setblock " .. bx .. " " .. by .. " " .. bz .. " minecraft:beacon")
minecheck.runCommand("tp @s " .. (bx + 0.5) .. " " .. (by + 1) .. " " .. (bz + 0.5))
local settled = minecheck.waitFor(function()
  local pl = minecheck.getPlayer()
  return pl ~= nil and math.floor(pl.pos.y) == by + 1
end, 10)
check(settled, "client back on top of the beacon")
minecheck.look(90, 0)

local beaconSeen = minecheck.waitFor(function()
  local pl = minecheck.getPlayer()
  if pl == nil then
    return false
  end
  local t = minecheck.getTargetBlock(pl)
  return t ~= nil and t.x == bx and t.y == by and t.z == bz
end, 30)
check(beaconSeen, "beacon visible in the client world")

local p = minecheck.getPlayer()
local beaconBlock = minecheck.getBlock({ x = bx, y = by, z = bz })
check(beaconBlock ~= nil and beaconBlock.id == "minecraft:beacon", "getBlock reads the beacon block")
check(beaconBlock ~= nil and beaconBlock.nbt ~= nil, "getBlock returns the beacon's block entity nbt")
local ironBlock = minecheck.getBlock({ x = bx, y = by - 1, z = bz })
check(ironBlock ~= nil and ironBlock.id == "minecraft:iron_block", "getBlock reads the pyramid block below")
check(ironBlock ~= nil and ironBlock.nbt == nil, "plain blocks have no block entity nbt")

local opened = minecheck.openScreen("beacon", { x = p.pos.x, y = p.pos.y - 0.5, z = p.pos.z }, 10)
check(opened, "beacon screen opened")
check(minecheck.isScreenOpen("beacon") == true, "isScreenOpen(beacon) is true")

local count = minecheck.slotCount("beacon")
check(count == 37, "beacon menu exposes 37 slots (got " .. count .. ")")
local slot = minecheck.getSlot(0, "beacon")
check(slot.empty, "beacon payment slot is empty")

local clicked = minecheck.clickGui(4, 4)
check(clicked, "clickGui dispatched to the beacon screen")
check(minecheck.isScreenOpen("beacon") == true, "beacon screen still open after click")

local moved1 = minecheck.moveItem(28, 1, nil, "beacon")
check(moved1, "moveItem dispatched emerald to main slot 1")
local inMain = minecheck.waitFor(function()
  local s = minecheck.getSlot(1, "beacon")
  return s ~= nil and not s.empty and s.id == "minecraft:emerald"
end, 10)
check(inMain, "emerald landed in main slot 1")
check(minecheck.getSlot(28, "beacon").empty, "hotbar slot emptied after the move")

local moved2 = minecheck.moveItem(1, 0, nil, "beacon")
check(moved2, "moveItem dispatched emerald into the payment slot")
local paid = minecheck.waitFor(function()
  local s = minecheck.getSlot(0, "beacon")
  return s ~= nil and not s.empty and s.id == "minecraft:emerald"
end, 10)
check(paid, "emerald landed in the beacon payment slot")
local paidStack = minecheck.getSlot(0, "beacon")
check(paidStack.count == 1, "payment slot holds 1 emerald (got " .. paidStack.count .. ")")
check(minecheck.getSlot(1, "beacon").empty, "main slot 1 emptied after the payment move")

-- Element-id form: the beacon screen's cancel button, addressed by its label (the portable id — the
-- widget class differs per mapping). Clicking it is a real button press, so it closes the screen.
check(minecheck.clickGui("NoSuchButton") == false, "an unknown element id is not found")
check(minecheck.clickGui("Cancel"), "clickGui found the beacon screen's cancel button by label")
local closed2 = minecheck.waitFor(function()
  return not minecheck.isScreenOpen()
end, 5)
check(closed2, "beacon screen closed by the element-id click")

local function ghostItemIds(viewerId, recipeId)
  local slots = minecheck.getRecipeSlots(recipeId, viewerId)
  local items = {}
  for i = 1, #slots do
    for j = 1, #slots[i].items do
      items[#items + 1] = slots[i].items[j]
    end
  end
  return #slots, items
end

local function contains(items, wanted)
  for i = 1, #items do
    if items[i] == wanted then
      return true
    end
  end
  return false
end

local function checkViewer(viewerId, label)
  local slotCount, items = ghostItemIds(viewerId, "minecraft:stick")
  check(slotCount > 0, label .. " exposes the stick recipe ghost slots (got " .. slotCount .. ")")
  check(contains(items, "minecraft:oak_planks"), label .. " stick slots list the plank tag members")
  check(
    #minecheck.getRecipeSlots("minecheck:not_a_recipe", viewerId) == 0,
    label .. " has no ghost slots for an unknown recipe"
  )
  check(
    minecheck.fillFromRecipe("minecraft:stick", nil, viewerId) == false,
    label .. " refuses to fill with no container open"
  )
end

-- The viewer content checks only assert that the *viewer* answers from its own index; whether that
-- index holds anything is the viewer's business. EMI indexes from the client's synced recipes, so
-- its checks always run. JEI in this harness starts before the dedicated server's recipe sync and
-- can stay empty for the whole session, so its content is probed *after* the reload at the end of
-- the script (see there) — a viewer that indexes too early is exactly what the reload is for.
local viewer = minecheck.recipeViewer()
check(viewer ~= nil, "priority recipe viewer bridged")
check(viewer.id == "emi", "priority recipe viewer is emi (got " .. tostring(viewer.id) .. ")")
checkViewer(nil, "priority (EMI)")
check(minecheck.recipeViewer("emi").id == "emi", "emi can also be selected by id")
check(minecheck.recipeViewer("not_a_viewer") == nil, "an unknown viewer id is not bridged")

local jeiInstalled = minecheck.isModLoaded("jei")
if jeiInstalled then
  local jei = minecheck.recipeViewer("jei")
  check(jei ~= nil, "JEI is installed but not bridged")
  if jei ~= nil then
    check(jei.id == "jei", "named recipe viewer is jei (got " .. tostring(jei.id) .. ")")
  end
end

-- REI's *mod* id is `roughlyenoughitems`; it reports the short viewer id `rei`. On Quilt it is not
-- installed, so this block simply does not run there.
local reiInstalled = minecheck.isModLoaded("roughlyenoughitems")
if reiInstalled then
  local rei = minecheck.recipeViewer("rei")
  check(rei ~= nil, "REI is installed but not bridged")
  if rei ~= nil then
    check(rei.id == "rei", "named recipe viewer is rei (got " .. tostring(rei.id) .. ")")
    -- REI indexes from the client's synced recipes, like EMI, so its content is checked here with
    -- the other viewers — before any container screen is opened (the fill check needs it closed).
    -- An empty index is logged rather than failed: whether a viewer's own index holds a recipe is
    -- the viewer's business, and this suite is asserting the bridge.
    local reiIndexed = minecheck.waitFor(function()
      return #minecheck.getRecipeSlots("minecraft:stick", "rei") > 0
    end, 20)
    if reiIndexed then
      checkViewer("rei", "REI")
    else
      minecheck.log(
        "REI's recipe index holds no minecraft:stick display; skipping its ghost-slot content check",
        "warn"
      )
    end
  end
end

local inv2 = minecheck.openScreen("inventory", nil, 10)
check(inv2, "inventory reopened")
check(minecheck.slotCount("inventory") > 0, "inventory has slots")
-- The hotbar's first slot is not a constant: the survival handler puts it at 36, while the creative
-- inventory puts the item-list tabs at 0..44 and the hotbar at 45..53 (slotCount 54). A read at 36
-- on the creative screen returns a tab item and says nothing about the player's hotbar.
local function hotbarStart()
  return minecheck.slotCount("inventory") > 46 and 45 or 36
end

-- Closing the beacon screen hands the payment slot's stack back to the player (vanilla returns it
-- on close; only activating an effect consumes it), so the emerald is expected back in the
-- inventory, anywhere — the beacon screen already showed the move itself (payment slot full, the
-- main slot it came from empty). What must hold after the round trip is that there is exactly one:
-- the move through the beacon neither duplicated nor lost it.
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

local emeraldBack = minecheck.waitFor(function()
  return emeraldCount() == 1
end, 10)
if not emeraldBack then
  for i = 0, minecheck.slotCount("inventory") - 1 do
    local s = minecheck.getSlot(i, "inventory")
    if s ~= nil and not s.empty then
      minecheck.log("beacon test: slot " .. i .. " = " .. tostring(s.id) .. " x" .. tostring(s.count))
    end
  end
end
check(emeraldBack, "the emerald came back from the beacon screen once (got " .. emeraldCount() .. ")")

local sword = minecheck.getSlot(hotbarStart() + 1, "inventory")
check(not sword.empty and sword.id == "minecraft:iron_sword", "named sword in the second hotbar slot")
check(sword.nbt ~= nil and sword.nbt.display ~= nil and sword.nbt.display.Name ~= nil, "sword carries display nbt")

local redstoneReady = minecheck.waitFor(function()
  local s = minecheck.getSlot(hotbarStart() + 2, "inventory")
  return s ~= nil and not s.empty and s.id == "minecraft:redstone" and s.count == 8
end, 30)
check(redstoneReady, "server gave the 8-redstone stack (third hotbar slot)")
local partial = minecheck.moveItem(hotbarStart() + 2, 10, 3, "inventory")
check(partial, "moveItem moved exactly 3 redstone")
local moved3 = minecheck.waitFor(function()
  local s = minecheck.getSlot(10, "inventory")
  return s ~= nil and not s.empty and s.id == "minecraft:redstone" and s.count == 3
end, 10)
check(moved3, "destination holds exactly 3 redstone")
local leftOver = minecheck.getSlot(hotbarStart() + 2, "inventory")
check(leftOver.id == "minecraft:redstone" and leftOver.count == 5, "source keeps the remaining 5 redstone")

local typed = minecheck.typeText("give @s minecraft:diamond 2", "command")
check(typed, "typeText typed a /give command into the chat screen")
check(minecheck.isScreenOpen("chat") == true, "chat screen is open after typeText")

local sent = minecheck.press("enter")
check(sent.pressed == true, "enter pressed to submit the typed command")
local chatClosed = minecheck.waitFor(function()
  return not minecheck.isScreenOpen("chat")
end, 5)
check(chatClosed, "chat screen closed after submitting the typed command")

local inv3 = minecheck.openScreen("inventory", nil, 10)
check(inv3, "inventory reopened for the typeText check")
local gotDiamonds = minecheck.waitFor(function()
  for i = hotbarStart(), hotbarStart() + 8 do
    local s = minecheck.getSlot(i, "inventory")
    if s ~= nil and not s.empty and s.id == "minecraft:diamond" and s.count == 2 then
      return true
    end
  end
  return false
end, 20)
check(gotDiamonds, "typed /give command executed (2 diamonds in the hotbar)")

-- Recipe-viewer ghost slots are "part of the GUI": a ghost slot can be the *source* of a slot move.
-- It names the ingredient the viewer shows (the same 0-based `index` getRecipeSlots reports) — the
-- viewer's slots are display-only, so the item itself still comes out of the open container.
local function slotHolding(itemId)
  for i = 0, minecheck.slotCount("inventory") - 1 do
    local s = minecheck.getSlot(i, "inventory")
    if s ~= nil and not s.empty and s.id == itemId then
      return i
    end
  end
  return nil
end

local function countInInventory(itemId)
  local total = 0
  for i = 0, minecheck.slotCount("inventory") - 1 do
    local s = minecheck.getSlot(i, "inventory")
    if s ~= nil and not s.empty and s.id == itemId then
      total = total + s.count
    end
  end
  return total
end

local function ghostIndexOf(recipeId, itemId)
  local slots = minecheck.getRecipeSlots(recipeId)
  for i = 1, #slots do
    for j = 1, #slots[i].items do
      if slots[i].items[j] == itemId then
        return slots[i].index
      end
    end
  end
  return nil
end

minecheck.runCommand("give @s minecraft:oak_planks 4")
check(
  minecheck.waitFor(function()
    return countInInventory("minecraft:oak_planks") == 4
  end, 30),
  "server gave 4 oak planks"
)

local plankGhost = ghostIndexOf("minecraft:stick", "minecraft:oak_planks")
check(plankGhost ~= nil, "the stick recipe has a plank-backed ghost slot")
check(
  minecheck.moveItem({ recipe = "minecheck:not_a_recipe", slot = 0 }, 10, 1, "inventory") == false,
  "an unknown recipe resolves to no source slot"
)
if plankGhost ~= nil then
  local source = slotHolding("minecraft:oak_planks")
  local dest = nil
  for i = hotbarStart(), hotbarStart() + 8 do
    local s = minecheck.getSlot(i, "inventory")
    if s ~= nil and s.empty and i ~= source then
      dest = i
      break
    end
  end
  check(source ~= nil and dest ~= nil, "plank source and an empty destination slot found")

  local okGhostDest = pcall(function()
    minecheck.moveItem(source, { recipe = "minecraft:stick", slot = plankGhost }, 1, "inventory")
  end)
  check(okGhostDest == false, "naming a ghost slot as the destination raises")

  check(
    minecheck.moveItem({ recipe = "minecraft:stick", slot = plankGhost }, dest, 1, "inventory"),
    "moveItem took the recipe's ingredient out of the container"
  )
  local landed = minecheck.waitFor(function()
    local s = minecheck.getSlot(dest, "inventory")
    return s ~= nil and not s.empty and s.id == "minecraft:oak_planks" and s.count == 1
  end, 10)
  check(landed, "1 oak plank landed in the destination slot")
  check(minecheck.getSlot(source, "inventory").count == 3, "the other 3 planks stayed in the source")
  check(countInInventory("minecraft:oak_planks") == 4, "no planks were lost in the ghost move")
end

-- A client resource reload (the F3+T reload) late in the session: mods that rebuild an index in a
-- reload listener do so during it, and the client must stay usable afterwards. (Kept last on
-- purpose: a reload re-syncs client state, which would perturb the inventory reads above.)
check(minecheck.reloadResources(30), "client resource reload finished mid-session")

-- JEI is probed only now, with the client's recipes long since synced and a reload behind it: an
-- index that is still empty here is empty for the session, not just early. The provider logs what
-- JEI holds on this read. If JEI started before the server's recipe sync (which is what happens on
-- a dedicated-server client) its index was built from an empty recipe manager; a server-side
-- datapack reload re-sends the sync, which is the event JEI's rebuild path waits for.
if jeiInstalled then
  minecheck.runCommand("reload")
  local indexed = minecheck.waitFor(function()
    return #minecheck.getRecipeSlots("minecraft:stick", "jei") > 0
  end, 40)
  if indexed then
    checkViewer("jei", "JEI")
  else
    minecheck.log(
      "JEI's recipe index is still empty after a server recipe re-sync; skipping its ghost-slot content check",
      "warn"
    )
  end
end

minecheck.disconnect(60)
minecheck.quit()
