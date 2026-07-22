# Polymorphic Stations and First Mod Recipe Integrations

Date: 2026-07-23  
Tracking: GitHub #1 and #10

## User outcome

One logical workstation position can accept several concrete station blocks. The recipe preview rotates through those concrete blocks, but the server always derives execution speed from the exact installed stack. Faster and slower blocks in one family never collapse into one guessed value.

The same foundation supplies the first built-in compatibility modules for NeoForge 1.21.1 mods without starting or polling their external block entities.

## Research boundary

The initial source-audited targets are:

- Iron Furnaces 4.3.2 for vanilla-smelting station variants and live configurable cook speed;
- Farmer's Delight 1.3.2 Cooking Pot recipes;
- Mekanism 10.7.19 Crushing, Enriching, Smelting, Combining, and the item-output Pressurized Reaction subset.

The exact Modrinth artifact IDs used by CI are reproducibility evidence only. Player metadata does not pin any of these optional mods to those versions. A present but binary-incompatible mod fails with a named compatibility error; an absent mod does not load its compatibility class.

## Considered approaches

### A. One descriptor per concrete block

This reuses the current integer rate but creates many Fuel-page slots, makes every recipe choose one arbitrary descriptor, and cannot show that several stations satisfy the same recipe requirement. Rejected.

### B. One descriptor with a wider Ingredient and one shared rate

This keeps one slot but loses the concrete block's identity and produces incorrect speed whenever variants differ. Rejected.

### C. Logical station plus concrete variants

This is the selected design. A descriptor is the stable logical family. It owns a late-resolved, bounded list of exact variants. Each variant carries its own representative stack and normalized rational work rate. Persistence remains keyed by descriptor ID and exact installed stack.

## Station model

`MachineVariant` contains:

- one representative `ItemStack`, matched by exact item identity; station-stack components are intentionally not part of variant identity;
- a normalized positive `MachineWorkRate(numerator, denominator)` for PROCESS descriptors;
- zero work for INSTANT descriptors.

An installable descriptor exposes 1..64 active variants. Duplicate item identities, empty stacks, non-positive PROCESS rates, nonzero INSTANT rates, and oversized lists fail explicitly. The existing fixed-rate factory remains a convenience wrapper around one variant.

Variant suppliers are evaluated only after mod discovery/config loading and are materialized into the server menu snapshot. Client descriptors contain only the materialized variant list; they never execute addon callbacks.

The machine slot accepts a stack only when it matches an exact active variant. A descriptor may be registered but inactive when its optional mod is absent; inactive descriptors are omitted from the live ordered slot table, while persisted unknown entries remain unresolved and preserved.

## Exact rate accumulation

Core generation uses:

```text
totalNumerator = savedRemainder + installedCount * rate.numerator
wholeWork      = totalNumerator / rate.denominator
newRemainder   = totalNumerator % rate.denominator
```

The remainder record includes descriptor ID, installed item ID, numerator, and denominator. It survives save/reload. Replacing the installed variant or changing the live configured rate resets only that descriptor's incompatible remainder before applying the new rate. Addition saturates at `Long.MAX_VALUE`; arithmetic overflow is rejected or saturated at the same boundary as the existing energy path.

Built-in furnace descriptors keep their existing `EnergyType` pools for save compatibility. New third-party PROCESS descriptors use descriptor-keyed station-work pools, so adding future mods does not expand a fixed enum. Recipe costs can require station work, optional existing Fuel, and typed resources in one simulate-then-commit plan.

## Preview model

Recipe presentation metadata carries the station descriptor ID. The menu resolves the synchronized descriptor variants and returns them in deterministic order, with the actual installed variant first when available. The screen chooses the displayed index from client time and wraps without sending packets. A single variant never flickers. Tooltip and dim overlay use the same chosen index for the frame.

The animation is display-only. Recipe selection, station availability, work amount, and execution stay server-owned.

## Optional compatibility loading

The normal bootstrap contains no direct third-party types. During the mod constructor, before Magic Storage's deferred descriptor/family registers bind to the event bus, it checks `ModList` and reflectively invokes a named isolated compatibility registrar. That registrar is compile-only against one representative artifact.

Absent mod: no class load and no descriptor/family registration.  
Present supported mod: descriptors and families register once.  
Present incompatible mod: startup fails with the mod ID and compatibility module name; there is no silent fallback.

## Initial integrations

### Iron Furnaces

The existing furnace logical descriptor includes vanilla Furnace and normal Iron Furnaces furnace items. The compatibility module derives each configured cook time through the mod's live config surface and converts it to `200/configuredTicks`. It does not embed tier speed numbers. AllTheModium, Vibranium, and Unobtainium variants are excluded because their multi-output behavior is not equivalent to a faster vanilla furnace.

### Farmer's Delight Cooking Pot

The family consumes all 1..6 recipe ingredients. If `getOutputContainer()` is non-empty, one exact serving container is an additional consumed input. Crafting remainders return through the existing atomic delivery plan. The recipe's `cookTime` is descriptor-keyed station work; equivalent furnace Fuel is required for the same amount. The fixed result is delivered to Player or Storage according to the existing terminal setting.

### Mekanism

The initial item families are Crushing, Enriching, Smelting, and Combining. Sized item predicates and exact counts come from Mekanism's public ingredient API rather than `Recipe#getIngredients()` guesses.

Pressurized Reaction is supported only when its deterministic output includes an item, because current terminal recipe selection is item-primary. Its item, fluid, and chemical inputs and optional chemical co-output use one typed transaction; `duration` becomes station work and `energyRequired × duration` consumes stored NeoForge Energy because Mekanism applies that recipe value per processing tick. Chemical-only Reaction and pure fluid/chemical-output families remain visible roadmap work until terminal selection allows non-item primary outputs.

Chance outputs, per-tick chemical-use families, Cutting Board fortune/chance behavior, factories/upgrades encoded outside the installable item, and external-machine send-and-wait remain unsupported.

## Testing

RED-first coverage must include:

- rate normalization, bounds, duplicate item variants, item-identity matching, snapshot parity;
- fractional carry, save/reload, replacement/rate change reset, count multiplication, saturation;
- absent optional mods on the base dedicated server;
- Iron Furnaces variant discovery and live config-derived rates;
- preview deterministic order, wrap, and single-variant stability;
- Farmer's Delight ingredients/container/remainders/cook time/full destination rollback;
- Mekanism sized item inputs, two-item Combining, Reaction item+fluid+chemical+FE input, chemical co-output, missing resource, and rollback;
- stale recipe holders/reload and dedicated-server classloading.

CI loads one representative release per optional mod. Those versions do not become player compatibility constraints.

## Visual gate

The implementation changes recipe and Fuel-page rendering, so automated tests cannot approve the GUI. Per the user's 2026-07-23 instruction, Prism F11 fullscreen verification is deliberately accumulated with all remaining issue work and run once at the end. No intermediate commit may claim visual approval.
