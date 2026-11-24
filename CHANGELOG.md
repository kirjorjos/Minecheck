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
-->

## Added

- A Forge 1.4.7 line: the mod builds, launches and passes its lifecycle suites on FML 1.4.7, with the
  Lua API the 1.7.10 line exposes. Every class is emitted at class file 51, which is what this
  line's ASM 4.0 reads.
- `/setblock` and `/fill` answer on this line, and `give` accepts an inline NBT tag.

## Changed

- 1.4.7 names are translated both ways: ids are the reverse word order of later lines
  (`blockIron`, `swordIron`, `wood`), and the crafting table is `workbench`.
- `minecheck.keyPress("key.hotbar.N")` selects the slot and syncs it to the server on 1.4.7, which
  has no hotbar bindings to press.

## Fixed

- The Legacy Fabric entrypoint loads on 1.4.7: it no longer calls a static method on the loader's
  own interface, which the Java 7 downgrade cannot rewrite for a class outside the jar.
