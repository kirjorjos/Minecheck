---
title: Minecheck
---

# Minecheck

A development-helper Minecraft mod that automates the Minecraft client and dedicated server for
testing and debugging mods: scripted world setup, entity and block spawning with NBT, GUI and
inventory automation, input simulation, and CI-friendly runs — all driven by Lua scripts, from a
single Java codebase across Forge, Fabric, and Quilt.

## Quick links

- [Lua API reference](lua-api.md) — every `minecheck.*` action, wait condition and reporter.
- [Source & releases](https://github.com/kirjorjos/Minecheck) — code, issues, and build artifacts.

## Where things live

| Resource | Location |
|---|---|
| Lua API | [lua-api.md](lua-api.md) |
| JARs | GitHub Releases for each `<minecraft version>/v<project version>` tag |
| Maven | `net.minecheck:minecheck-<loader>-<minecraft version>:<project version>` on GitHub Packages |

Each supported Minecraft version is its own branch line (`<version>/main` is the released, shippable
code; `<version>/development` is where work integrates). The Lua API is identical across released
versions — if it ever has to fork per version, this site moves to per-version paths.
