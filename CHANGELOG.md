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

- First release of Minecheck for Minecraft 1.12, shipping the Forge build. Fabric starts at 1.14, so
  this line is Forge only.
- The shared script suites run on this line unmodified: the lifecycle, failure-path, client-feature
  (input, live driver, camera), server-feature (beacon, query, packet, spawn) and world-handoff
  suites pass against 1.12, and the merged server-features session runs its phases against one
  server lifetime.

## Changed

- The run reads its config with the gson the game already ships, so a packaged server no longer
  fails on a method that only exists in newer gson.
- The 1.12 dev dedicated server launches against a copy of the game jar without the `TweakClass`
  attribute the FG2 merge inherits, because FML 1.12 launches every tweak class it finds and
  refused the second one.
- Entering a world after leaving one waits for FML's loader state to settle, so a rejoin no longer
  starts while the previous integrated server is still stopping.
