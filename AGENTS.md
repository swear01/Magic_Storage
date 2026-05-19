<!-- FOR AI AGENTS - Human readability is a side effect, not a goal -->
<!-- Last updated: 2026-05-19 -->

# AGENTS.md — magic_storage

Scoped rules for the `magic_storage` Minecraft mod. These override the root AGENTS.md when working inside this directory.

## Project Overview

| Key | Value |
|-----|-------|
| Mod name | magic_storage |
| Type | Minecraft storage mod (NeoForge) |
| Language | Java |
| Build tool | Gradle |

## Commands

| Task | Command |
|------|---------|
| Build | `./gradlew build` |
| Test | `./gradlew test` |
| Run client | `./gradlew runClient` |

## Reference Source — Refined Storage 2

**When unsure how to implement storage mechanics, network logic, grid UI, or item/fluid handling — refer to the Refined Storage 2 source code first.**

| Topic | Where to look |
|-------|--------------|
| Storage network architecture | `refinedstorage2-platform-common/src/main/java/.../network/` |
| Grid (item browser UI) | `refinedstorage2-platform-common/src/main/java/.../grid/` |
| Storage provider / disk API | `refinedstorage2-api/src/main/java/.../storage/` |
| Item/fluid resource handling | `refinedstorage2-api/src/main/java/.../resource/` |
| Autocrafting | `refinedstorage2-platform-common/src/main/java/.../autocrafting/` |
| NeoForge platform bridge | `refinedstorage2-platform-neoforge/` |

**Source:** https://github.com/refinedmods/refinedstorage2

Workflow when stuck:
1. Search the RS2 repo for the interface or class name you need.
2. Read the implementation to understand the pattern.
3. Adapt (do not copy wholesale — license differs).
4. If still unsure, ask the user.

## Heuristics

| When | Do |
|------|----|
| Unsure how RS2 does X | Browse https://github.com/refinedmods/refinedstorage2 first |
| Adding a new network component | Model after RS2's `NetworkNode` pattern |
| Need an item/fluid abstraction | Check RS2's `ResourceKey` / `ResourceAmount` API |
| Adding dependency | Ask user first — minimize deps |

## Boundaries

### Always Do
- Follow NeoForge conventions for capability registration.
- Keep network logic server-side; send packets for client sync.
- Reference RS2 source for design guidance when implementing storage features.

### Never Do
- Copy RS2 source verbatim (different license).
- Implement client-side storage state — always sync from server.
