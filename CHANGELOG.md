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
