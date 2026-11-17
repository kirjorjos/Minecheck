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

- A screenshot baseline whose scene no longer exists is now removed by the post-merge sync. The
  validation run reports the picture as a deletion, and the sync commits the removal instead of
  leaving the orphan in `tests/baselines`.
