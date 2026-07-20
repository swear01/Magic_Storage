# Modded Recipe Compatibility and Multi-Step Magic Crafting Plan

> Status: the keyed machine-descriptor prerequisite and the built-in Phase 1 adapter authority are implemented. Ten exact built-in families now provide complete one-level input/output/remainder/station/energy/tool/presentation/simulation/commit contracts plus exhaustive/non-exhaustive candidate discovery. GitHub #2's first slice adds exact component-preserving Smithing Trim variants; third-party families, a public addon API, and multi-step planning/execution are not implemented.

## Goals

1. Support selected third-party recipe and station families without pretending arbitrary recipes are safe.
2. Replace the current fixed recipe/station tables with an explicit, server-owned adapter model that can grow by installed mod.
3. Add deterministic multi-step magic crafting while preserving exact recipe identity, components, remainders, energy, capacity, and rollback.
4. Reuse EMI where its public API is suitable for recipe discovery and visualization, but never make EMI client state authoritative for storage or execution.

## Current boundary

- A mod recipe that uses vanilla shaped, shapeless, cooking, stonecutting, Smithing Transform, or Smithing Trim serializers already produces the exact vanilla recipe classes accepted by Magic Storage; a non-`minecraft` recipe namespace alone does not make it unsupported.
- Custom recipe classes/types, dynamic outputs, world/player/event-dependent transforms, fluids, chance outputs, and custom side effects remain fail-closed.
- `CraftableRecipeCatalog` now consumes exact built-in adapter classifications and an explicit candidate coverage result. Simple ingredient representatives are exhaustive; non-simple/custom representatives and Smithing predicate scans are conservatively non-exhaustive and therefore remain in the unindexed candidate set so a valid recipe is not omitted before its real predicate is reached.
- `BuiltInRecipeAdapters` is the sole policy authority for the supported one-level families. `CraftingTerminalMenu` consumes normalized matches and owns only joint reservation, destination delivery, capacity checks, mutation, and rollback; the replaced station/energy family-table source files are gone.
- The Fuel layout and public `magic_storage:machine_descriptor` registry are descriptor-count-driven and paged. Core state persists by stable descriptor ID, menu opening uses a cached server snapshot plus a fixed 256-slot parity bank, and unavailable addon-item NBT is retained for later reload. Recipe-family registration remains separate and is not implied by a machine descriptor.

## EMI source audit

The minimum development dependency is EMI `1.1.24+1.21.1`; this is not an exact player runtime pin. Released clients accept compatible EMI 1.x builds through `[1.1.24,2)`, while dedicated servers do not require EMI.

- The public `EmiRecipeManager` exposes recipes and input/output lookup, and `EmiRecipe#supportsRecipeTree()` identifies recipes that can participate in EMI's tree.
- Public `EmiApi.viewRecipeTree()` only opens EMI's tree screen. Public `focusRecipe(...)` only focuses an already-open recipe screen.
- Creating a tree goal and reading the tree use `BoM.setGoal(...)`, `MaterialTree`, and related `dev.emi.emi.bom` classes. Those classes exist in the runtime jar but are absent from the API artifact and are therefore internal implementation details.
- NeoForge 1.21.1 keeps authoritative recipes in the server `RecipeManager`; recipes are not generally synchronized to the client. That matches this project's server-authority rule.

Sources:

