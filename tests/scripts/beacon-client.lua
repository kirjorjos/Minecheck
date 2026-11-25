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
-- A phase runs inside a session the coordinator owns: it is already in the world, and leaving is
-- the coordinator's call (see `_G.MINECHECK_PHASE` in docs/lua-api.md).
local phase = _G.MINECHECK_PHASE ~= nil
if not phase then
  local joined = minecheck.joinServer("localhost:25565", 300)
  if not minecheck.expect(joined, "joined beacon-test server") then
    minecheck.fail("joinServer failed")
  end
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

-- The hand is read through a chest, not the inventory screen. A chest's menu is the same 27 chest
-- slots + 27 main + 9 hotbar in every gamemode, while the inventory screen is not: a client left in
-- creative opens the creative inventory, which holds no player slots at all, so the emerald the
-- `give` did deliver is never in the container that gets read. That read is what used to make this
-- handshake the flakiest thing in the suite — the server logged the give and the client saw nothing.
--
-- The client is op by now (the server ops before it cues this phase), so it still asks for survival
-- as well: later phases act on it (the tap phase needs a survival player). This line has no
-- client-side `gamemode` to wait on — a player's mode is not in the 1.4.7 API — so whether or not
-- that has landed yet no longer matters to the handshake below.
local me = minecheck.getPlayer()
if me ~= nil then
  minecheck.runCommand("gamemode survival " .. me.name)
end

-- Beside the player's column and below the pyramid lines built later, so it is in reach without
-- moving and clear of everything the rest of this script places.
local handChest = { x = math.floor(p0.pos.x) + 2, y = math.floor(p0.pos.y), z = math.floor(p0.pos.z) }
minecheck.runCommand("setblock " .. handChest.x .. " " .. handChest.y .. " " .. handChest.z .. " minecraft:chest")
check(
  minecheck.waitFor(function()
    local b = minecheck.getBlock(handChest)
    return b ~= nil and b.id == "minecraft:chest"
  end, 10),
  "client sees the handshake chest"
)

local handAim = { x = handChest.x + 0.5, y = handChest.y + 0.5, z = handChest.z + 0.5 }
local HAND_FIRST = 27

local inv = false
local ready = false
for attempt = 1, 10 do
  inv = minecheck.openScreen("chest", handAim, 20)
  local slots = minecheck.slotCount("chest")
  minecheck.log("beacon handshake: attempt " .. attempt .. " exposes " .. slots .. " slots")
  if inv and slots > HAND_FIRST then
    -- Any slot of the player's share of the menu, not a fixed hotbar index: where a `give` lands
    -- depends on what the player already carries. The claim under test is the op + give round trip,
    -- and it is proven by the emerald showing up in the client's own inventory at all.
    ready = minecheck.waitFor(function()
      for i = HAND_FIRST, minecheck.slotCount("chest") - 1 do
        local s = minecheck.getSlot(i, "chest")
        if s ~= nil and not s.empty and s.id == "minecraft:emerald" then
          return true
        end
      end
      return false
    end, 15)
    if ready then
      break
    end
  end
  if inv then
    minecheck.press("Escape")
    minecheck.waitFor(function()
      return not minecheck.isScreenOpen()
    end, 5)
  end
  -- The world script gives the emerald as soon as it cues this phase, and a give that arrives while
  -- the client is still settling into the world does not reach it: the menu above then shows an
  -- empty player inventory minutes later, which is what the retries are for. The client is op, so it
  -- asks again from inside the session — the claim under test is the op + give round trip, not which
  -- side issued the command.
  -- Only from the third attempt: the phase's own give normally lands on the first one, and an extra
  -- emerald is one more item in the hotbar for the container checks further down to sort the
  -- redstone and the sword around.
  if attempt >= 3 and me ~= nil then
    minecheck.runCommand("give " .. me.name .. " minecraft:emerald 1")
  end
  minecheck.sleep(2)
