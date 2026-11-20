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

## Added

- Legacy Fabric on 1.12.2: the loader now has the platform the line's Forge build has — mixins in
  place of the Fabric API, the full client and server Lua API, and every shared suite registered as
  `testLegacyfabric*` (lifecycle, fail path, client features, server features, world handoff and
  screenshots). Both loaders on this line pass the same unmodified scripts, and both release jars
  are proven to load in a real dedicated server.

## Changed

- A packet that arrives while the client is stopping is dropped instead of applied: the unpatched
  1.12 handlers dereference a world that teardown has already removed, which killed the process
  with a null pointer mid-suite. Forge's patched build guards this; the Legacy Fabric build now
  does too.
