# Magic Storage

[![CI](https://github.com/swear01/Magic_Storage/actions/workflows/ci.yml/badge.svg)](https://github.com/swear01/Magic_Storage/actions/workflows/ci.yml)

`magic_storage` is a NeoForge 1.21.1 storage + crafting mod. It provides a server-authoritative storage network, storage/crafting terminals, crafting energy, import/export buses, and an in-game Patchouli guide.

Public repository: https://github.com/swear01/Magic_Storage

## Requirements

- JDK 21
- Gradle wrapper from this repository

On the local Mac dev machine, set:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

## Build and test

```bash
./gradlew build
./gradlew runGameTestServer
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts
```

Expected automated coverage is currently SelfTest 40608 + GameTest 222 + 72 Python script/static regression tests.

Version 0.1.15 keeps the explicit Storage/Craftable/Fuel tabs and adds nine persistent station/tool slots: the five energy machines plus Crafting Table, Stonecutter, Smithing Table, and an axe. Recipes are server-gated by their installed station; exact crafting/cooking/stonecutting, component-exact smithing transforms, and deterministic vanilla default-state axe strip/scrape/wax-off actions are supported. Inventory delivery fills the player inventory first, routes overflow back to the Core, and rejects atomically if neither can hold the full result; Max searches for the largest fully deliverable amount and chunks Core reservations and outputs across both `ItemStack` and `Integer.MAX_VALUE` boundaries. Installed Stations now uses two adaptive rows, the Fuel control panel occupies the former lower-right gap, the recipe workspace follows a native EMI-like input/output/resource grammar without embedding EMI internals, and all model-referenced mod textures are 16×16. EMI remains optional and performs exact one-level server-authoritative crafting, not recursive autocrafting. The final fullscreen user-owned GUI checklist remains the visual release gate. Active contracts: `docs/superpowers/plans/2026-07-12-fuel-craftable-emi-adaptive-ui.md` and `docs/superpowers/plans/2026-07-13-stations-recipes-output-textures.md`.

## CI/CD

GitHub Actions runs on pushes to `main`, pull requests, and manual dispatch:

- `.github/workflows/ci.yml` builds the mod, runs `./gradlew runGameTestServer`, runs Python script tests, runs `./gradlew runData` as a datagen drift check, uploads logs/reports, and uploads `build/libs/magic_storage-*.jar` as an artifact.
- `.github/workflows/client-smoke.yml` is manual-only (`workflow_dispatch`) and launches a NeoForge client with HeadlessMC / MC-Runtime-Test to catch client boot/resource crashes; it is not GUI layout approval.
- `.github/workflows/release.yml` runs when a tag `v<mod_version>` is pushed, verifies the tag matches `gradle.properties`, repeats the build/tests/datagen drift check, generates release notes from git history, uploads logs/reports, then creates a GitHub Release with the jar.

Release example:

```bash
git tag v0.1.15
git push origin main v0.1.15
```

## Manual GUI verification

Automated tests and client smoke do not verify Minecraft GUI layout. For non-visual client boot/resource checks, run `python3 scripts/run_prism_gui_session.py --scenario boot-smoke`. For terminal/Patchouli/visual changes, run the relevant scenario (`crafting-fuel-page` covers the dedicated Fuel page); the runner transactionally rebuilds the deterministic true-void lab, clears the optional Computer Use wrapper, disables Prism's error-console pop-up for this instance, launches native Prism offline with `-o MagicStorageBot`, and waits for `MS_GUI_TEST_READY` before printing a manual handoff message. Visual verification owner: user. The user takes over, enters native fullscreen, and follows the generated checklist under `docs/notes.md` “Prism dev / manual handoff”. Offline mode skips Microsoft/Xbox account refresh. Vanilla authlib can still write a harmless offline profile-properties 401 to `latest.log`; the runner recognizes only that exact stack and still fails on every other current-run error.

## License

All Rights Reserved.
