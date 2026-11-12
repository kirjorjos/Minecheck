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

## Changed

- Release verification keeps captured frames in memory and hashes them in the JVM, so a lifecycle
  run no longer writes screenshot files to disk and the same bytes reach the comparison directly.
- The lifecycle suites run as phases of one session instead of one game per suite: beacon, query,
  packet and spawn share a client and a dedicated server, and input and live share a client. Every
  assertion is unchanged; the lifecycle jobs boot five games where they booted ten.

## Fixed

- A phase that waited for a cue the server had already sent no longer hangs until its timeout.
- The punch test puts the sword in the target's hand, where the command means it to be, not only
  in the inventory.
