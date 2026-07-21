package com.swearprom.magicstorage.magic_storage;

import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class MekanismChemicalCompat {
    private static final ResourceLocation CHEMICAL_KIND = StorageResourceKindApi.CHEMICAL_KIND;
    private static final BlockCapability<IChemicalHandler, Direction> CHEMICAL_CAPABILITY =
            BlockCapability.createSided(
                    ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"),
                    IChemicalHandler.class);
    private static final Map<Object, WeakReference<IChemicalHandler>> HANDLERS =
            new WeakHashMap<>();

    private MekanismChemicalCompat() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                CHEMICAL_CAPABILITY,
                MagicStorage.STORAGE_CORE_BE.get(),
                (core, side) -> core.getLevel() == null || core.getLevel().isClientSide()
                        ? null : handler(core, core.resourceHandler()));
        event.registerBlockEntity(
                CHEMICAL_CAPABILITY,
                MagicStorage.IMPORT_BUS_BE.get(),
                (bus, side) -> bus.getLevel() == null || bus.getLevel().isClientSide()
                        ? null : handler(bus, bus.passiveResourceHandler()));
        event.registerBlockEntity(
                CHEMICAL_CAPABILITY,
                MagicStorage.EXPORT_BUS_BE.get(),
                (bus, side) -> bus.getLevel() == null || bus.getLevel().isClientSide()
                        ? null : handler(bus, bus.passiveResourceHandler()));
    }

    private static synchronized IChemicalHandler handler(
            Object owner,
            StorageResourceHandler resources
    ) {
        WeakReference<IChemicalHandler> reference = HANDLERS.get(owner);
        IChemicalHandler existing = reference == null ? null : reference.get();
        if (existing != null) return existing;
        IChemicalHandler created = new ResourceChemicalHandler(resources);
        HANDLERS.put(owner, new WeakReference<>(created));
        return created;
    }

    private static StorageResourceKey key(ChemicalStack stack) {
        ResourceLocation chemicalId = stack.getChemicalHolder().unwrapKey()
                .orElseThrow(() -> new IllegalArgumentException("Unregistered chemical cannot be stored"))
                .location();
        return StorageResourceKey.of(CHEMICAL_KIND, chemicalId, new CompoundTag());
    }

    private static ChemicalStack stack(StorageResourceKey key, long amount) {
        if (!key.kindId().equals(CHEMICAL_KIND) || amount <= 0) return ChemicalStack.EMPTY;
        return MekanismAPI.CHEMICAL_REGISTRY.getHolder(ResourceKey.create(
                        MekanismAPI.CHEMICAL_REGISTRY_NAME, key.resourceId()))
                .map(holder -> new ChemicalStack(holder, amount))
                .orElse(ChemicalStack.EMPTY);
    }

    private static final class ResourceChemicalHandler implements IChemicalHandler {
        private final StorageResourceHandler resources;

        private ResourceChemicalHandler(StorageResourceHandler resources) {
            this.resources = resources;
        }

        @Override
        public int getChemicalTanks() {
            return Math.max(1, availableKeys().size());
        }

        @Override
        public ChemicalStack getChemicalInTank(int tank) {
            List<StorageResourceKey> keys = availableKeys();
            if (tank < 0 || tank >= keys.size()) return ChemicalStack.EMPTY;
            StorageResourceKey key = keys.get(tank);
            return stack(key, resources.getAmount(key));
        }

        @Override
        public void setChemicalInTank(int tank, ChemicalStack chemicalStack) {
            throw new UnsupportedOperationException("Magic Storage chemical tanks are transactional");
        }

        @Override
        public long getChemicalTankCapacity(int tank) {
            return tank >= 0 && tank < getChemicalTanks() ? Long.MAX_VALUE : 0;
        }

        @Override
        public boolean isValid(int tank, ChemicalStack chemicalStack) {
            return tank >= 0 && tank < getChemicalTanks() && !chemicalStack.isEmpty();
        }

        @Override
        public ChemicalStack insertChemical(
                int tank,
                ChemicalStack chemicalStack,
                Action action
        ) {
            if (!isValid(tank, chemicalStack)) return chemicalStack.copy();
            return insertChemical(chemicalStack, action);
        }

        @Override
        public ChemicalStack insertChemical(ChemicalStack chemicalStack, Action action) {
            if (chemicalStack.isEmpty()) return ChemicalStack.EMPTY;
            long inserted = resources.insert(
                    key(chemicalStack),
                    chemicalStack.getAmount(),
                    action.simulate());
            long remaining = chemicalStack.getAmount() - inserted;
            return remaining == 0 ? ChemicalStack.EMPTY : chemicalStack.copyWithAmount(remaining);
        }

        @Override
        public ChemicalStack extractChemical(int tank, long amount, Action action) {
            List<StorageResourceKey> keys = availableKeys();
            if (amount <= 0 || tank < 0 || tank >= keys.size()) return ChemicalStack.EMPTY;
            StorageResourceKey key = keys.get(tank);
            long extracted = resources.extract(
                    key,
                    amount,
                    action.simulate());
            return stack(key, extracted);
        }

        private List<StorageResourceKey> availableKeys() {
            return resources.getStoredResources().stream()
                    .filter(key -> key.kindId().equals(CHEMICAL_KIND))
                    .filter(key -> !stack(key, 1).isEmpty())
                    .sorted()
                    .toList();
        }
    }
}