- [EMI public `EmiApi`](https://github.com/emilyploszaj/emi/blob/322171ee1d85cb1809bb7afd23d432b685756247/xplat/src/main/java/dev/emi/emi/api/EmiApi.java#L118-L179)
- [EMI public `EmiRecipe`](https://github.com/emilyploszaj/emi/blob/322171ee1d85cb1809bb7afd23d432b685756247/xplat/src/main/java/dev/emi/emi/api/recipe/EmiRecipe.java#L72-L98)
- [EMI public `EmiRecipeManager`](https://github.com/emilyploszaj/emi/blob/322171ee1d85cb1809bb7afd23d432b685756247/xplat/src/main/java/dev/emi/emi/api/recipe/EmiRecipeManager.java)
- [EMI internal `BoM`](https://github.com/emilyploszaj/emi/blob/322171ee1d85cb1809bb7afd23d432b685756247/xplat/src/main/java/dev/emi/emi/bom/BoM.java#L158-L175) and [internal `MaterialTree`](https://github.com/emilyploszaj/emi/blob/322171ee1d85cb1809bb7afd23d432b685756247/xplat/src/main/java/dev/emi/emi/bom/MaterialTree.java)
- [NeoForge 1.21.1 recipe model](https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/)
- [NeoForge 1.21.1 ingredient contract](https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/ingredients/)

**Decision:** EMI's tree is useful immediately as a player-facing planning/reference screen, but it is not a supported library API for exporting a craft graph or seeding a goal. Magic Storage must not link `dev.emi.emi.bom`, reflect into it, serialize its client tree, or trust it for execution. If EMI later exposes a public tree/goal API, the client bridge may consume it; the server still rebuilds and validates its own plan.

## Proposed architecture

### 1. Normalized recipe execution adapters

Introduce an internal adapter registry keyed by stable adapter ID. Each adapter owns the complete contract for one recipe family:

- whether an exact `RecipeHolder<?>` is supported;
- ordered input predicates, whether candidate representatives are exhaustive, catalysts/tools, remainders, and per-craft multiplicity;
- exact output assembly from the chosen full-component source stacks;
- required station descriptor and process/Fuel/tool costs;
- presentation kind/representatives;
- simulation and commit validation.

The existing vanilla families move behind adapters without changing behavior first. There is no generic `Recipe#getIngredients()` fallback: an unknown recipe is unsupported until an adapter proves its complete contract.

The completed built-in slice keeps `RecipeAdapterRegistry` keyed by stable `ResourceLocation`, rejects duplicate IDs, orders by priority then ID, binds each match to the exact holder instance, and recognizes only the exact shaped, shapeless, four cooking, stonecutting, Smithing Transform, Smithing Trim, and internal axe-transformation classes. Every successful match also carries ordered predicates and multiplicity, a stable station descriptor ID, process/Fuel/tool costs, checked component-sensitive output and remainders, presentation semantics, source-backed variant resolution, and simulation/commit validators. Smithing Transform and Trim enumerate only exact currently available template/base/addition stacks in canonical registry/component order and fail closed above 65,536 combinations. Trim assembly must resolve its pattern/material in the current registry context and may expose multiple exact output identities under one recipe ID. Every lookup, select, preview, Max, execute, and EMI request resolves the current holder by recipe ID; selected Smithing variants are then rebound by exact output identity, and a replaced same-ID holder still fails the in-flight identity check. Transform's generic target-result lookup remains an explicit discovery-only compatibility path and still executes a source-bound exact variant. There is no generic family fallback. Adapters are pure policy/validation and never mutate Core; the existing menu reservation/delivery/capacity/atomic rollback engine remains the only mutation owner.

### 2. Keyed station and energy descriptors (completed prerequisite)

The implemented public registry replaces persistent ordinal identity with stable `ResourceLocation` descriptor IDs. Its frozen descriptor snapshot drives:

- category, representative item, accepted machine/tool predicate, max installed count, and optional energy production;
- recipe adapter/station requirement mapping;
- Core persistence keyed by descriptor ID, with explicit migration from the current numeric slots;
- one fixed 256-slot Core/menu bank plus a paged server-supplied descriptor view; registration never changes client/server container parity;
- bounded menu opening data and payloads so server and client constructors retain identical fixed slot/data counts and order;
- variable reserve values through explicit packets rather than client-owned state or an unbounded `ContainerData` layout;
- Fuel `FlowGrid` pages and exact hitboxes.

Registration freezes before a world/menu can use it. Missing/reordered descriptors recover parseable stacks into ordinary Core storage; raw NBT for an unavailable addon item is retained and retried after later loads. The remaining plan must consume this API rather than replace it.

### 3. Server-owned recipe graph

Build the future planner from normalized server adapters, not from EMI widgets or client recipes. A plan node contains exact recipe ID, adapter ID, chosen input identities/counts, output identities/counts, station/energy costs, remainders, and requested craft count.

Planning rules:

- resolve alternatives deterministically from currently available exact `ItemKey`s, then stable registry/component order;
- reserve existing stock before expanding a missing ingredient into another recipe;
- detect recipe/output cycles by exact identity and report them explicitly;
- enforce configurable-in-code hard bounds for depth, node count, and arithmetic overflow; exceeding a bound is a visible planning failure, not partial crafting;
- account for catalysts, tools, energy, by-products, container remainders, and output capacity;
- keep intermediate products in a virtual working inventory, then simulate the complete net Core/player/cursor delta;
- commit the complete immediate job in one Core mutation batch only after revalidating recipe reload identity, topology, station state, sources, destination, and capacity.

The first multi-step version is immediate and atomic. Queued/asynchronous jobs, crafting CPUs, progress monitors, cross-chunk workers, and persisted partial execution are a separate later design because they require durable reservations and recovery after reload.

### 4. EMI bridge

Keep EMI required on clients, absent from dedicated-server requirements, and limited to public API:

- continue exposing the server-synchronized terminal inventory projection and exact one-level handler;
- add an **Open in EMI** action only through public `EmiApi` calls, so players can inspect/select recipes and use EMI's own recipe tree UI;
- distinguish those actions precisely: public `focusRecipe(...)` can open/focus the selected recipe, while public `viewRecipeTree()` can only open EMI's existing tree screen and cannot seed the selected recipe as a new goal;
- accept only exact backing recipe IDs that the server adapter registry supports;
- when Magic Storage gains its own multi-step request, send only `{containerId, recipeId, amount, destination}` and let the server build the graph;
- never transmit an EMI `MaterialTree`, default-resolution map, or client-calculated material cost as authority.

## TDD implementation phases

### Phase 1 — Built-in adapter authority, no family expansion

1. **Complete:** RED-first dist-neutral tests cover stable keyed IDs, duplicate rejection, priority/ID order, exact family selection, unsupported custom classes, candidate coverage, holder identity, and all mandatory match obligations.
2. **Complete:** nine table-driven GameTests use non-`minecraft` recipe IDs and prove the original families' exact adapter selection, preview, execute, components/output count/remainder, station, concrete cooking energy, and finite/infinite axe deltas. Dedicated `SmithingTrimTests` extends coverage to the tenth family: exact predicates/components/count, source-bound Transform regression, canonical order, bounded-cap fail-closed, multi-variant discovery/selection/execution, current-holder reload, missing station/context, full destination, and rollback.
3. **Complete:** built-in input/output/station/energy/tool/presentation/simulation/commit family policy is behind adapters; the menu retains only the generic transaction engine and reclassifies fresh holders at each operation.
4. **Out of scope:** third-party compatibility, public adapter registration, multi-step planning/execution, GUI/resources/lang changes, and EMI tree integration remain later phases.

### Phase 2 — First third-party compatibility module

1. Select one installed NeoForge 1.21.1 mod and one bounded recipe family after inspecting its real source/API.
2. Prefer a fixed item-input/item-output family with deterministic assembly and a single station. Do not start with world-interaction, fluid-network, chance, or event-driven recipes.
3. Add its API as `compileOnly` only if source-level integration is required; runtime dependency remains optional unless the user explicitly changes the product policy.
4. Write absent-mod classloading, recipe reload, station missing/present, exact components, amount, remainder, capacity, rollback, EMI backing-ID, and dedicated-server tests before implementation.

### Phase 3 — Dynamic station descriptors

1. Write migration and menu-parity RED tests first.
2. Introduce stable keyed persistence and an immutable registry snapshot.
3. Synchronize the descriptor snapshot before constructing client slots; keep storage values server-owned.
4. Prove zero/current/capacity/capacity+1/many descriptors stay reachable through Fuel paging and never overlap the reserved type-capacity area.
5. Expose a public third-party registration API only after two internal compatibility modules use the same contract without special cases.

### Phase 4 — EMI planning bridge

1. Test EMI-present client integration and EMI-absent dedicated-server classloading separately.
2. Use only public API calls to open recipe views/tree UI.
3. Keep the existing exact one-level handler as the execution path until the server planner exists.
4. If no public API can perform a requested action, report that boundary; do not reflect into EMI internals.

### Phase 5 — Pure multi-step planner

1. Start with a dist-neutral planner over synthetic adapter fixtures.
2. RED cases: two/three-step chains, output multiplicity, shared intermediates, alternatives, existing partial stock, exact components, non-simple ingredient indexing, catalysts, remainders, energy, cycles, overflow, depth/node/time/payload bounds, and deterministic tie-breaks.
3. Add server RecipeManager integration only after the pure planner is green.
4. Return an immutable plan or a typed failure reason; no partial/default plan.

### Phase 6 — Atomic execution

1. GameTest complete-job simulation against Core plus optional player sources/destinations.
2. Revalidate every step at commit time and apply one net transaction.
3. Test reload/stale recipe, changed station, changed topology, full destination, `Long.MAX_VALUE`, component variants, rollback, and listener reentrancy.
4. Deliver only the requested final output to Player/Cursor when selected; intermediates and remainders follow the simulated Core plan.

### Phase 7 — Multi-step UI and release gate

1. Show a concise plan summary: final amount, missing base resources, required stations/energy, and step count.
2. Use a separate details view for the full tree; do not overload the current one-recipe ledger.
3. Use EMI as the client recipe-browser/tree view and keep a Magic Storage-owned summary for server-planned costs and unsupported synthetic presentations.
4. Run complete automated gates, then the current fullscreen Prism checklist owned by the user.

## First target selection gate

Before Phase 2 implementation, record the chosen mod, exact mod/version/license/source, recipe type/class, station item, inputs/outputs/remainders, assembly semantics, and optional dependency policy. If any of those cannot be established from source or official API, stop rather than guessing.

## 2026-07-20 compatibility test target audit

External mods enter a test runtime only with assertions for the contract they are meant to prove. A boot-only collection is not a compatibility test and must not make normal CI depend on unrelated large artifacts.

| Target | Audited 1.21.1 baseline | Contract to prove | Boundary |
|---|---|---|---|
| Applied Energistics 2 | [19.2.17](https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/tag/neoforge/v19.2.17), LGPL-3.0 | An AE2 item in `c:tools/wrench` rotates and safely removes Magic Storage blocks; AE2 recipes using exact vanilla classes remain craftable. Charger is the second custom-family candidate because it is one item input to one fixed output. | GuideME is required. Inscriber is deferred: PRESS consumes optional plates while INSCRIBE retains them, so it needs explicit catalyst/remainder semantics rather than Charger assumptions. |
| Sophisticated Storage | [1.21.1-1.5.80.1999](https://modrinth.com/mod/sophisticated-storage/version/IfMsqcCe), All Rights Reserved | Directional Import, passive Import, and Export interact with its registered block `Capabilities.ItemHandler.BLOCK` without duplication, loss, slot-order drift, or bypassing Magic Storage filters. | Sophisticated Core is required. This target tests bus capability interoperability, not recipe-adapter support; test jobs may fetch the official artifact but must never redistribute or bundle it. |
| Mekanism | [10.7.19.85](https://github.com/mekanism/Mekanism/releases/tag/v1.21.1-10.7.19.85), MIT | First custom recipe adapter candidate: bounded Enriching and Crushing `ItemStackToItemStackRecipe` families, exact source stack, output/components/count, station/energy, capacity, reload, and rollback. | Start with deterministic item-to-item recipes only. Chemical/fluid, secondary/chance output, and machine-world side effects remain unsupported. |

The main CI prerequisite is a repository-owned addon fixture proving that a separate mod can register process, instant, and consumable descriptors through `MachineDescriptorApi`. Real-mod present/absent matrices belong in an independently pinned compatibility job after the corresponding behavior assertions exist; they must not silently pass when the target mod failed to load.

## Non-goals for the first multi-step release

- EMI internal `BoM`/`MaterialTree` linkage or reflection.
- Automatic support for every registered `RecipeType`.
- Fluid/chemical/network side effects without an explicit transaction adapter.
- Chance recipes whose probability/remainder semantics cannot be simulated exactly.
- Persistent asynchronous jobs, crafting CPUs, monitors, or chunk-loading workers.
- Client-owned storage snapshots or client-authoritative craft plans.
