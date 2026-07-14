# Shared Terminal Platform, EMI-First Recipe UI, and Axe Energy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `using-superpowers` and execute this plan task-by-task with strict `test-driven-development`. Do not edit production code until the task's focused test has failed for the expected reason.

**Goal:** Make Storage Terminal and Crafting Terminal share one adaptive UI platform, correct server-owned grid quantities and sorting, render exact recipes through EMI's public widgets with an explicit native fallback, add atomic output routing, and replace installed axes with finite-or-infinite Axe Energy.

**Architecture:** Preserve separate menu registrations but introduce shared profile-driven terminal geometry, controls, display metadata, and comparators. The server remains authoritative for inventory, recipe identity, recipe presentation, destination, station state, and tool energy. Client-only EMI code is isolated behind a Magic Storage renderer interface and a guarded compatibility bootstrap.

**Tech Stack:** Java 21, Minecraft 1.21.1, NeoForge 21.1, Gradle ModDev, EMI 1.1.24 public API, NeoForge GameTest, project SelfTest, Python static regression tests, Replicate texture workflow.

---

## Execution rules

- Keep each numbered task as one independently reviewable RED → GREEN change.
- Run the smallest focused command after adding tests and record the expected RED before touching production code.
- Never weaken or delete a regression merely to make a task green.
- Menu data-slot and container-slot counts must remain identical in server and client constructors after every task.
- Update the active docs named by each task in the same behavior commit.
- Do not load client or EMI classes from dedicated-server paths.
- Commit only after the task's focused tests are green. Do not push until the user asks.

## Task 1: Server-owned display amounts and shared ordering

> Status: complete. RED exposed the missing Mod mode, unstable component ties, old stack-count transport, old Craftable yield quantities, duplicated comparator, zero-count overlay, and EMI marker leakage; GREEN is SelfTest 40622/40622, GameTest 223/223, and Python 78/78.

**Files:**

- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/SortMode.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java`
- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/TerminalDisplayStack.java`
- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/TerminalEntryComparator.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/SelfTest.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/gametest/TerminalFlowTests.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/gametest/CraftingTests.java`
- Modify: `docs/overview.md`
- Modify: `docs/notes.md`

**RED:** Add tests proving Name, Quantity, Mod, and full-ID ordering share deterministic tie-breakers; synthetic display metadata round-trips `0`, `64`, values above `Integer.MAX_VALUE`, and `Long.MAX_VALUE`; stripping only the Magic Storage marker preserves the stack's original components; `ItemKey` never contains the marker; synthetic zero-count entries remain visible and non-extractable; and Craftable overlays report the exact Core amount rather than potential craft yield. Run `./gradlew runGameTestServer` and confirm failures are caused by missing Mod sorting/display metadata and old Craftable count semantics.

**GREEN:** Add the one display-stack helper and one comparator source, route Storage and Craftable sorting through it, and make Craftable entries carry exact stored amounts while remaining server-owned synthetic stacks. Strip metadata before all identity, insertion, output, and recipe comparisons. Run `./gradlew runGameTestServer`.

**Commit:** `feat: unify terminal display amounts and sorting`

## Task 2: Shared terminal profile, adaptive shell, and rail controls

> Status: complete. RED produced 41 missing-profile/direction/previous/scale compile errors and a focused static failure for the remaining vanilla Fuel `CycleButton`; GREEN is compileJava, SelfTest 40999/40999, GameTest 223/223, focused Python 37/37, and full Python 84/84. Storage and Crafting now use one profile-driven layout/shared shell, one 18px/16px item-or-atlas control system, one click/wheel cycle contract, and one screen-wide amount scale.

**Files:**

- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java`
- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/TerminalProfile.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/StorageTerminalScreen.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java`
- Modify: `src/main/resources/assets/magic_storage/lang/en_us.json`
- Modify: `scripts/test_static_regressions.py`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/SelfTest.java`
- Modify: `docs/overview.md`
- Modify: `docs/structure.md`
- Modify: `docs/notes.md`

**RED:** Add geometry and static tests proving both screens call one profile-driven layout entry point, Storage is the reduced profile, no base widgets are created and hidden, every rail control uses an 18-pixel hit box and 16-pixel icon canvas, page and view groups have a gap, click/wheel direction is consistent, tooltips expose only the current value and interaction hint, and all rendering/hit testing/EMI exclusions use the same geometry. Run the focused SelfTest through `./gradlew runGameTestServer` and `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.test_static_regressions` to observe the old split shell fail.

