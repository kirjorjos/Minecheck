# Changelog

<!--
Pending release notes. Write the entries for the *next* release below this comment.

The release workflow copies everything after the title and this comment into the GitHub Release
body, and aborts the release if there is nothing there. After releasing, clear this file back to
this header — published notes live in the GitHub Release, not here, so this file never accumulates
version sections.

Suggested shape, delete what you do not use:

## Added
-

## Changed
-

## Fixed
-
-->

## Added
- First public release: scripted client and dedicated-server automation for Fabric, Forge and Quilt on Minecraft 1.19.2.
- Lua 5.2 scripting API behind the global `minecheck` table, documented in `docs/lua-api.md`.
- Harness mode, where a server script boots a dedicated server while a client script joins it on localhost.
- Server actions: spawn entities, blocks, item drops and structures with NBT, run console commands, wait for a player to join.
- Client actions: world setup, GUI and inventory automation, keypress simulation, wait conditions.
- Plugin API so other mods can register their own script actions and wait conditions.

