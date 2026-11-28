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

- Minecraft 1.21.4 on all four loaders, from the same common code: `MineCheck-fabric-1.21.4-<version>`, `MineCheck-forge-1.21.4-<version>`, `MineCheck-neoforge-1.21.4-<version>` and `MineCheck-quilt-1.21.4-<version>`, each with its `-deobf` twin beside it. The line builds against Yarn `1.21.4+build.8`, Fabric Loader 0.20.1 with Fabric API 0.119.4+1.21.4, Forge 54.1.22, NeoForge 21.4.161 and Quilt Loader 0.30.2.
- The Lua API on this version — world entry and exit, server and client actions, assertions and result reporting, input, entities and blocks, screenshots, packets and the shims — the same surface the 1.21.1 line ships, documented in `docs/lua-api.md`.
- REI 18.0.815 on Fabric and NeoForge, and JEI 20.0.0.4 on NeoForge. 1.21.4 publishes no EMI build at all and JEI builds only for NeoForge, so Fabric carries REI alone, NeoForge carries both, and Forge and Quilt carry no recipe viewer.

## Changed

- Quilt runs on Fabric API with nothing nested: QSL stopped at 1.21.1, so the `init` entrypoint phase is gone and Quilt Loader's own `main` phase starts the mod. Installing Fabric API is the only thing a Quilt instance needs.
- The 1.21.2 rework of rendering, input, item models and registration is carried through the platform code, the mixins and the camera entity rather than assumed to fit, so the world, the screenshots and the recipe lookups behave as they do on 1.21.1.
- The packaged-jar check boots all four released jars in an installed dedicated server before anything is published, so a release only ships artifacts that have loaded and reported themselves active.