end
-- What the menu actually exposes, logged before the asserts: a failure here is then diagnosable from
-- the run log (which slots the screen has, and what is in them) instead of only naming the
-- expectation.
local dump = {}
local totalSlots = minecheck.slotCount("chest")
for i = 0, totalSlots - 1 do
  local s = minecheck.getSlot(i, "chest")
  if s ~= nil and not s.empty then
    dump[#dump + 1] = i .. ":" .. s.count .. "x " .. s.id
  end
end
minecheck.log("beacon handshake: chest menu exposes " .. totalSlots .. " slots [" .. table.concat(dump, ", ") .. "]")
check(inv, "handshake chest opened")
check(ready, "server op + give handshake complete (emerald in the player's inventory)")
minecheck.press("Escape")
local closed = minecheck.waitFor(function()
  return not minecheck.isScreenOpen()
end, 5)
check(closed, "handshake chest closed")

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

-- The target block is read along the look ray from the eye, and the beacon is directly under the
-- player's feet: a stance that drifted even a fraction sideways (a settling teleport, a server-side
-- nudge) puts the ray past the block instead of into it. Re-assert the stance and the look while
-- polling — standing centred on the beacon and looking straight down must hit it.
local beaconSeen = false
for _ = 1, 6 do
  minecheck.runCommand("tp @s " .. (bx + 0.5) .. " " .. (by + 1) .. " " .. (bz + 0.5))
  minecheck.look(90, 0)
  beaconSeen = minecheck.waitFor(function()
    local pl = minecheck.getPlayer()
    local t = pl ~= nil and minecheck.getTargetBlock(pl) or nil
    return t ~= nil and t.x == bx and t.y == by and t.z == bz
  end, 5)
  if beaconSeen then
    break
  end
end
check(beaconSeen, "beacon visible in the client world")

local beaconBlock = minecheck.getBlock({ x = bx, y = by, z = bz })
check(beaconBlock ~= nil and beaconBlock.id == "minecraft:beacon", "getBlock reads the beacon block")
check(beaconBlock ~= nil and beaconBlock.nbt ~= nil, "getBlock returns the beacon's block entity nbt")
local ironBlock = minecheck.getBlock({ x = bx, y = by - 1, z = bz })
check(ironBlock ~= nil and ironBlock.id == "minecraft:iron_block", "getBlock reads the pyramid block below")
check(ironBlock ~= nil and ironBlock.nbt == nil, "plain blocks have no block entity nbt")

-- The screen mechanics are asserted on a sign, not on the beacon's menu. The sign editor is the same
-- screen on every version — one text field and a `Done` button carrying that label — while the beacon
-- menu is not: its slot count, its payment slot and its cancel button's class name all differ across
-- versions, and a line that predates the beacon cannot run them at all. The sign stands on top of the
-- beacon, so it is also the block over it.
local sy = by + 1
minecheck.runCommand("setblock " .. bx .. " " .. sy .. " " .. bz .. " minecraft:oak_sign")
local signPlaced = minecheck.waitFor(function()
  local b = minecheck.getBlock({ x = bx, y = sy, z = bz })
  return b ~= nil and b.id == "minecraft:oak_sign"
end, 10)
check(signPlaced, "sign placed over the beacon")

-- Step off the beacon onto the pyramid's top layer: the sign now holds the space the player stood
-- in, and its editor is opened by right-clicking it from outside.
minecheck.runCommand("tp @s " .. (bx + 1.5) .. " " .. by .. " " .. (bz + 0.5))
local beside = minecheck.waitFor(function()
  local pl = minecheck.getPlayer()
  return pl ~= nil and math.floor(pl.pos.y) == by
end, 10)
check(beside, "client stepped off the beacon beside the sign")

-- The editor is opened through the harness: `openScreen("sign", pos)` asks the client for vanilla's
-- own sign editor instead of synthesizing a right-click, which is what keeps this block running on
-- every line — 1.16.5's block-use path never reaches the editor, so a click there opens nothing.
local opened = minecheck.openScreen("sign", { x = bx, y = sy, z = bz }, 10)
check(opened, "sign editor opened")
check(minecheck.isScreenOpen("sign") == true, "isScreenOpen(sign) is true")
check(minecheck.clickGui("NoSuchButton") == false, "an unknown element id is not found")

