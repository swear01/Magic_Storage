# Polymorphic Stations and Mod Recipes Implementation Plan

## Task 1: Lock public station-variant contracts — complete

1. Add RED compile/SelfTests for `MachineWorkRate`, `MachineVariant`, variant descriptor factories, validation, exact lookup, and client snapshot round-trip.
2. Run the focused compile/SelfTest path and confirm failures are missing API/behavior failures.
3. Implement the smallest immutable API and snapshot codec.
4. Re-run focused tests.

## Task 2: Add exact fractional Core generation — complete

1. Add RED GameTests for fractional accumulation, multiple installed blocks, replacement/rate-change reset, saturation, and save/reload.
2. Add descriptor-keyed work and remainder persistence without changing existing EnergyType IDs.
3. Route each PROCESS variant to either its legacy energy pool or descriptor work pool.
4. Re-run focused and persistence tests.

## Task 3: Add station-work recipe cost — complete

1. Add RED API/GameTests for station work plus optional Fuel and typed-resource atomicity.
2. Extend recipe cost, preview rows, maximum-craft calculation, simulate, commit, and rollback.
3. Keep the existing `EnergyCost` path unchanged for vanilla recipes.
4. Re-run crafting, Fuel, and menu parity suites.

## Task 4: Add preview variant cycling — complete

1. Add RED model/static tests for station descriptor metadata, deterministic variant ordering, actual-installed-first ordering, cycle wrap, and one-item stability.
2. Synchronize materialized variant snapshots and add client-only display selection.
3. Keep execution independent from the display index.
4. Record the GUI change as pending the batched fullscreen gate.

## Task 5: Integrate Iron Furnaces — complete

1. Add a representative Modrinth compile/runtime dependency and isolated fixture run.
2. Add RED absent/present tests and live-config rate assertions.
3. Load the direct typed compat class only when `ironfurnaces` is present.
4. Derive rates from active cook-time config; exclude multi-output special furnaces.
5. Add the fixture task to CI/release and run it alone.

## Task 6: Add the bounded multi-item family API — complete

1. Add RED different-package compile fixture and addon GameTests for 1..9 predicate/count item inputs, fixed item output, remainders, station work, and rollback.
2. Implement the public immutable input contract and factory through the existing adapter transaction engine.
3. Update `recipe-family-api.md` before moving to real mods.

## Task 7: Integrate Farmer's Delight — complete

1. Add representative runtime to the optional-mod fixture.
2. Add RED tests using real Cooking Pot recipes for ingredients, serving container, cook time, remainder, discovery, full destination, and reload.
3. Register one isolated Cooking Pot family and station descriptor.
4. Run the fixture and base absent-mod server.

## Task 8: Integrate Mekanism recipes — complete

1. Extend the existing representative Mekanism fixture RED tests for Crushing, Enriching, Smelting, Combining, and item-output Pressurized Reaction.
2. Register exact public recipe classes/types and station descriptors.
3. Use typed plans for Reaction's fluid/chemical inputs, exact `energyRequired × duration` FE input, duration work, and optional chemical output; reject chemical-only primary output.
4. Re-run all Mekanism chemical capability and recipe tests.

## Task 9: Finish docs and gates — complete

1. Update active overview, structure, roadmap, plan, notes, machine descriptor API, recipe family API, typed-resource behavior, and CI docs.
2. Update GitHub #1/#10 checklists only for verified acceptance criteria.
3. Run build, EMI minimum/latest compile, base GameTest, recipe-addon GameTest, optional-mod fixture, Mekanism fixture, Python, runData drift, and diff checks serially.
4. Commit and push checkpoints. Do not run or claim Prism visual verification yet.
