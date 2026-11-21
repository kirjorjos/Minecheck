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

- First release of Minecheck for Minecraft 1.8.8, shipping the Forge and Legacy Fabric builds.
- The Lua API this document describes in full, with the differences this line's game imposes listed
  under "1.8.8 (Forge, Legacy Fabric)" in `docs/lua-api.md`.
- The shared script suites run on this line unmodified on both loaders: the lifecycle, world, spawn,
  query, beacon, packet, input, client-feature, handoff and screenshot suites pass, and the merged
  server-features session runs all of its client phases against one server lifetime.

## Changed

- This line targets Minecraft 1.8.8 on Forge 11.15.0.1655 with MCP `stable_20`, and on Legacy Fabric
  with yarn `1.8.8+build.604`. Both loader modules follow those mappings: `getName` in place of
  `getCommandSenderName`, `GameRules.hasRule` / `getString`, the one-argument `ScaledResolution` and
  `Window`, a `ResourceLocation`-keyed item registry, `BlockHitResult.type`, the gamemode enum inside
  `LevelInfo`, and `PlayerManager.getPlayers()`.
- Commands whose syntax this line predates are rewritten before they are sent: `/fill` expands into
  one `/setblock` per block, `replaceitem` slots gain the `slot.` prefix this line requires, and
  1.13's `gamemode=` / `limit=` selector arguments are translated to `m=` / `c=`.
- `execute if block`, `execute if entity`, `data get entity` and `tag` are answered by this line's
  shim, and entity tags live in Minecheck's own store because the `/tag` root arrives in 1.13.
- `spawnStructure` is refused with its reason: 1.8 ships no structure templates.
- Item ids are strings while the item registry is keyed by `Identifier`: a script's id is wrapped for
  the registry, and a numeric raw id still resolves.
- Both builds are Java 8 bytecode. The mod compiles at 17 and is downgraded so that the dev runs and
  the shipped jars load on the Java 8 these loaders launch with.