local typed = minecheck.typeText("minecheck")
check(typed, "typeText typed into the sign editor")
check(minecheck.isScreenOpen("sign") == true, "sign editor still open after typing")

-- Addressed by label, like every other element id: the widget class behind it is mapping-dependent.
local saved = minecheck.clickGui("Done")
check(saved, "clickGui found the sign editor's Done button")
local closed = minecheck.waitFor(function()
  return not minecheck.isScreenOpen("sign")
end, 10)
check(closed, "sign editor closed after Done")

-- The text proves the round trip reached the block, not just the field: the editor saves through the
-- server, which writes the line into the block entity the client reads back.
local wroteText = minecheck.waitFor(function()
  local s = minecheck.getBlock({ x = bx, y = sy, z = bz })
  return s ~= nil
    and s.nbt ~= nil
    and type(s.nbt.Text1) == "string"
    and string.find(s.nbt.Text1, "minecheck", 1, true) ~= nil
end, 10)
check(wroteText, "the sign saved the typed text")

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
-- Which viewer wins the priority order is a property of what is installed, not of the harness:
-- EMI ships no Forge build for 1.18.2, so the priority there is JEI. The assertions name whichever
-- one this loader actually has.
-- 1.16.5 has no bridged recipe viewer at all (EMI and REI publish nothing for it, and JEI's 7.x
-- line predates the API these bridges are written against), so the viewer assertions are conditional
-- on one actually being installed rather than assuming the priority order has a member.
local viewer = minecheck.recipeViewer()
local viewerInstalled = viewer ~= nil
if not viewerInstalled then
  minecheck.log("no recipe viewer is bridged on this line; skipping the viewer checks", "warn")
end
if viewerInstalled then
  local emiInstalled = minecheck.isModLoaded("emi")
  local priorityId = emiInstalled and "emi" or "jei"
  check(viewer ~= nil, "priority recipe viewer bridged")
  check(viewer.id == priorityId, "priority recipe viewer is " .. priorityId .. " (got " .. tostring(viewer.id) .. ")")
  check(minecheck.recipeViewer("not_a_viewer") == nil, "an unknown viewer id is not bridged")

  if emiInstalled then
    checkViewer(nil, "priority (EMI)")
    check(minecheck.recipeViewer("emi").id == "emi", "emi can also be selected by id")
  end

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
end

-- The container assertions are read through a chest, not the player inventory screen. A chest's
-- menu is the same menu on every version and in every gamemode — 27 chest slots, then the player's
-- main inventory (27..53) and hotbar (54..62) — while the inventory screen is not: the survival
-- handler puts the hotbar at 36..44, the creative screen puts its item tabs at 0..44 and the hotbar
-- at 45..53, so a read at a fixed offset means different things per line and per gamemode. A chest
-- is also a real block, which keeps the whole container path (open, move, read) under test.
local CHEST_SLOTS = 27
local PLAYER_SLOTS = 27
local HOTBAR_FIRST = CHEST_SLOTS + PLAYER_SLOTS

-- Placed beside the player's column so it is in reach without moving, and clear of the beacon, the
-- sign and the pyramid blocks read above.
local chestPos = { x = bx + 2, y = by, z = bz }
local chestAim = { x = bx + 2.5, y = by + 0.5, z = bz + 0.5 }
minecheck.runCommand("setblock " .. chestPos.x .. " " .. chestPos.y .. " " .. chestPos.z .. " minecraft:chest")
check(
  minecheck.waitFor(function()
    local b = minecheck.getBlock(chestPos)
    return b ~= nil and b.id == "minecraft:chest"
  end, 10),
  "client sees the chest placed beside the pyramid"
)

local chest = minecheck.openScreen("chest", chestAim, 10)
check(chest, "chest opened")
check(minecheck.slotCount("chest") == HOTBAR_FIRST + 9, "chest menu is 27 chest slots plus the player's 36")

