# Bus Automation Contract

> Status: authoritative design for GitHub issue #6. Phase 1 state/migration is
> implemented; later behavior changes remain unauthorized until their phase-specific
> RED tests exist.

## Goal

Import and Export Buses form a bounded, server-owned automation boundary around a
Storage Core. They may interoperate with vanilla and modded item handlers, but must
never expose the Core as an unrestricted inventory.

This contract keeps the current behavior until each migration phase lands:

- Import Bus actively pulls one stack from its front every cooldown and accepts
  passive insertion on every side, including an unsided capability query.
- Export Bus actively pushes one filtered stack through its front and exposes no
  external extraction capability.
- All transfers remain server-authoritative, currently-loaded-path-only, and
  simulate-then-commit.

## Evidence and boundaries

### Local baseline

- `ImportBusBlockEntity` already has front active pull, a stable one-slot
  insert-only handler, exact remainder returns, loaded-path validation, and a
  ten-tick missing-Core backoff.
- `ExportBusBlockEntity` already simulates Core extraction and target insertion,
  extracts only the amount simulated to fit, restores a changed target's
  remainder to the Core, and explicitly drops an unrestorable remainder.
- `Actor` is currently a diagnostic name only. It does not carry structured bus,
  network, owner, or operation identity.
- `StorageCoreBlockEntity` is the only item owner. A bus never stores inventory.
- `WrenchActions` rotates directional buses and routes dismantling through normal
  break/drop hooks.

### External patterns, not copied code

- NeoForge 1.21.1 defines sided block item capabilities and recommends a stable
  cached capability for frequently queried targets:
  [capability documentation](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/).
