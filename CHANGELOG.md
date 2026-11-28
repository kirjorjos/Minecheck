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

- `minecheck.check` and `minecheck.checkEq`: assertions that record a result and end the run, so a
  failure names itself instead of timing out.
- `minecheck.phase`: a phase budgets its ticks, not just its waits, and every `expect` is attributed
  to the phase it ran in.
- `minecheck.ownTickClock`: a script can own the tick clock without the screenshot profile.
- `minecheck.getWorldTime` / `setWorldTime` / `getWeather` / `setWeather`: read and pin the world's
  time and weather from a script (client side).
- `minecheck.worldExists`, `minecheck.deleteWorld`, `createWorld({ reuse = true })` and the
  `keepWorld` task property: a local world can survive between runs instead of being regenerated.
- `minecheck.startServer` / `serverStatus` / `waitForServer` / `stopServer`: a client script starts
  and stops the dedicated server itself, after the integrated phases have passed.

## Changed

- The launch profile pins player name, view distance, simulation distance, spawn protection,
  gamemode, difficulty and max tick time, defaulting to vanilla's values.
