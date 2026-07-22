# Machine Descriptor API

Magic Storage exposes a NeoForge custom registry at `magic_storage:machine_descriptor` for addon-owned Fuel-page descriptors. Registration is performed during the normal mod registry lifecycle; descriptor order, persistence, consumable conversion, and live values remain server-owned.

## Register from an addon

```java
public static final ResourceLocation COPPER_PRESS_ID =
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "copper_press");

public static final DeferredRegister<MachineDescriptor> MACHINE_DESCRIPTORS =
        MachineDescriptorApi.createDeferredRegister(MOD_ID);

public static final DeferredHolder<MachineDescriptor, MachineDescriptor> COPPER_PRESS =
        MACHINE_DESCRIPTORS.register("copper_press", () -> MachineDescriptor.installable(
                COPPER_PRESS_ID,
                new ItemStack(ModItems.COPPER_PRESS.get()),
                Ingredient.of(ModItems.COPPER_PRESS.get()),
                MachineEnergyTable.Category.PROCESS,
                64,
                EnergyType.SMELTING_ENERGY,
                2));

public AddonMod(IEventBus modBus) {
    MACHINE_DESCRIPTORS.register(modBus);
}
```

The registry key and `MachineDescriptor.id()` must be identical. IDs are persistent data keys and must never be reused for a different machine. All installed descriptors share a fixed bank of 256 menu/Core slots; Magic Storage refuses to start if the combined registry exceeds that limit.

## Descriptor kinds

- `PROCESS`: installable stack, maximum 1–64. It may generate one existing `EnergyType`, or descriptor-keyed station work when `energyType` is `null`.
- `INSTANT`: installable stack, normally maximum 1. It unlocks an adapter-defined action without generating energy.
- `CONSUMABLE`: transient input. Its `ConsumableValue` callback runs only in the server-owned Core mutation path and returns a finite `long` amount or explicit infinity.

Example consumable:

```java
MachineDescriptor.consumable(
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "charged_crystal"),
        new ItemStack(ModItems.CHARGED_CRYSTAL.get()),
        Ingredient.of(ModItems.CHARGED_CRYSTAL.get()),
        stack -> new MachineDescriptor.ConsumableAmount(1_000L * stack.getCount(), false));
```

The callback must be deterministic from the supplied stack and must not mutate the world, player, or stack. Returning a negative value, or a nonzero amount together with `infinite=true`, is rejected.

## Polymorphic station variants

Use one logical descriptor when several concrete blocks satisfy the same recipe station. Each `MachineVariant` has one exact item identity and a normalized rational `MachineWorkRate`; variants in the same slot may run at different rates.

```java
MachineDescriptor.installableVariants(
        COPPER_PRESS_ID,
        () -> List.of(
                MachineVariant.of(
                        new ItemStack(ModItems.COPPER_PRESS.get()),
                        MachineWorkRate.ONE),
                MachineVariant.derived(
                        new ItemStack(ModItems.REINFORCED_PRESS.get()),
                        () -> MachineWorkRate.of(200, AddonConfig.pressTicks.get()))),
        MachineEnergyTable.Category.PROCESS,
        64,
        null);
```

The supplier is materialized after registry/config loading and synchronized as values; the client never executes it. Capturing a config holder is allowed, but reading its value during a DeferredRegister callback is not. Empty lists, more than 64 variants, duplicate items, zero-rate PROCESS variants, or nonzero INSTANT rates fail explicitly. Work generation keeps an exact fractional remainder per descriptor plus installed item/rate. Changing the concrete item or live configured rate discards only the incompatible remainder before applying the new rate.

The recipe preview always starts with the actually installed variant, then cycles the remaining synchronized variants in descriptor order every 40 client ticks. This index is display-only. Station availability, accumulated work, rate, recipe execution, and persistence remain server-owned.

## Server authority and compatibility

- The server freezes and caches the ordered registry snapshot after registration, then writes that exact snapshot into every Crafting or Remote Terminal menu payload. Core ticks reuse the same immutable list instead of rebuilding and sorting it.
- Installable stacks, descriptor-keyed station work, and fractional remainder persist by descriptor `ResourceLocation`, never by ordinal. If a descriptor is missing, changes category/ingredient, exceeds its current maximum, or its addon item is unavailable, the original machine/work-entry NBT remains in the server-owned Core storage record and is retried on later loads. Raw ordinary-inventory entries are preserved the same way; this early-development repository format does not migrate either case into another slot or silently delete it.
- Consumable amounts persist by descriptor ID. Live finite/infinite state is sent through `MachineDescriptorStatePacket`; it is not inferred from client inventory.
- Menu slot count always remains fixed at 256, so adding or removing addon descriptors cannot produce client/server container-index drift.
- This is registry-time addon registration, not runtime hot registration after NeoForge registries freeze. Any representative or accepted addon item must still exist on the client like other player-visible mod content.

Registering a descriptor only adds its installation/consumable behavior and Fuel-page presentation. A recipe from any namespace that resolves to one of Magic Storage's exact supported vanilla recipe classes automatically reuses that family's built-in station descriptor; no per-recipe or per-mod station registration is needed, although the player must still install the corresponding station in the system. Descriptors and recipe families are registered once per station/family, not once per recipe ID.

Current built-in optional integrations use this boundary in three ways: Iron Furnaces contributes supported furnace blocks and derives `200 / configuredCookTicks` live rates; Farmer's Delight registers one Cooking Pot descriptor/family; Mekanism registers Crusher, Enrichment Chamber, Energized Smelter, Combiner, and Pressurized Reaction Chamber descriptors/families. Their exact Modrinth CI artifacts are evidence only and are not player-facing version pins.

Custom exact classes/types use the public NeoForge `magic_storage:recipe_family` registry and bounded deterministic factories described in [`recipe-family-api.md`](recipe-family-api.md). `singleItemToItem` and `deterministicResources` map a complete family to a descriptor without exposing Core/player mutation authority. Client EMI state is never authoritative. External-machine processing patterns and asynchronous send-and-wait orchestration remain outside the product.
