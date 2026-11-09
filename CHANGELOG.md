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
-->
## Added

- A bundled freecam camera that Lua drives: `minecheck.cameraToggle`, `minecheck.cameraPose` and
  `minecheck.cameraMove`, with `cameraMode` (`off`, `assist` or `fullControl`) choosing how much of
  the camera the script owns. Captures still photograph the player view, and `restoreVanillaCamera`
  leaves the render path untouched.

## Changed

- MineCam is declared as an optional dependency and referred to by its `minecam` mod id, so a client
  that has it loads it before Minecheck (#74, #77).

## Fixed

- Simple Voice Chat's listener is staged on the player during camera runs, so flying the camera no
  longer changes what the script hears (#82).
