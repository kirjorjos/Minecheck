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
-
-->

## Added
- `minecheck.captureScreenshot(name[, options][, timeout])`: PNG captures of the client frame (full frame or a framebuffer/GUI-unit region), written with a deterministic capture profile and verified against committed baselines by the new `test<Loader>Shots` lifecycle suites.
- A live Lua session: `minecheck.listen([port][, host])` serves a program on the loopback interface and `minecheck.liveAttach([port][, timeout])` attaches to one, over a TCP socket that carries one line per request and the caller's report back on the same connection. A suite can now pause for input from outside the game instead of being edited and relaunched; `test<Loader>Live` drives it in CI.

## Changed
- `test<Loader>Shots` compares captures against the committed baselines in CI and against a git-ignored per-machine tree locally, with a tolerance sized to the drift CI actually shows.

## Fixed
- Screenshot capture waits for the first frame drawn after the chat is cleared, so HUD text from the run's own commands no longer leaks into the image.
- The input suite holds the right click until the placed block exists, instead of assuming a single press landed on the crosshair target.
