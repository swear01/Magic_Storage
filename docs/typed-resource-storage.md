# Typed Resource Storage Architecture

> Status: implemented foundation under GitHub [#9](https://github.com/swear01/Magic_Storage/issues/9). Item, fluid, NeoForge Energy, optional Mekanism chemical, and registered addon kinds now share one live ledger and transaction domain. Terminal listing and its resource selector, passive Bus capabilities, Creative unlimited type capacity, the public resource-kind API, and deterministic typed recipe families are connected.

## Product boundary

Magic Storage owns stored resources and executes supported recipes immediately inside its server-owned transaction engine. It does not export ingredients to an external machine and wait for that machine to finish.

Items, fluids, NeoForge Energy, and Mekanism chemicals are the first required kinds. They are not a closed enum. Future addons must be able to register other deterministic resources without adding another hardcoded Core field.

## Universal identity

Every stored entry has:

- a stable resource-kind ID;
- a stable resource ID inside that kind;
- exact amount-independent variant data;
- a non-negative `long` amount.

The initial internal key is deliberately independent from optional-mod Java classes. Persistence and atomic planning can therefore retain an unavailable kind as raw keyed data when its provider mod is missing. A kind-specific bridge converts between this universal identity and an `ItemStack`, `FluidStack`, energy value, `ChemicalStack`, or future addon value.

Variant data is part of identity. Item and fluid components cannot be discarded or normalized to registry defaults. Resource bridges must produce one canonical representation and must reject malformed or lossy values rather than guessing.

## Required resource kinds

### Item

- Identity: exact item registry ID plus exact data components.
- Amount: item count as `long`.
- `ItemKey` remains the exact item adapter, but live counts are stored only in the universal ledger. The old segmented item NBT is now a persistence compatibility encoding, not a second runtime map.

### Fluid

- Identity: exact fluid registry ID plus exact `FluidStack` components.
- Amount: NeoForge fluid units as `long` internally; each `IFluidHandler` call remains bounded to its `int` contract.
- External access uses NeoForge `Capabilities.FluidHandler` and honors `SIMULATE`/`EXECUTE` return values.

### Power

- Identity: the NeoForge Energy resource ID with no variant payload.
- Amount: FE as `long` internally; each `IEnergyStorage` call remains bounded to `int`.
- This pool is separate from Magic Storage's recipe process/Fuel reserves. They cannot silently convert into one another.

### Chemical

- Identity: exact Mekanism chemical registry ID.
- The persisted resource-kind ID remains `mekanism:chemical` for compatibility with 0.1.31 worlds. The built-in registry entry is exposed through a NeoForge alias from `mekanism:chemical` to `magic_storage:chemical`; do not rename it or silently migrate saved keys.
- Amount: chemical amount as `long`.
- The compatibility module uses `ChemicalStack` and `IChemicalHandler` only when Mekanism is present. The normal build and dedicated server must remain safe when it is absent.
- The player-facing dependency is not pinned to the one representative version exercised by CI. No Mekanism multi-version CI matrix is planned; incompatibilities outside that representative fixture are handled from user reports.

## Ledger and transaction rules

- A transaction is a finite map of exact resource keys to signed deltas.
- Simulation validates every delta, underflow, overflow, type-capacity transition, source, destination, and provider rule without mutation.
- Commit revalidates the same transaction and applies every kind or none.
- Failed mixed-kind transactions cannot leave an item consumed while a fluid, power, chemical, remainder, or output was not committed.
- Exact insertion/extraction APIs reject invalid amounts. Bounded capability APIs may return a smaller accepted amount only when their contract explicitly allows it.
- Every successful mutation marks the Core changed and emits listeners only after the mutation batch is complete.
- Type capacity counts every non-zero exact resource key, independent of kind. Total-capacity policy remains kind-specific and must be explicit; no implicit item-to-fluid conversion factor is allowed.

## Resource-kind registration boundary

`StorageResourceKindApi.createDeferredRegister(modId)` registers stable kind IDs in `magic_storage:resource_kind` during normal NeoForge loading. A kind declares whether exact variant payloads are legal and supplies a non-empty representative item. Addons construct canonical `StorageResourceKey` values and may expose them through `StorageResourceCapabilities.BLOCK`; built-in helpers create and decode exact item/fluid keys and the singleton NeoForge Energy key.

Registration does not grant Core/player mutation callbacks. `StorageResourceHandler` exposes bounded list/amount/insert/extract operations; `StorageResourceTransaction` is the only public multi-key mutation request. Magic Storage still owns validation, capacity, persistence, synchronization, and all-or-nothing commit. Missing providers remain raw on disk and are omitted from terminal presentation until their kind is registered again; new live mutations reject unregistered kinds instead of guessing or fabricating a representative.

Terminal entries carry the exact key and long amount in display-only metadata. A server-owned menu value selects exactly one presentation group: Item, Fluid, NeoForge Energy, Mekanism Chemical/Gas, or Other. Other contains registered addon kinds not promoted to a built-in group; missing providers remain omitted. The selector appears on Storage views in both terminal types, resets to Item, and is hidden/rejected on Craftable and Fuel. Clicking a non-item representative never extracts its icon or selects an item recipe, and EMI excludes those representatives from item inputs. Import and Export Buses expose the generic resource capability plus native fluid/energy and optional chemical wrappers: Import is insert-only and Export is extract-only. Existing item filters are not reinterpreted as typed filters, so non-item resources pass only under `DENY` policy; active front scanning remains item-only. Creative Storage makes the same shared type domain unlimited, not only items.

## Recipe-family relationship

`RecipeFamilyFactories.deterministicResources(...)` resolves a `TypedRecipePlan` from the current exact recipe holder and server registries. A plan declares one to nine exact inputs, multiple exact outputs, a selectable primary item output, layout, and explicit roles. `CONSUME` multiplies by craft count; `CATALYST` and `TOOL` are retained and reusable across a batch; `PRIMARY` item outputs follow the selected Player/Storage destination, while non-item outputs and typed remainders return to Core. Checked multiplication, destination capacity, every consumed item/non-item, and every Core output are simulated and committed as one ledger transaction.

Chance, dynamic world/player callbacks, arbitrary mutation callbacks, and external-machine send-and-wait remain unsupported. Multi-step graph planning is still future work under GitHub [#1](https://github.com/swear01/Magic_Storage/issues/1).

## Delivery phases

1. **Implemented:** universal key, delta, ledger, persistence, simulation, and atomic commit algebra.
2. **Implemented:** item live-ledger bridge with exact components and legacy segmented-NBT compatibility.
3. **Implemented:** fluid storage plus `IFluidHandler` capability and transfer tests.
4. **Implemented:** NeoForge Energy storage plus `IEnergyStorage` capability and separation tests.
5. **Implemented foundation:** optional Mekanism chemical capability, absent-mod base run, and one representative present-mod GameTest fixture. This is intentionally not a multi-version matrix; other-version incompatibilities are fixed from user reports rather than turning CI into a version guarantee.
6. **Implemented:** typed terminal listings, generic/native passive Bus capabilities, Creative unlimited type capacity, and addon resource-kind registry/capability API.
7. **Implemented:** deterministic N-input/N-output typed recipe families. **Future:** bounded multi-step planning.

The current ledger is the Core's only live item/resource transaction domain. Persistence may still encode resolvable items in legacy segmented form so existing worlds remain readable, but load immediately migrates them into the same ledger and rejects duplicate dual representation.

## Verification gates

Every phase starts RED-first. The final phase gate remains:

```bash
./gradlew build
./gradlew runGameTestServer
./gradlew runRecipeAddonGameTestServer
./gradlew runMekanismGameTestServer
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts
./gradlew runData
```

GUI-visible resource work additionally requires the fullscreen Prism manual gate. Backend ledger work does not claim visual verification.

## API sources

- [NeoForge 1.21.1 `Capabilities`](https://github.com/neoforged/NeoForge/blob/1.21.x/src/main/java/net/neoforged/neoforge/capabilities/Capabilities.java)
- [NeoForge `IFluidHandler`](https://github.com/neoforged/NeoForge/blob/1.21.x/src/main/java/net/neoforged/neoforge/fluids/capability/IFluidHandler.java)
- [NeoForge `IEnergyStorage`](https://github.com/neoforged/NeoForge/blob/1.21.x/src/main/java/net/neoforged/neoforge/energy/IEnergyStorage.java)
- [Mekanism 1.21.1 `ChemicalStack`](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/api/java/mekanism/api/chemical/ChemicalStack.java)
- [Mekanism 1.21.1 `IChemicalHandler`](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/api/java/mekanism/api/chemical/IChemicalHandler.java)
