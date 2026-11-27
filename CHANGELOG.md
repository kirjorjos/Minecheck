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

- Minecraft 1.21.1 on all four loaders, from the same common code: `MineCheck-fabric-1.21.1-<version>`, `MineCheck-forge-1.21.1-<version>`, `MineCheck-neoforge-1.21.1-<version>` and `MineCheck-quilt-1.21.1-<version>`, each with its `-deobf` twin beside it. The line builds against Fabric Loader 0.16.14 with Fabric API 0.116.18, Forge 52.1.20, NeoForge 21.1.268 and Quilt Loader 0.30.2.
- The Lua API on this version — world entry and exit, server and client actions, assertions and result reporting, input, entities and blocks, screenshots, packets and the shims — the same surface the 1.20.4 line ships, documented in `docs/lua-api.md`.
- EMI, JEI and REI on the loaders that have them (EMI 1.1.24, JEI 19.8.2.99, REI 16.0.799); EMI wins where it is installed, as on the other lines.
- Quilt runs beside Fabric API instead of QFAPI, which has no 1.21.1 build: Quilt Loader loads Fabric API as it is, and the `init` entrypoint phase comes from QSL base, which the jar carries as a nested mod. Installing Fabric API is the only thing a Quilt instance needs.

## Changed

- The packaged-jar check boots all four released jars in an installed dedicated server before anything is published, so a release only ships artifacts that have loaded and reported themselves active.
