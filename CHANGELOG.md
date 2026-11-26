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

- NeoForge support for 1.20.4: `MineCheck-neoforge-1.20.4-<version>` and its `-deobf` twin, from the same common code as Fabric, Quilt and Forge. The loader's transport is NeoForge's payload registry rather than a `SimpleChannel`, and `watchPacketChannel` observes another mod's channel through the same low-level frame tap Forge uses.
- EMI, JEI and REI are all supported on NeoForge; EMI wins where it is installed, as it does on Fabric.

## Changed

- The packaged-jar check boots the NeoForge server as well, so every artifact in a release is proven to load.
