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

## Changed

- The 1.18.2 loader jars are built the way the 1.19.2 jars already were: the release ships the
  remapped `MineCheck-<loader>-1.18.2-<version>.jar` beside its `-deobf` twin, and `publish` sends
  that twin to GitHub Packages under the `deobf` classifier. `collectArtifacts` now copies exactly
  those two files per loader instead of every jar sitting in `build/libs`.

## Fixed

## Fixed

- A release dispatch covering more than one Minecraft version no longer attaches the previous
  version's jars to the next tag. `collectArtifacts` empties `build/artifacts` before it copies, and
  the release refuses to publish if a staged jar is not this version's — the `1.19.2/v2.0.0` and
  `1.18.2/v1.1.2` releases each carried the other version's jars (#52).
- The Forge jar ships a `pack.mcmeta`. Forge treats every mod file as a resource pack and put
  `failed to load a valid ResourcePackInfo` on its "Warning while loading mods" screen, which no
  headless run can dismiss, so no script ever started (#45).
- The 1.18.2 release jars carry their loader's production names. A jar built against development
  names loads in the Gradle dev runtime and dies in a production instance — the Forge jar with
  `NoSuchMethodError: 'Minecraft Minecraft.getInstance()'` (#23). `checkProductionJars` now fails
  the build if any loader's jar is not remapped.
