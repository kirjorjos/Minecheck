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
- `viewWindow` (default `false`): a view-only window showing the client's frames, for watching a run
  whose game window nobody can see. It installs no mouse listener and never takes focus on its own,
  and the keys typed into it are dispatched through Minecheck's virtual keyboard (`F2` captures,
  `F3` opens the debug screen). A headless JVM, or a machine with no display, logs why and runs
  without it.
- `restoreVanillaCamera` (default `false`): leaves the camera and render path untouched — no
  render-delta pin, no capture readback — for a setup where another mod core-mods the camera and
  conflicts with Minecheck's injections. Scripting keeps working; every `captureScreenshot` fails with
  `screenshots are unavailable: the vanilla camera is in effect (restoreVanillaCamera = true)`.
- `net.minecheck.platform.api.InjectedInput`: the marker raised around every synthetic key/mouse
  dispatch, so a mod that owns the human's input can tell Minecheck's virtual input from it.
