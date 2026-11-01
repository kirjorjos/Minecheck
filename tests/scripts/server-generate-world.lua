-- Dedicated-server script (shared world template):
--  The harness boots one server with this script so the pinned world is generated exactly once, then
--  copies the level out and reuses it for every suite that only needs a world to stand in -- instead
--  of each of them paying the full spawn-region prep again.
--
--  Nothing here asserts on the world's contents: the suites that reuse it do their own asserting.
--  The verdict still matters, because a world that never came up must fail here rather than leave a
--  half-generated template for every later suite to inherit.
local settled = minecheck.waitFor(minecheck.worldGenSettled, 180)
minecheck.expect(settled, "the generated world settled before the template was taken")
if not settled then
  minecheck.fail("worldgen did not settle; refusing to save a half-generated world as the template")
end

minecheck.runCommand("stop")