**GREEN:** Make the base screen own shell initialization and profile-specific control construction. Replace Unicode/procedural cycle controls with the shared sprite/icon control and remove Crafting Terminal's hide-and-replace path. Ensure focus clears when a page, popup, or menu is left. Use one screen-wide count scale derived from the slot bound, never a per-value scale. Run the two focused commands again.

**Commit:** `feat: share adaptive terminal shell and controls`

## Task 3: Exact server-synced recipe presentation

> Status: complete. RED produced 28 missing-model/getter compile errors, 31 missing-geometry compile errors, 4 focused static UI failures, and 3 focused contract GameTest failures for bounded shaped recipes, exact-ID ordering, and navigation identity. GREEN is compileJava, build, SelfTest 222946/222946, GameTest 231/231, focused Python 43/43, full Python 90/90, and runData without drift; the exact model crosses the real menu wire without client RecipeManager/Core access.

**Files:**

- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/RecipePresentation.java`
- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/RecipePresentationKind.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/gametest/CraftingTests.java`
- Modify: `scripts/test_static_regressions.py`
- Modify: `docs/overview.md`
- Modify: `docs/structure.md`
- Modify: `docs/notes.md`

**RED:** Add menu/GameTests for shaped positions and dimensions, shapeless state, cooking, stonecutting, smithing roles, synthetic axe presentation, exact selected recipe ID, exact per-craft output stack count, station identity, aggregated available/required item rows, and distinct energy/tool rows. Add the menu parity regression for every new data/container field. Add static UI assertions for diagram-above-ledger geometry, explicit previous/next arrows, output count rendering, neutral item rows, and dark-red energy/tool rows. Run `./gradlew runGameTestServer` and the focused Python test and confirm the current fixed nine-cell panel fails.

**GREEN:** Build the presentation only on the server from the selected exact recipe and synchronize bounded primitive/stack data through the menu. Render positioned recipe diagrams above a compact variable-length ledger and footer without consulting client Core state or `RecipeManager`. Preserve exact result components and count. Run the focused commands.

**Commit:** `feat: sync and render exact recipe presentations`

## Task 4: EMI-first public-widget renderer with explicit native fallback

> Status: complete. Five focused static failures established the missing runtime/boundary/public-widget/exact-selection/bounded-no-catch contracts. GREEN is compileJava, build, SelfTest 222946/222946, GameTest 231/231, focused Python 48/48, full Python 95/95, runData without drift, and `runClient --dry-run`; full EMI is a development `runtimeOnly` dependency while release metadata remains optional. The local Maven full-jar transfer required a user-approved same-URL range-prefetch into Gradle cache and did not change project or CI dependency sources.

**Files:**

- Modify: `build.gradle`
- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/RecipeDiagramRenderer.java`
- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/NativeRecipeDiagramRenderer.java`
- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/compat/EmiRecipeDiagramBootstrap.java`
- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/compat/EmiRecipeDiagramRenderer.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/ClientSetup.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/compat/MagicStorageEmiPlugin.java`
- Modify: `scripts/test_static_regressions.py`
- Modify: `docs/notes.md`
- Modify: `docs/rs2-design-gap.md`

**RED:** Add static/compile contracts proving the development client includes the full EMI mod, the base screen and renderer interface do not import EMI, the guarded bootstrap is the only optional linkage point, only public `EmiRecipe`, `Widget`, and `WidgetHolder` APIs are used, no internal `WidgetGroup`/recipe-screen class is referenced, and there is no broad catch-and-native-fallback path. Add renderer-selection tests for EMI present, EMI absent, exact standard recipe found, and known unsupported/synthetic recipe. Run the Python test and `./gradlew compileJava` and observe missing adapter/runtime coverage.

**GREEN:** Implement a bounded public-API `WidgetHolder`, translate/render the widgets from `EmiRecipe#addWidgets`, forward input/tooltips only inside the diagram bounds, and keep the native ledger/craft controls authoritative. Select native rendering only when EMI is absent or the exact recipe has no compatible public representation. Let unexpected EMI exceptions surface. Run `./gradlew compileJava`, focused Python tests, and `./gradlew runClient --dry-run`.

**Commit:** `feat: render terminal recipes through EMI public widgets`

## Task 5: Atomic Player or Storage output destination

> Status: complete. RED produced 34 expected missing-output-API compile errors plus two focused static failures for the server control/shared rail and bilingual current-value tooltip. GREEN is compileJava, build, SelfTest 222946/222946, GameTest 238/238, focused Python 3/3, full Python 98/98, runData without drift, and `runClient --dry-run`; direct Player/Storage routing is atomic and EMI Cursor/Inventory remains request-authoritative.

**Files:**

- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/TerminalOutputDestination.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/MagicStorage.java`
- Modify: `src/main/resources/assets/magic_storage/lang/en_us.json`
- Modify: `src/main/resources/assets/magic_storage/lang/zh_tw.json`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/gametest/CraftingTests.java`
- Modify: `docs/overview.md`
- Modify: `docs/notes.md`

**RED:** Add tests for the independent server-synced session toggle; Player inventory-first plus Core remainder; Storage Core-only primary output and crafting remainders; exact-capacity success; one-item-short no-op; direct ×1/×8/×64/Max behavior; exact EMI Cursor/Inventory remaining authoritative; ingredient-source independence; rollback; and quantities crossing `Integer.MAX_VALUE`. Add menu parity coverage. Run `./gradlew runGameTestServer` and confirm direct craft's hard-coded inventory destination fails Storage cases.

**GREEN:** Keep EMI's `CraftingDestination` request contract separate and add a direct-terminal output destination. Extend simulate-then-commit delivery planning so every primary output and remainder follows the selected policy and the complete batch is rejected before mutation when capacity is insufficient. Add the shared rail toggle. Run `./gradlew runGameTestServer`.

**Commit:** `feat: add atomic terminal output routing`

## Task 6: Station categories and Axe Energy storage

> Status: complete. Initial RED produced 66 expected missing-category/Axe-Energy/persistence/menu compile errors, one focused Fuel UI static failure, and two focused GUI-lab/checklist failures. Review RED then exposed a hidden failed-migration axe, legacy instant overstack, ambiguous infinite presentation, and chunk-loop risk. GREEN is compileJava, build, SelfTest 222949/222949, GameTest 251/251, Python 99/99, runData without drift, and runClient dry-run. Five process descriptors remain stackable, three instant stations are max-one with upgrade recovery, the ninth descriptor is a consuming transient input or failed-migration recovery view, Core persists finite-or-explicit-infinite Axe Energy, and Max uses atomic per-key long-count mutations.

**Files:**

- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/MachineEnergyTable.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingStationTable.java`
- Create: `src/main/java/com/swearprom/magicstorage/magic_storage/AxeEnergy.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/AxeTransformationCatalog.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/StorageCoreBlockEntity.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalMenu.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/gametest/FuelPageTests.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/gametest/CraftingTests.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/gametest/PersistenceTests.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/gametest/TerminalFlowTests.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/SelfTest.java`
- Modify: `src/main/resources/assets/magic_storage/lang/en_us.json`
- Modify: `src/main/resources/assets/magic_storage/lang/zh_tw.json`
- Modify: `scripts/prepare_prism_gui_world.py`
- Modify: `scripts/run_prism_gui_session.py`
- Modify: `scripts/test_prepare_prism_gui_world.py`
- Modify: `scripts/test_run_prism_gui_session.py`
- Modify: `scripts/test_static_regressions.py`
- Modify: `docs/overview.md`
- Modify: `docs/notes.md`

**RED:** Add tests proving process machines stack and contribute per installed block; instant stations accept one and reject a second without consuming it; legacy stacked instant stations recover extras; axes cannot remain installed; finite conversion is remaining durability multiplied by vanilla Unbreaking level plus one; Mending and unrelated enchantments add nothing; multiple finite axes accumulate; checked overflow rejects without consumption; Unbreakable sets a persistent explicit infinite flag; finite `Long.MAX_VALUE` stays distinct from infinity; infinite recipes never decrement; further axes reject; and the old slot-8 axe clears only after successful migration or remains visible/retrievable after failure. Include finite/infinite recipe capacity, single-mutation `Long.MAX_VALUE` Max, rollback, save/load, and menu parity cases. Run `./gradlew runGameTestServer` and confirm the installed-tool/chunk-loop model fails.

**GREEN:** Classify descriptors as process or instant, enforce stack limits server-side, recover legacy instant-station extras, store Axe Energy and the explicit infinite flag in Core NBT, atomically convert accepted axes, expose a failed legacy slot-8 migration for recovery, sync explicit tool infinity, and make synthetic axe planning/commit reserve Axe Energy through per-key long-count mutations instead of mutating an item or chunk-looping. Run `./gradlew runGameTestServer`.

**Commit:** `feat: replace installed axes with persistent axe energy`

## Task 7: Scalable Fuel target popup and exact hover bounds

> Status: complete. RED began with missing popup/list/exact-hitbox APIs and three focused static failures, then added overlay click-through, covered-slot tooltip, and stale Prism checklist failures. GREEN is compileJava, build, SelfTest 222959/222959, GameTest 251/251, Python 102/102, runData without drift, and runClient dry-run. The shared cycle remains, while immutable bounded popup geometry, descriptor rows, selected state, close/focus/EMI behavior, event suppression, and exact slot/icon hover bounds are now guarded.

**Files:**

- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/TerminalLayout.java`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/CraftingTerminalScreen.java`
- Modify: `src/main/resources/assets/magic_storage/lang/en_us.json`
- Modify: `src/main/resources/assets/magic_storage/lang/zh_tw.json`
- Modify: `src/main/java/com/swearprom/magicstorage/magic_storage/SelfTest.java`
- Modify: `scripts/test_static_regressions.py`
- Modify: `scripts/run_prism_gui_session.py`
- Modify: `scripts/test_run_prism_gui_session.py`
- Modify: `docs/notes.md`

**RED:** Add tests for the separate list button, descriptor-driven popup rows, selected state, bounded scrolling, left-next/right-previous cycle behavior, outside-click and Escape close, focus cleanup, popup EMI exclusion, no rail overlap, and tooltips only inside the actual station slot/icon rectangle. Run SelfTest/Python and observe the current whole-flow-cell hover and cycle-only selector fail.

**GREEN:** Add popup geometry to the shared immutable layout, render the ordered server-approved target list, route bounded pointer/wheel/key input, and tighten station hit testing to icon/slot bounds. Run the focused tests.

**Commit:** `feat: add scalable fuel target selector`

## Task 8: Cohesive 16×16 texture and icon families

**Files:**

- Modify: `src/main/resources/assets/magic_storage/textures/block/*.png`
- Modify: `src/main/resources/assets/magic_storage/textures/item/*.png`
- Create or modify: `src/main/resources/assets/magic_storage/textures/gui/terminal_controls.png`
- Modify: `scripts/test_static_regressions.py`
- Create: `art/texture-generation/20260714-terminal-family/*`
- Modify: `docs/notes.md`

**RED:** Extend static tests to require every runtime block/item texture to be 16×16, reject orphaned obsolete runtime textures and generation sidecars, verify every expected semantic family member has a referenced runtime asset, and verify rail icons share the declared atlas/canvas. Run the focused Python test and record the inconsistent/orphaned current assets.

**GREEN:** Use the repo-local `minecraft-texture-replicate` workflow with one selected chassis/palette reference, fixed seed/settings, family-specific semantic motifs, and nearest-neighbor cleanup. Generate and inspect a contact sheet before replacing runtime assets. Keep prompts, predictions, and metadata only under `art/texture-generation/`; do not substitute a procedural generator if Replicate is unavailable. Remove obsolete unreferenced runtime textures. Run the texture/static tests and resource build.

**Commit:** `art: unify terminal block and control textures`

## Task 9: Documentation, version, complete gates, and Prism handoff

**Files:**

- Modify: `gradle.properties`
- Modify: `docs/overview.md`
- Modify: `docs/structure.md`
- Modify: `docs/notes.md`
- Modify: `docs/plan.md`
- Modify: `docs/roadmap.md`
- Modify: `docs/rs2-design-gap.md`
- Modify: `docs/superpowers/plans/2026-07-14-terminal-platform-emi-recipe-axe.md`
- Modify if baseline changes: `scripts/gui_test_world/*`

**RED/consistency audit:** Search all active docs for the old installed-axe, fixed-resource-grid, split-terminal, old sort-mode, and hard-coded inventory-output contracts. Update or archive stale active guidance according to the docs lifecycle. Add any missing static documentation assertions before implementation.

**GREEN and final gates:**

1. Set JDK 21 and run `./gradlew compileJava`.
2. Run `./gradlew build`.
3. Run `./gradlew runGameTestServer` and record exact SelfTest/GameTest totals.
4. Run `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts`.
5. Run `./gradlew runData`, then confirm `git status --short` contains no unexpected datagen drift.
6. Run JSON/model/texture validation and the project pre-push review.
7. Bump the patch version transactionally through the project deployment script; never hand-edit a deployed jar/version mismatch.
8. Rebuild the void-lab baseline only if the changed station/data model requires migration, deploy the unique jar to Prism dev, and launch the offline scenario runner.
9. Stop automation at `MS_GUI_TEST_READY`; the user enters native fullscreen and owns the visual verdict for Storage/Craftable/Fuel layouts, recipe diagram, all counts, popup, focus, output destination, station categories, infinity display, icons, and block/item family textures.
10. Close every terminal/process window opened by automation after the handoff or failure path.

**Commit:** `chore: finalize terminal platform release`

## Completion boundary

Automated completion requires every task commit and every final gate above to pass. GUI completion remains pending until the user records a current-run fullscreen verdict. Do not claim EMI/no-EMI or visual behavior from dedicated GameTests alone.
