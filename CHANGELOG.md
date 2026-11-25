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

- A Minecraft 1.19.3 line: the mod builds, launches and passes its lifecycle and screenshot suites on
  Fabric, Forge and Quilt.

## Changed

- Coordinates for the line: Fabric API 0.76.1, Forge 44.1.23, Quilt Loader 0.30.2 with QFAPI
  5.0.0-beta.9, EMI 1.1.22 and REI 10.0.646.
- Recipe transfer follows REI 10: a handler answers whether it applies instead of being probed for
  applicability first.
- The Forge client builds its world through the world loader's dimension function, and no longer
  warms the builtin registry copy that 1.19.3 stopped memoising at login.

## Fixed

- The Quilt module loads again: QFAPI publishes its access wideners in the named namespace, so
  unimined passes them through instead of refusing the jar while configuring.
