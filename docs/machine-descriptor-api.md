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
- Installable stacks persist by descriptor `ResourceLocation`, never by ordinal. If the item still exists but its descriptor is removed or changes its accepted ingredient, the stack moves into ordinary Core storage. If the addon item itself is unavailable, raw entries from both descriptor slots and ordinary Core storage remain server-persisted and are retried on later loads, so reinstalling the addon can restore them instead of the load cycle deleting them.
- Consumable amounts persist by descriptor ID. Live finite/infinite state is sent through `MachineDescriptorStatePacket`; it is not inferred from client inventory.
- Menu slot count always remains fixed at 256, so adding or removing addon descriptors cannot produce client/server container-index drift.
- This is registry-time addon registration, not runtime hot registration after NeoForge registries freeze. Any representative or accepted addon item must still exist on the client like other player-visible mod content.

Registering a descriptor only adds its installation/consumable behavior and Fuel-page presentation. Supporting a new recipe family still requires a server recipe adapter; it must not use client EMI state as authority.
