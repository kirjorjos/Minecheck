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

## Fixed
-
-->

## Added
- `minecheck.getCrosshairBlock()`: the block the renderer's crosshair is on, which is the target a click actually acts on. `getTargetBlock` raycasts on demand and so cannot tell you what a press will hit until a frame has redrawn the crosshair.

## Changed
- `test<Loader>Shots` compares captures byte for byte: the client pins the render tick delta, so a frozen scene is reproducible instead of drifting a level between runs, and one changed pixel is a failure. A stale committed picture is a reported finding rather than a red build, and CI regenerates it after merge.
- Suites wait on the condition itself instead of sleeping for a fixed number of seconds. A click waits for the renderer's own crosshair, and client departures and world loads are bounded by `config.defaultTimeout` rather than per-script guesses.