-- Found by content, like the redstone below: a merged session reaches this phase with the hotbar
-- the session set up for it, so a give lands wherever the first free slot happens to be.
local sword = nil
local swordReady = minecheck.waitFor(function()
  for i = HOTBAR_FIRST, HOTBAR_FIRST + 8 do
    local s = minecheck.getSlot(i, "chest")
    if s ~= nil and not s.empty and s.id == "minecraft:iron_sword" and s.nbt ~= nil then
      sword = s
      return true
    end
  end
  return false
end, 10)
check(swordReady, "named sword in the hotbar")
check(sword ~= nil and sword.nbt.display ~= nil and sword.nbt.display.Name ~= nil, "sword carries display nbt")

-- Found by content, not by index: a merged session reaches this phase with the hotbar the session
-- set up for it, so the gives land wherever the first free slot happens to be.
local redstoneSlot = nil
local redstoneReady = minecheck.waitFor(function()
  for i = HOTBAR_FIRST, HOTBAR_FIRST + 8 do
    local s = minecheck.getSlot(i, "chest")
    if s ~= nil and not s.empty and s.id == "minecraft:redstone" and s.count == 8 then
      redstoneSlot = i
      return true
    end
  end
  return false
  -- 90 s, not 30: the give is one server tick's work, but the chest's container sync and this
  -- client's own phase can both trail a loaded runner by a long way, and a give window that is
  -- merely tight reads as a missing item and fails the run.
end, 90)
check(redstoneReady, "server gave the 8-redstone stack (in the hotbar)")
local partial = minecheck.moveItem(redstoneSlot, 0, 3, "chest")
if not partial then
  -- A click the server cannot match to its own container is dropped without a resync, so every
  -- later click is dropped too and nothing moves or errors. Re-opening the chest gives both sides
  -- the same window again, which is what the retry needs.
  minecheck.press("Escape")
  minecheck.waitFor(function()
    return not minecheck.isScreenOpen()
  end, 5)
  check(minecheck.openScreen("chest", chestAim, 10), "chest reopened after a dropped click")
  partial = minecheck.moveItem(redstoneSlot, 0, 3, "chest")
end
check(partial, "moveItem moved exactly 3 redstone")
local moved3 = minecheck.waitFor(function()
  local s = minecheck.getSlot(0, "chest")
  return s ~= nil and not s.empty and s.id == "minecraft:redstone" and s.count == 3
end, 10)
check(moved3, "the chest's first slot holds exactly 3 redstone")
local leftOver = minecheck.getSlot(redstoneSlot, "chest")
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

local inv3 = minecheck.openScreen("chest", chestAim, 10)
check(inv3, "chest reopened for the typeText check")
-- Anywhere in the container: the hand's slot is the client's business, and the container also holds
-- the player's main inventory, which the creative screen does not show at all.
local gotDiamonds = minecheck.waitFor(function()
  for i = 0, minecheck.slotCount("chest") - 1 do
    local s = minecheck.getSlot(i, "chest")
    if s ~= nil and not s.empty and s.id == "minecraft:diamond" and s.count == 2 then
      return true
    end
  end
  return false
end, 20)
check(gotDiamonds, "typed /give command executed (2 diamonds in the container)")

-- Recipe-viewer ghost slots are "part of the GUI": a ghost slot can be the *source* of a slot move.
-- It names the ingredient the viewer shows (the same 0-based `index` getRecipeSlots reports) — the
-- viewer's slots are display-only, so the item itself still comes out of the open container.
local function slotHolding(itemId)
  for i = 0, minecheck.slotCount("chest") - 1 do
    local s = minecheck.getSlot(i, "chest")
    if s ~= nil and not s.empty and s.id == itemId then
      return i
    end
  end
  return nil
end

local function countInContainer(itemId)
  local total = 0
  for i = 0, minecheck.slotCount("chest") - 1 do
    local s = minecheck.getSlot(i, "chest")
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
    return countInContainer("minecraft:oak_planks") == 4
  end, 90),
  "server gave 4 oak planks"
)

