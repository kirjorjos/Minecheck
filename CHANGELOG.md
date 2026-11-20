# Changelog

<!--
Pending release notes. Write the entries for the *next* release below this comment.

The release workflow copies everything after the title and this comment into the GitHub Release
body, and aborts the release if there is nothing there. Once the release is published it resets
this file back to this header on the development branch — published notes live in the GitHub
Release, so this file never accumulates sections.

Suggested shape, delete what you do not use:

## Added
-

## Changed
-
-->

## Changed
- Publishing: the release workflow installs the Java 8 JDK it needs up front instead of downloading
  one mid-build, so a release can no longer fail after its promotion has merged
