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

- A Minecraft 1.20.4 line: the mod builds, launches and passes its lifecycle and screenshot suites on
  Fabric, Forge and Quilt.

## Changed

- Coordinates for the line: Fabric API 0.97.3 on Fabric Loader 0.16.14, Forge 49.2.13, Quilt Loader
  0.30.2 with QFAPI 9.0.0-alpha.8, EMI 1.1.22, REI 14.1.786, JEI 17.3.1.5, Cloth Config 13.0.138 and
  Architectury 11.1.17.
- The Forge build no longer hooks EMI's recipe viewer, because EMI has no Forge artifact past 1.20.2.
- Every screenshot baseline is re-recorded for the line, since 1.20.4 renders the world and the
  crafting screen differently from 1.20.1.

## Fixed

- A Forge 1.20.4 client can rejoin a local world straight after leaving it: the previous session's
  world lock is released before the join instead of racing it.
