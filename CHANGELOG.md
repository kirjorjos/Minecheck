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

## Added

- First release of Minecheck for Minecraft 1.12.2, shipping the Forge build.
- The shared script suites run on this line unmodified: the 1.13-era commands, selector arguments and
  packet shapes they use are answered in the 1.12.2 platform (`LegacyCommandShim`, FML frame taps,
  per-part structure names, attribute base values).

## Changed

- The Forge 1.12.2 build targets Java 8, the JVM Forge 14.23.5 starts on.
