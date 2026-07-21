# Typed Resource Storage Architecture

> Status: active implementation under GitHub [#9](https://github.com/swear01/Magic_Storage/issues/9). The universal non-item ledger, persistent raw keys, NeoForge fluid/energy capabilities, and optional Mekanism chemical capability are implemented. Existing items still use the legacy exact-`ItemKey` map; terminal listings, buses, the public addon kind registry, and mixed item/non-item crafting remain later phases.

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
- Existing `ItemKey` behavior remains the compatibility baseline while the Core moves to the universal ledger.

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

The future public registry is keyed by `ResourceLocation` and freezes during normal NeoForge mod loading. A complete resource-kind contract must define:

- canonical identity encoding and validation;
- amount unit and legal bounds;
- deterministic ordering;
- persistence and network codecs;
- server-side insertion/extraction bridge;
- client presentation data that does not expose authoritative storage state;
- missing-provider recovery behavior.

Registration does not grant Core/player mutation callbacks. Addons describe values and deterministic deltas; Magic Storage owns simulation, capacity planning, commit, rollback, persistence, and synchronization.

## Recipe-family relationship

GitHub [#1](https://github.com/swear01/Magic_Storage/issues/1) will replace the initial `singleItemToItem` factory with bounded deterministic N-input/N-output contracts over this ledger. Inputs, outputs, catalysts, tools, remainders, fluids, power, and chemicals must all become explicit transaction roles. Unknown chance, event, world, player, or asynchronous machine semantics remain unsupported.

## Delivery phases

1. **Implemented:** universal key, delta, ledger, persistence, simulation, and atomic commit algebra.
2. Item bridge and unchanged item persistence/GameTest behavior.
3. **Implemented:** fluid storage plus `IFluidHandler` capability and transfer tests.
4. **Implemented:** NeoForge Energy storage plus `IEnergyStorage` capability and separation tests.
5. **Implemented foundation:** optional Mekanism chemical capability, absent-mod base run, and one representative present-mod GameTest fixture. This is intentionally not a multi-version matrix; other-version incompatibilities are fixed from user reports rather than turning CI into a version guarantee.
6. Typed terminal listings, buses, creative infinite storage, and addon resource-kind API.
7. Deterministic N-input/N-output recipe families and later multi-step planning.

The current ledger can atomically validate and commit deltas spanning arbitrary stable kind IDs, including unknown provider IDs. It is not yet the Core's only transaction domain: item mutations still live in the legacy item map, so production crafting must not claim atomic item+fluid/power/chemical execution until phase 2 and the mixed transaction bridge are complete.

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
