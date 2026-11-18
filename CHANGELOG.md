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

- Minecraft 1.14.4 on both Fabric and Forge, with the same script API as the other lines: the
  lifecycle, camera, view-window and screenshot suites run against it, and the handoff suite
  proves a dedicated server can run a client-generated save.

## Changed

- `muteAudio` keeps a scripted run silent on Forge too. Forge 28 has no hook that reaches the
  sound engine before it starts, so every play is refused and the device vanilla opens at startup
  is closed on the first client tick instead of the open being refused up front.
- `uncappedTicks` binds on 1.14.4's server loop. It hooks the loop's own next-tick deadline rather
  than a `Thread.sleep` this version does not have, so it stops warning that no pacing hook exists.
- `groundHeight` reports the first air above the surface, as the API documents, instead of the top
  blocking block the 1.14.4 port read.

