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

- A release dispatch covering more than one Minecraft version no longer attaches the previous
  version's jars to the next tag. `collectArtifacts` empties `build/artifacts` before it copies, and
  the release refuses to publish if a staged jar is not this version's — the `1.19.2/v2.0.0` and
  `1.18.2/v1.1.2` releases each carried the other version's jars (#52).
