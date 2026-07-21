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

- `PROCESS`: installable stack, maximum 1–64. It may generate one existing `EnergyType` at the declared per-item tick rate.
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

## Server authority and compatibility

- The server freezes and caches the ordered registry snapshot after registration, then writes that exact snapshot into every Crafting or Remote Terminal menu payload. Core ticks reuse the same immutable list instead of rebuilding and sorting it.
- Installable stacks persist by descriptor `ResourceLocation`, never by ordinal. If a descriptor is missing, changes category/ingredient, exceeds its current maximum, or its addon item is unavailable, the original machine-entry NBT remains in the server-owned Core storage record and is retried on later loads. Raw ordinary-inventory entries are preserved the same way; this early-development repository format does not migrate either case into another slot or silently delete it.
- Consumable amounts persist by descriptor ID. Live finite/infinite state is sent through `MachineDescriptorStatePacket`; it is not inferred from client inventory.
- Menu slot count always remains fixed at 256, so adding or removing addon descriptors cannot produce client/server container-index drift.
- This is registry-time addon registration, not runtime hot registration after NeoForge registries freeze. Any representative or accepted addon item must still exist on the client like other player-visible mod content.

Registering a descriptor only adds its installation/consumable behavior and Fuel-page presentation. A recipe from any namespace that resolves to one of Magic Storage's exact supported vanilla recipe classes automatically reuses that family's built-in station descriptor; no per-recipe or per-mod station registration is needed, although the player must still install the corresponding station in the system. Descriptors and recipe families are registered once per station/family, not once per recipe ID.

Custom exact classes/types use the public NeoForge `magic_storage:recipe_family` registry and bounded deterministic factories described in [`recipe-family-api.md`](recipe-family-api.md). The initial `singleItemToItem` factory maps one complete family to a descriptor without exposing Core/player mutation authority. Client EMI state is never authoritative. External-machine processing patterns and asynchronous send-and-wait orchestration remain outside the product.
