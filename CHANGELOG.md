# Changelog

<!--
Pending release notes. Write the entries for the *next* release below this comment.

The release workflow copies everything after the title and this comment into the GitHub Release
body, and aborts the release if there is nothing there. Once the release is published it resets
this file back to this header on the development branch — published notes live in the GitHub
Release, so this file never accumulates version sections.

Suggested shape, delete what you do not use:

## Added
-

## Changed
-
-->

## Added

- First release of Minecheck for Minecraft 1.7.10, shipping the Forge and Legacy Fabric builds.
- The shared script suites run on this line unmodified: the lifecycle, world, spawn, query, beacon,
  packet, tap, input, client-feature and handoff suites pass against 1.7.10, and the merged
  server-features session runs all ten of its client phases against one server lifetime.

## Changed

- Scripted coordinates, relative `/fill`, the crosshair ray and the camera pose use the feet
  position, since `Entity.posY` is the eye position on this line.
- Injected keys never reach the client's own key handling, so the inventory key opens its screen
  directly; button labels come from `displayString`, and a creative client gets the creative screen.
- `clickSlot` aligns the player's open container with the visible screen's window before clicking,
  and `moveItem` settles the container after each click so one the server rejects is retried
  instead of losing the stack.
- The `query` punch steps to a target beyond reach: this line has no `NoGravity`, so a mob spawned
  above geometry drops out of the interaction range.
