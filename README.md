# Magic Storage

[![CI](https://github.com/swear01/Magic_Storage/actions/workflows/ci.yml/badge.svg)](https://github.com/swear01/Magic_Storage/actions/workflows/ci.yml)

`magic_storage` is a NeoForge 1.21.1 storage + crafting mod. It provides a server-authoritative storage network, storage/crafting terminals, crafting energy, import/export buses, and an in-game Patchouli guide.

Public repository: https://github.com/swear01/Magic_Storage

## Requirements

- JDK 21
- Gradle wrapper from this repository
- EMI `1.1.24` or any later compatible `1.x` build for Minecraft 1.21.1 is required on clients; dedicated servers do not require EMI

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

Expected automated coverage is currently SelfTest 222959 + GameTest 251 + 116 Python script/static regression tests.

Current main keeps the explicit Storage/Craftable/Fuel tabs and classifies the nine Fuel-page descriptors as five stackable process machines, three max-one instant stations, and one consumable Axe Energy input. The Core persists the eight removable machine/station stacks plus finite Axe Energy and an explicit infinite flag; accepted axes are consumed atomically, finite value is remaining durability × (Unbreaking level + 1), and Unbreakable supplies non-decrementing infinite energy. Upgrade migration keeps one legacy instant station and recovers extras into Core storage; a legacy axe that cannot convert remains visible and retrievable from the Axe Energy slot. Current main also uses one capability-based terminal profile/layout platform: Storage is the reduced profile, Crafting extends the shared shell, all rail controls use uniform 18×18 buttons with 16×16 semantic-item/atlas icons, cycle controls support left/right and wheel directions, and every network amount uses one screen-wide slot-bounded scale. Fuel Target keeps that cycle control and adds a descriptor-driven list popup with representative items, selected state, bounded scrolling, explicit close/focus behavior, EMI exclusion, and non-click-through input; station/reserve details trigger only on their actual slot/icon. All model-referenced block/item textures now form one native 16×16 dark-stone/cyan/amethyst family with explicit Core, terminal, tier-bar, inward Import, outward Export, and Remote motifs; the 16px control atlas uses matching fixed canvases. The server synchronizes an exact immutable recipe presentation—identity, positioned inputs, exact result components/count, station, and typed resource ledger with an explicit infinity bit—so the client diagram never scans Core state or `RecipeManager`. Required client-side EMI resolves that exact ID and renders only through its public recipe/widget APIs; unsupported internal axe recipes use the explicit native renderer, while the ledger and craft controls remain Magic Storage-owned. Development compiles against the minimum `1.1.24+1.21.1` baseline, released clients accept compatible EMI `1.x` versions through `[1.1.24,2)`, EMI is not bundled, and dedicated servers do not require it. Recipes remain server-gated by their installed station or Axe Energy; exact crafting/cooking/stonecutting, component-exact smithing transforms, and deterministic vanilla default-state axe actions are supported. Direct terminal crafting defaults to Player delivery but has an independent server-synchronized rail control for Player or Storage: Player fills the 36-slot inventory first and sends only overflow to Core, while Storage keeps primary results and crafting remainders in Core. A complete batch is rejected before mutation if its selected destination cannot accept it, and Max searches for the largest fully deliverable amount across `ItemStack` and `Integer.MAX_VALUE` boundaries, then commits each Core key through one long-count mutation instead of an unbounded chunk loop. EMI Cursor/Inventory requests remain authoritative and do not consult this direct-terminal toggle. EMI crafting remains exact, one-level, and server-authoritative rather than recursive autocrafting. The final fullscreen user-owned GUI checklist remains the visual release gate. Active contract: `docs/superpowers/plans/2026-07-14-terminal-platform-emi-recipe-axe.md`.

## CI/CD

GitHub Actions runs on pushes to `main`, pull requests, and manual dispatch:

- `.github/workflows/ci.yml` builds the mod, compiles against the minimum and newest compatible EMI API, runs `./gradlew runGameTestServer`, runs Python script tests, runs `./gradlew runData` as a datagen drift check, uploads logs/reports, and uploads `build/libs/magic_storage-*.jar` as an artifact. Client-side datagen stages the minimum full EMI through `scripts/stage_emi_runtime.sh`; a transient failure retries the same Gradle command once, then fails explicitly without changing source or version.
- `.github/workflows/client-smoke.yml` is manual-only (`workflow_dispatch`), stages the newest compatible full EMI jar through the same single-retry script, and launches a NeoForge client with HeadlessMC / MC-Runtime-Test to catch client boot/resource crashes; it is not GUI layout approval.
- `.github/workflows/release.yml` runs when a tag `v<mod_version>` is pushed, verifies the tag matches `gradle.properties`, repeats the build/tests/datagen drift check, generates release notes from git history, uploads logs/reports, then creates a GitHub Release with the jar.

Release example:

```bash
git tag v0.1.17
git push origin main v0.1.17
```

## Manual GUI verification

Automated tests and client smoke do not verify Minecraft GUI layout. For non-visual client boot/resource checks, run `python3 scripts/run_prism_gui_session.py --scenario boot-smoke`. For terminal/Patchouli/visual changes, run the relevant scenario (`crafting-fuel-page` covers the dedicated Fuel page); the runner transactionally rebuilds the deterministic true-void lab, clears the optional Computer Use wrapper, disables Prism's error-console pop-up for this instance, launches native Prism offline with `-o MagicStorageBot`, and waits for `MS_GUI_TEST_READY` before printing a manual handoff message. Visual verification owner: user. The user takes over, enters native fullscreen, and follows the generated checklist under `docs/notes.md` “Prism dev / manual handoff”. Offline mode skips Microsoft/Xbox account refresh. Vanilla authlib can still write a harmless offline profile-properties 401 to `latest.log`; the runner recognizes only that exact stack and still fails on every other current-run error.

## License

All Rights Reserved.
