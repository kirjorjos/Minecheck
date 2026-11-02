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

## Fixed

- The Forge jar ships a `pack.mcmeta`. Forge treats every mod file as a resource pack and put
  `failed to load a valid ResourcePackInfo` on its "Warning while loading mods" screen, which no
  headless run can dismiss, so no script ever started (#45).