-- The moves below read the priority viewer's ghost slots, so its index has to be built first. EMI
-- and REI index from the client's synced recipes and are ready by now; JEI builds its index on a
-- background thread and is still filling it when the script gets here (see the note at the top of
-- the viewer section) — on Forge a bare `getRecipeSlots` reports no ghost slots until it is done.
-- Wait for the index and ask the server for a recipe re-sync only if it never arrives: a viewer
-- that indexes from the client's synced recipes is ready here, JEI may still be building its own.
if viewerInstalled then
  local indexed = minecheck.waitFor(function()
    return #minecheck.getRecipeSlots("minecraft:stick") > 0
  end, 30)
  if not indexed then
    minecheck.runCommand("reload")
    minecheck.waitFor(function()
      return #minecheck.getRecipeSlots("minecraft:stick") > 0
    end, 30)
  end

  local plankGhost = ghostIndexOf("minecraft:stick", "minecraft:oak_planks")
  check(plankGhost ~= nil, "the stick recipe has a plank-backed ghost slot")
  check(
    minecheck.moveItem({ recipe = "minecheck:not_a_recipe", slot = 0 }, 0, 1, "chest") == false,
    "an unknown recipe resolves to no source slot"
  )
  if plankGhost ~= nil then
    local source = slotHolding("minecraft:oak_planks")
    local dest = nil
    for i = HOTBAR_FIRST, HOTBAR_FIRST + 8 do
      local s = minecheck.getSlot(i, "chest")
      if s ~= nil and s.empty and i ~= source then
        dest = i
        break
      end
    end
    check(source ~= nil and dest ~= nil, "plank source and an empty destination slot found")

    local okGhostDest = pcall(function()
      minecheck.moveItem(source, { recipe = "minecraft:stick", slot = plankGhost }, 1, "chest")
    end)
    check(okGhostDest == false, "naming a ghost slot as the destination raises")

    check(
      minecheck.moveItem({ recipe = "minecraft:stick", slot = plankGhost }, dest, 1, "chest"),
      "moveItem took the recipe's ingredient out of the container"
    )
    local landed = minecheck.waitFor(function()
      local s = minecheck.getSlot(dest, "chest")
      return s ~= nil and not s.empty and s.id == "minecraft:oak_planks" and s.count == 1
    end, 10)
    check(landed, "1 oak plank landed in the destination slot")
    check(minecheck.getSlot(source, "chest").count == 3, "the other 3 planks stayed in the source")
    check(countInContainer("minecraft:oak_planks") == 4, "no planks were lost in the ghost move")
  end
end

-- A client resource reload (the F3+T reload) late in the session: mods that rebuild an index in a
-- reload listener do so during it, and the client must stay usable afterwards. (Kept last on
-- purpose: a reload re-syncs client state, which would perturb the inventory reads above.)
check(minecheck.reloadResources(30), "client resource reload finished mid-session")

-- `checkViewer` asserts that a fill is refused when no container is open, and the ghost-slot moves
-- above left the chest open: close it before probing JEI.
if minecheck.isScreenOpen() then
  minecheck.press("Escape")
  minecheck.waitFor(function()
    return not minecheck.isScreenOpen()
  end, 5)
end

-- JEI is probed only now, with the client's recipes long since synced and a reload behind it: an
-- index that is still empty here is empty for the session, not just early. The provider logs what
-- JEI holds on this read. If JEI started before the server's recipe sync (which is what happens on
-- a dedicated-server client) its index was built from an empty recipe manager; a server-side
-- datapack reload re-sends the sync, which is the event JEI's rebuild path waits for.
if jeiInstalled then
  local indexed = minecheck.waitFor(function()
    return #minecheck.getRecipeSlots("minecraft:stick", "jei") > 0
  end, 20)
  if not indexed then
    minecheck.runCommand("reload")
    indexed = minecheck.waitFor(function()
      return #minecheck.getRecipeSlots("minecraft:stick", "jei") > 0
    end, 30)
  end
  if indexed then
    checkViewer("jei", "JEI")
  else
    minecheck.log(
      "JEI's recipe index is still empty after a server recipe re-sync; skipping its ghost-slot content check",
      "warn"
    )
  end
end

if not phase then
  minecheck.disconnect(60)
  minecheck.quit()
end