- NeoForge's `IItemHandler` contract says insertion must not mutate the caller's
  stack and must return the exact remainder; extraction simulation must return a
  safe copy no larger than the request and stack limit:
  [`IItemHandler` at the audited 1.21.1 commit](https://github.com/neoforged/NeoForge/blob/73ab915032bfb2898c82dadabc3f7c4a1ce983bf/src/main/java/net/neoforged/neoforge/items/IItemHandler.java).
- Capability providers must call `Level.invalidateCapabilities(pos)` when an
  existing capability becomes unavailable or a new one becomes available:
  [`RegisterCapabilitiesEvent`](https://github.com/neoforged/NeoForge/blob/73ab915032bfb2898c82dadabc3f7c4a1ce983bf/src/main/java/net/neoforged/neoforge/capabilities/RegisterCapabilitiesEvent.java).
- NeoForge's cache returns `null` rather than loading an unloaded target and
  forbids querying from its invalidation callback:
  [`BlockCapabilityCache`](https://github.com/neoforged/NeoForge/blob/73ab915032bfb2898c82dadabc3f7c4a1ce983bf/src/main/java/net/neoforged/neoforge/capabilities/BlockCapabilityCache.java).
- Refined Storage 2 separates transfer policy from platform handlers, requires an
  importer source to accept rollback, and uses simulate/extract/insert/fallback
  phases:
  [`ImporterSource`](https://github.com/refinedmods/refinedstorage2/blob/0643e6f4bc25f12dc01893916c53d393ecdc08d0/refinedstorage-network/src/main/java/com/refinedmods/refinedstorage/api/network/impl/node/importer/ImporterSource.java),
  [`TransferHelper`](https://github.com/refinedmods/refinedstorage2/blob/0643e6f4bc25f12dc01893916c53d393ecdc08d0/refinedstorage-storage-api/src/main/java/com/refinedmods/refinedstorage/api/storage/TransferHelper.java), and
  [`ExporterTransferStrategyImpl`](https://github.com/refinedmods/refinedstorage2/blob/0643e6f4bc25f12dc01893916c53d393ecdc08d0/refinedstorage-network/src/main/java/com/refinedmods/refinedstorage/api/network/impl/node/exporter/ExporterTransferStrategyImpl.java).
- RS2's tri-state security providers are a useful composition pattern, but Magic
  Storage will not copy that API or make RS2 a dependency:
  [`SecurityDecision`](https://github.com/refinedmods/refinedstorage2/blob/0643e6f4bc25f12dc01893916c53d393ecdc08d0/refinedstorage-network-api/src/main/java/com/refinedmods/refinedstorage/api/network/security/SecurityDecision.java).
- The first real optional-handler matrix targets Sophisticated Storage 1.21.x's
  direct storage and controller handlers, in addition to adversarial synthetic
  handlers:
  [`StorageBlockEntity`](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/9aa6ad5170f8615a192d96c0607d3e4c0dc7e54c/src/main/java/net/p3pp3rf1y/sophisticatedstorage/block/StorageBlockEntity.java) and
  [`ControllerBlockEntity`](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/9aa6ad5170f8615a192d96c0607d3e4c0dc7e54c/src/main/java/net/p3pp3rf1y/sophisticatedstorage/block/ControllerBlockEntity.java).

These references are patterns only. Magic Storage keeps its own code, data model,
transactions, and license obligations.

## Non-negotiable invariants

1. The Core remains the sole owner of stored items. Buses hold configuration only.
2. A capability never exposes arbitrary Core slots, counts, insertion, or
   extraction. Every operation passes bus mode, side, filter, security, actor,
   topology, and transaction validation.
3. Client state is presentation only. NBT, filter evaluation, candidate ordering,
   security, simulation, execution, and rollback are server-owned.
4. Neither path validation nor capability discovery may load a chunk.
5. Simulation and execution use the same policy and exact remainder/count
   vocabulary. Execution revalidates all mutable state.
6. An `ItemStack` supplied by another handler is never mutated.
7. A remainder is restored to its source or Core. If restoration becomes
   impossible, the exact remainder is dropped at the bus and logged; it is never
   silently voided.
8. Conflicted multi-Core networks perform no transfer and expose no extraction.
9. One cooldown moves at most one normal item stack. Speed upgrades and bulk
   transfer are outside this issue.
10. Unknown modes, rule types, malformed IDs, missing registry entries, and stale
    packets fail closed and remain visibly diagnosable.

## Authoritative state model

Each bus BlockEntity stores a versioned `busConfig` compound:

```text
schema             int, initially 1
mode               "directional" | "directionless"
sideMask           six bits keyed by absolute world Direction
unsidedAccess      boolean
automationEnabled  boolean
filterMode         "allow" | "deny"
filterRules        ordered list, maximum 9
owner              optional UUID
configRevision     non-negative long
```

Runtime-only state includes cached Core/path, target capability cache, cooldown,
per-tick operation counter, and the reentrancy guard. Runtime state is never a
source of saved truth.

### Mode and facing

- `FACING` remains a blockstate property and is always rendered.
- `mode` is also represented by a blockstate property so clients can select the
  directional-arrow or neutral-directionless model without receiving private
  configuration.
- `DIRECTIONAL` is the default and preserves the current front transfer.
- `DIRECTIONLESS` performs no active neighbor scan. It is capability-driven only.
  This is the central loop-safety rule; directionless never means "scan all six
  inventories every cooldown".
- Normal Wrench use rotates `FACING` only in `DIRECTIONAL`. It returns `PASS` in
  `DIRECTIONLESS`; mode changes belong to the configuration screen.
- `sideMask` uses absolute world directions and is intentionally unchanged by a
  Wrench rotation. Rotating the active front must not silently move a pipe's
  passive permission to another physical face.
- Reload, piston movement, and a same-state block update preserve the complete
  config. Capability caches are discarded and rebuilt after movement or facing
  change.

### Exact side and capability matrix

`sideMask` controls physical sided capability queries. `unsidedAccess` separately
controls `side == null`; null never bypasses a false unsided setting.

| Bus | Mode | Active tick | Sided capability | Null capability |
|---|---|---|---|---|
| Import | Directional | pull from `FACING` | insert-only on enabled bits | insert-only iff `unsidedAccess` |
| Import | Directionless | none | insert-only on enabled bits | insert-only iff `unsidedAccess` |
| Export | Directional | push to `FACING` | none | none |
| Export | Directionless | none | filtered extract-only on enabled bits | filtered extract-only iff `unsidedAccess` |

Additional rules:

- A disabled side returns `null`, not a dummy handler.
- Mode, side, unsided, automation, or ownership changes call
  `Level.invalidateCapabilities(busPos)` after the server commits the config.
- Stable handler objects are reused while their capability remains available.
- `automationEnabled == false` disables active ticks and all capability providers.
- Active targets that implement `IStorageNetworkBlock` are always rejected.
- The queried target side is `FACING.getOpposite()` and must be re-queried after
  rotation or target invalidation.

## Filters and deterministic ordering

### Rule types

The only schema-1 rule types are:

1. `exact_stack`: exact item plus immutable data components (`ItemKey`).
2. `item`: registry item ID, components ignored.
3. `tag`: item tag `ResourceLocation`.
4. `mod`: exact registry namespace.

There is no regex, display-name, lore-text, generic NBT substring, script, or
arbitrary predicate rule.

### Evaluation

- `ALLOW`: a resource must match at least one valid rule. Empty means allow none.
- `DENY`: a resource must match no valid rule. Empty means allow all.
- Rules are checked in slots `0..8`. Duplicate rules after the first are retained
  as visible invalid entries and match nothing; they are never silently deleted.
- An invalid ID, missing item, missing tag, or unavailable addon item matches
  nothing. Raw rule data is retained so reinstalling the addon can restore it.
- Tag membership and registry lookups use the current server registry snapshot.
  A datapack reload invalidates the compiled filter cache and increments the
  effective filter revision.

### Candidate order

- Import active transfer keeps source-handler slot order, then applies the filter.
- Export checks filter slots in order. For an `exact_stack` or `item` rule, exact
  Core keys are ordered by registry ID and then canonical component encoding.
  Tag and mod rules use the same inner order.
- In `DENY` mode, allowed Core keys use that global order directly.
- The first transferable candidate wins. There is no hash-map iteration in player
  visible or transfer ordering.

The current Export single exact filter migrates to slot 0 in `ALLOW` mode. Import
starts in `DENY` mode with no rules, preserving accept-all behavior.

## Structured actor and loop isolation

`Actor.name()` is not sufficient for policy. The storage API gains a dist-neutral
structured bus actor carrying:

```text
owner UUID or explicit legacy-unclaimed marker
dimension ResourceKey
bus BlockPos
Core network UUID
operation direction: IMPORT | EXPORT
operation ID: game time plus a per-bus monotonic counter
```

A server-scoped `BusTransferGuard` wraps every active and passive operation in
`try/finally`:

- Re-entry by the same operation ID is rejected before simulation.
- A nested bus call into the same Core network is rejected when the current
  transfer already originated from that network.
- An inverse Import/Export call for the same bus and operation is rejected.
- The guard is execution-context state, not item NBT and not a global item
  cooldown heuristic.

This prevents pipes or modded handlers from recursively calling a bus capability
back into itself during `insertItem`/`extractItem`. Directionless mode has no active
scan, and directional mode rejects network blocks as targets, so neither mode can
create an autonomous unbounded self-loop. A player can still deliberately build a
bounded cross-tick ping-pong using two buses and an external inventory; each leg is
limited to one stack per cooldown and is visible in configuration. Magic Storage
does not guess player intent or tag items with provenance.

## Multiplayer access policy

This issue uses bus-local ownership rather than silently introducing a global Core
ACL that would also change terminals and remotes.

- A newly placed bus records the placing player's UUID.
- A legacy bus without an owner continues its existing automation behavior. Its
  first otherwise-valid configuration, Wrench rotation, or Wrench dismantle by a
  non-operator atomically assigns that player as owner before applying the action.
  A permission-level-2 operator may act without claiming it.
- After a bus has an owner, configuration packets, mode changes, filter edits,
  Wrench rotation, and Wrench dismantling require that owner or a server operator
  with permission level 2.
- The player must also be a non-spectator, be within the normal interaction range,
  target the same loaded BlockEntity instance, have `mayBuild`, and pass the
  existing NeoForge interaction/break event. Claim/protection mods therefore keep
  their veto.
- `automationEnabled` is owner-controlled. When enabled, active and passive
  operations run as the structured bus actor; when disabled, they fail closed.
- Bus item drops preserve mode, sides, unsided setting, automation setting, and
  valid/raw filter rules, but strip the old owner. Placement assigns the new owner.
- No packet may set an arbitrary owner UUID.

This policy prevents another player from widening an Export Bus filter or side
surface while still allowing intentionally exposed hoppers/pipes to use the exact
owner-configured capability.

## Transfer protocol

Every operation uses an immutable plan containing at least:

- bus position and config revision;
- Core dimension, position, network UUID, topology revision, and conflict state;
- loaded validated bus-to-Core path;
- source/target position, block state, queried side, and capability identity;
- exact `ItemKey`, simulated count, and filter revision;
- structured actor and operation ID.

Immediately before execution, the server re-resolves or verifies every field. A
changed field cancels the plan without mutation and lets the next cooldown retry.

### Active Import

1. Resolve a currently loaded, valid, non-conflicted Core path.
2. Resolve the currently loaded front handler and iterate source slots in order.
3. Simulate source extraction of at most 64.
4. Apply the current filter and simulate exact Core insertion.
5. Revalidate the plan and execute source extraction only for the accepted count.
6. Execute Core insertion with the same actor.
7. If the Core accepts less after re-entrant change, return the exact remainder to
   the same source slot. Drop and log only the amount the source now rejects.

### Passive Import capability

- It has one virtual empty slot, accepts insertion only, and never stores a stack.
- `getStackInSlot` and `extractItem` return empty; `isItemValid` reports only
  schema-level validity and does not replace insertion simulation.
- Simulation calls Core simulation only. Execution repeats filter, security,
  topology, and Core-capacity validation, commits only the accepted amount, and
  returns an exact copied remainder.
- The caller's input stack is never modified.

### Active Export

1. Resolve a currently loaded, valid, non-conflicted Core path and loaded front
   target.
2. Select the deterministic first filter candidate.
3. Simulate Core extraction of at most 64 and target insertion across target slots.
4. Revalidate the plan and execute only the amount simulated to fit.
5. Insert into the target using actual returned remainders.
6. Restore a changed target's exact remainder to the Core. If conflict or another
   re-entrant mutation makes restoration impossible, drop and log that exact
   remainder at the Export Bus.

### Passive Export capability

- It exists only in `DIRECTIONLESS`, only on enabled sides/null, and only while
  automation is enabled and the loaded Core path is valid.
- It is a stable one-slot, extract-only virtual view. `insertItem` always returns
  an unchanged copy and `isItemValid` is false.
- `getStackInSlot(0)` returns a safe copy of the deterministic current candidate,
  capped to its normal max stack size. It exposes no Core count beyond that copy.
- `extractItem(0, amount, simulate)` clamps to `1..64`, reselects and validates the
  current candidate, and calls Core simulate/execute with the bus actor. The
  returned stack is the exact amount extracted and is safe for the caller to own.
- No raw Core `IItemHandler`, arbitrary slot index, insertion path, or unfiltered
  extraction is reachable.

## Loaded-path and capability lifecycle

- Core/path resolution keeps the current bounded BFS, cached loaded path, and
  ten-tick negative lookup backoff.
- Every cached path check verifies every member is loaded and still an
  `IStorageNetworkBlock`. No `getChunk`, `getBlockEntity`, or capability query is
  used to force-load a missing path member.
- Front target discovery first checks `hasChunkAt`/`isLoaded`.
- A `BlockCapabilityCache` may replace repeated front lookups, but is recreated
  when facing/target changes. Its invalidation listener only marks the transfer
  dirty; the next tick performs the lookup.
- Missing target, invalidated capability, unloaded chunk, stale Core, disconnected
  bus, and Core conflict all produce a no-op, not a fallback route.

## Persistence, synchronization, and migration

### Existing saves

- Import: `DIRECTIONAL`, all six side bits, `unsidedAccess=true`,
  `automationEnabled=true`, `DENY` with no rules, legacy-unclaimed owner.
- Export: `DIRECTIONAL`, all six side bits, `unsidedAccess=false`,
  `automationEnabled=true`, `ALLOW`, current exact filter in slot 0 when present,
  legacy-unclaimed owner.
- Existing `coreX/coreY/coreZ` is at most a cache hint. It never authorizes a Core
  or loads its chunk.
- Unknown future schema data is preserved raw and the bus remains disabled with a
  visible unsupported-config state. It is not reset to permissive defaults.

### Client sync

- Blockstate synchronizes facing and directional/directionless visuals.
- A server menu snapshot synchronizes mode, side mask, unsided setting,
  automation state, owner-is-current-player, filter mode/rules, invalid-rule
  markers, and config revision.
- Edits are C2S requests carrying container ID, bus position, and expected config
  revision. The server resolves the current menu/BE, checks access, validates the
  complete proposed state, commits atomically, calls `setChanged`, invalidates
  capabilities when required, and sends the new snapshot.
- Client prediction never changes transfer behavior.

## Player-facing UI and visual contract

- The screen uses one concise mode selector, six physical side toggles, one
  unsided toggle, one automation on/off control, an Allow/Deny selector, and a
  nine-slot ordered filter grid.
- Directional mode explains only "Front transfer". Directionless explains only
  "External pipes use enabled sides". Tooltips do not teach mouse gestures.
- Invalid/missing rules remain visible with a neutral unavailable marker; they do
  not turn red or disappear.
- Directional textures keep the existing cyan-in/orange-out front grammar.
  Directionless models use a neutral all-side port while keeping Import and Export
  distinguishable.
- en_us and zh_tw keys are added together. No implementation terms such as BFS,
  actor ID, capability, or rollback appear in player text.
- Any screen, blockstate, or texture change requires the deterministic Prism lab,
  current fullscreen gate, and user visual verdict. Automated tests do not approve
  layout.

## Implementation phases

### Phase 0 — Contract

This document is the required pre-production decision record.

### Phase 1 — State and migration, no behavior change

Add the versioned config, structured actor types, deterministic filter value
objects, owner capture, config revision, codecs, and migration tests. Keep active
Import/Export and current capability availability unchanged.

Implemented 2026-07-17: schema-1 config/raw preservation, exact legacy defaults,
four rule value types, placement owner, owner-stripped Export drop state, structured
actor identity, revision validation, and eight registered GameTests. Normal transfers
still use the legacy actor and directional behavior; no later-phase field is active.

### Phase 2 — Filter and server configuration

Add Import filtering, ordered Export candidates, atomic server menu packets,
ownership checks, and capability invalidation. Keep both buses directional.

### Phase 3 — Directionless capabilities

Add directionless Import passive-only mode and the filtered one-slot Export
extract-only handler. Prove every side/null matrix and re-entrant handler case
before adding client controls.

### Phase 4 — Presentation and compatibility

Add the shared bus configuration screen, blockstate/models/textures, i18n,
Wrench/access integration, deterministic Prism lab fixtures, and the optional
Sophisticated Storage test runtime. Do not bundle or require that mod in release.

## RED-first verification matrix

### Dist-neutral SelfTests

- schema-0 migration exact defaults for Import and Export;
- unknown schema preserved and disabled;
- all mode/side/null capability decisions;
- filter rule validation, duplicates, missing registry entries, exact components,
  tag reload, Allow/Deny empty semantics, and deterministic ordering;
- structured actor identity and re-entry rejection;
- config revision overflow/validation and stale update rejection;
- owner/operator/configure decisions without client classes.

### Required GameTests

- current directional Import/Export behavior is unchanged after migration;
- passive Import full/partial simulation and execution do not mutate caller input;
- active Import restores a re-entrant remainder to the same source or drops the
  exact unrestorable amount;
- active Export extracts only actual target capacity and restores/drops exact
  remainder after re-entrant target refusal;
- directionless Import has no active pull and only accepts configured sides/null;
- directionless Export has no active push and exposes only filtered extraction;
- hopper insertion into Import and hopper extraction from Export obey mode, side,
  filter, and stack limits;
- synthetic handlers cover slot-order change, capability replacement, same-object
  remainder, partial execution, recursive callback, invalid slot, and over-request;
- Sophisticated Storage direct storage and controller handlers pass present-mod;
  the dedicated server and normal build pass absent-mod without loading its classes;
- missing/conflicted Core, disconnected path, unloaded intermediate/target chunk,
  bus move, piston move, Wrench rotation, reload, and datapack tag reload fail
  closed without loading chunks;
- unauthorized configure/rotate/dismantle requests fail; owner/operator and claim
  event paths succeed exactly once;
- recursive same-network Import/Export capability callbacks terminate without
  mutation, duplication, deletion, stack overflow, or repeated BFS;
- NBT/item-component filter identities survive save/load and Wrench drop/place.

### Repository gates

- `./gradlew compileJava`
- `./gradlew build`
- `./gradlew runGameTestServer`
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts`
- `./gradlew runData` followed by a clean resource diff
- minimum/latest compatible EMI compile gates already defined by CI
- JSON/model/texture/i18n parity and `git diff --check`
- deterministic Prism scenario plus user fullscreen verdict for visual phases

## Non-goals

- No raw Core inventory capability.
- No fluid/energy bus in this issue.
- No speed/stack/fortune/crafting upgrades.
- No active six-direction scan in directionless mode.
- No item provenance tags or heuristic cross-tick loop blacklist.
- No global Core/terminal/remote ACL redesign.
- No bundled or required Sophisticated Storage dependency.
- No silent downgrade when a target, addon, config schema, or capability is absent.
