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

- A Minecraft 1.20.1 line: the mod builds, launches and passes its lifecycle and screenshot suites on
  Fabric, Forge and Quilt.

## Changed

- Coordinates for the line: Fabric API 0.92.2 on Fabric Loader 0.16.14, Forge 47.4.28, Quilt Loader
  0.30.2 with QFAPI 7.7.0, EMI 1.1.24, REI 12.1.785, JEI 15.20.0.106, Cloth Config 11.1.136 and
  Architectury 9.2.14.
- The crafting-open screenshot baseline moves on every loader, because 1.20 relocates the recipe book
  button on the crafting screen.
- A lifecycle client never pauses when its window loses focus, so injected input keeps reaching the
  game on a runner whose window is never focused.

## Fixed

- The beacon suite reads a sign's line from 1.20's front-side text, so the typed text is checked
  again.
