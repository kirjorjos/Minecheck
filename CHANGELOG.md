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

## Changed
- A pull request into this line's `development` branch now boots the packaged jars instead of only
  running the development classpath, and that check is a Gradle task in the plugin rather than a
  Python script shipped beside the build.
