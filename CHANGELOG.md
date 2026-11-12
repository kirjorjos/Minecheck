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
- `bootPackagedJars` installs each packaged loader jar in a real dedicated server and fails unless
  Minecheck reports itself active there, so a jar that breaks after remapping is caught before the
  release that would have shipped it (#103). `-Pboot.only=fabric,forge` boots a subset,
  `-Pboot.java=<path>` picks the JDK the servers run on, and `-Pboot.timeout=<seconds>` widens the
  start deadline.
- `pauseTicks`, `stepTicks` and `resumeTicks` let a script own the tick loop of the server it runs:
  the world can be held between captures and advanced by an exact tick count, and stepped ticks run
  back to back instead of at twenty a second. `uncappedTicks` drops vanilla's cadence for the whole
  run, so a machine with headroom runs well past twenty ticks a second. All of it is behind
  `ownTickClock`, off by default.

## Changed
- A pull request into this line's `development` branch boots the packaged jars instead of only
  running the development classpath, and that check is a Gradle task in the plugin rather than a
  Python script shipped beside the build.
- The screenshot suite settles on a held clock and a fixed tick count instead of on the frame
  digest. A capture is a function of the ticks the script ran rather than of how fast the runner drew
  frames, and the ad-hoc `_settle-*` probes are gone from the mismatch report.
