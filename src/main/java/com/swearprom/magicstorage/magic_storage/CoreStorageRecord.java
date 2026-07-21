package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class CoreStorageRecord {
    static final int MAX_SEGMENT_TYPES = 63;

    static final String TAG_STORAGE_ID = "storageId";
    static final String TAG_NETWORK_ID = "networkId";
    static final String TAG_ENERGY = "energy";
    static final String TAG_DESCRIPTOR_CONSUMABLES = "descriptorConsumables";
    static final String TAG_MACHINE_DESCRIPTORS = "machineDescriptors";
    static final String TAG_INVENTORY_SEGMENTS = "inventorySegments";
    static final String TAG_RESOURCE_LEDGER = "resourceLedger";
    static final String TAG_ENTRIES = "entries";
    static final String TAG_DESCRIPTOR_ID = "descriptorId";
    static final String TAG_ITEM = "item";
    static final String TAG_COUNT = "count";
    static final String TAG_AMOUNT = "amount";
    static final String TAG_INFINITE = "infinite";

    private final UUID storageId;
    private final UUID networkId;
    private final Map<EnergyType, Long> energy = new EnumMap<>(EnergyType.class);
    private final Map<ResourceLocation, Long> descriptorAmounts = new java.util.HashMap<>();
    private final Set<ResourceLocation> infiniteDescriptors = new java.util.HashSet<>();
    private final List<CompoundTag> unresolvedDescriptorEntries = new ArrayList<>();
    private final List<CompoundTag> unresolvedMachineEntries = new ArrayList<>();
    private StorageResourceLedger resourceLedger = new StorageResourceLedger();
    private final List<CompoundTag> unresolvedInventoryEntries = new ArrayList<>();
    private final SimpleContainer machines;

    private Runnable dirtyCallback = () -> {
    };
    private Runnable machineMutationCallback = this::markChanged;
    private boolean suppressMachineCallback;

    private CoreStorageRecord(UUID storageId, UUID networkId) {
        this.storageId = java.util.Objects.requireNonNull(storageId, "storageId");
        this.networkId = java.util.Objects.requireNonNull(networkId, "networkId");
        for (EnergyType type : EnergyType.values()) {
            energy.put(type, 0L);
        }
        machines = new SimpleContainer(MachineDescriptorApi.MAX_DESCRIPTORS) {
            @Override
            public int getMaxStackSize() {
                return 64;
            }

            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                MachineDescriptor descriptor = MachineEnergyTable.get(slot);
                return descriptor != null
                        && descriptor.category() != MachineEnergyTable.Category.CONSUMABLE
                        && descriptor.maxInstalledCount() > 0
                        && descriptor.accepts(stack);
            }

            @Override
            public void setChanged() {
                super.setChanged();
                if (!suppressMachineCallback) {
                    machineMutationCallback.run();
                }
            }
        };
    }

    static CoreStorageRecord fresh(UUID storageId) {
        return new CoreStorageRecord(storageId, UUID.randomUUID());
    }

    static LoadResult load(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag raw = tag.copy();
        UUID storageId = tag.hasUUID(TAG_STORAGE_ID) ? tag.getUUID(TAG_STORAGE_ID) : null;
        if (storageId == null) {
            return LoadResult.failure(null, raw, "missing storageId");
        }
        if (!tag.hasUUID(TAG_NETWORK_ID)) {
            return LoadResult.failure(storageId, raw, "missing networkId");
        }
        if (!tag.contains(TAG_ENERGY, Tag.TAG_COMPOUND)
                || !tag.contains(TAG_DESCRIPTOR_CONSUMABLES, Tag.TAG_LIST)
                || !tag.contains(TAG_MACHINE_DESCRIPTORS, Tag.TAG_LIST)
                || !tag.contains(TAG_INVENTORY_SEGMENTS, Tag.TAG_LIST)) {
            return LoadResult.failure(storageId, raw, "missing mandatory record payload");
        }
        ListTag descriptorEntries = compoundList(tag, TAG_DESCRIPTOR_CONSUMABLES);
        ListTag machineEntries = compoundList(tag, TAG_MACHINE_DESCRIPTORS);
        ListTag inventorySegments = compoundList(tag, TAG_INVENTORY_SEGMENTS);
        if (descriptorEntries == null || machineEntries == null || inventorySegments == null) {
            return LoadResult.failure(storageId, raw, "mandatory record list has non-compound elements");
        }

        CoreStorageRecord record = new CoreStorageRecord(storageId, tag.getUUID(TAG_NETWORK_ID));
        try {
            String energyError = record.loadEnergy(tag.getCompound(TAG_ENERGY));
            if (energyError != null) {
                return LoadResult.failure(storageId, raw, energyError);
            }
            record.loadDescriptorEntries(descriptorEntries);
            record.loadMachineEntries(machineEntries, registries);
            if (tag.contains(TAG_RESOURCE_LEDGER)) {
                if (!tag.contains(TAG_RESOURCE_LEDGER, Tag.TAG_COMPOUND)) {
                    return LoadResult.failure(storageId, raw, "typed resource ledger is not a compound");
                }
                record.resourceLedger = StorageResourceLedger.load(
                        tag.getCompound(TAG_RESOURCE_LEDGER));
            }
            String inventoryError = record.loadInventorySegments(inventorySegments, registries);
            if (inventoryError != null) {
                return LoadResult.failure(storageId, raw, inventoryError);
            }
            return LoadResult.success(record);
        } catch (RuntimeException exception) {
            return LoadResult.failure(storageId, raw,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_STORAGE_ID, storageId);
        tag.putUUID(TAG_NETWORK_ID, networkId);

        CompoundTag energyTag = new CompoundTag();
        for (EnergyType type : EnergyType.values()) {
            energyTag.putLong(type.getId(), Math.max(0, energy.getOrDefault(type, 0L)));
        }
        tag.put(TAG_ENERGY, energyTag);

        ListTag descriptorTags = new ListTag();
        Set<ResourceLocation> descriptorIds = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        descriptorIds.addAll(descriptorAmounts.keySet());
        descriptorIds.addAll(infiniteDescriptors);
        for (ResourceLocation descriptorId : descriptorIds) {
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_DESCRIPTOR_ID, descriptorId.toString());
            entry.putLong(TAG_AMOUNT, Math.max(0, descriptorAmounts.getOrDefault(descriptorId, 0L)));
            entry.putBoolean(TAG_INFINITE, infiniteDescriptors.contains(descriptorId));
            descriptorTags.add(entry);
        }
        unresolvedDescriptorEntries.forEach(entry -> descriptorTags.add(entry.copy()));
        tag.put(TAG_DESCRIPTOR_CONSUMABLES, descriptorTags);

        ListTag machineTags = new ListTag();
        List<MachineDescriptor> descriptors = MachineEnergyTable.entries();
        for (int slot = 0; slot < descriptors.size(); slot++) {
            ItemStack stack = machines.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_DESCRIPTOR_ID, descriptors.get(slot).id().toString());
            entry.put(TAG_ITEM, stack.save(registries));
            machineTags.add(entry);
        }
        unresolvedMachineEntries.forEach(entry -> machineTags.add(entry.copy()));
        tag.put(TAG_MACHINE_DESCRIPTORS, machineTags);

        List<CompoundTag> inventoryEntries = new ArrayList<>();
        Set<StorageResourceKey> persistedAsInventory = new HashSet<>();
        for (Map.Entry<StorageResourceKey, Long> entry : resourceLedger.snapshot().entrySet()) {
            if (!entry.getKey().kindId().equals(StorageResourceBridge.ITEM_KIND)) continue;
            var itemKey = StorageResourceBridge.itemKey(entry.getKey(), registries);
            if (itemKey.isEmpty()) continue;
            CompoundTag inventoryEntry = new CompoundTag();
            inventoryEntry.put(TAG_ITEM, itemKey.get().toStack(1).save(registries));
            inventoryEntry.putLong(TAG_COUNT, entry.getValue());
            inventoryEntries.add(inventoryEntry);
            persistedAsInventory.add(entry.getKey());
        }
        inventoryEntries.sort(Comparator
                .comparing((CompoundTag entry) -> entry.getCompound(TAG_ITEM).getString("id"))
                .thenComparing(entry -> entry.getCompound(TAG_ITEM).toString())
                .thenComparingLong(entry -> entry.getLong(TAG_COUNT)));
        unresolvedInventoryEntries.forEach(entry -> inventoryEntries.add(entry.copy()));

        ListTag segments = new ListTag();
        for (int start = 0; start < inventoryEntries.size(); start += MAX_SEGMENT_TYPES) {
            ListTag entries = new ListTag();
            int end = Math.min(inventoryEntries.size(), start + MAX_SEGMENT_TYPES);
            for (int index = start; index < end; index++) {
                entries.add(inventoryEntries.get(index));
            }
            CompoundTag segment = new CompoundTag();
            segment.put(TAG_ENTRIES, entries);
            segments.add(segment);
        }
        tag.put(TAG_INVENTORY_SEGMENTS, segments);
        tag.put(TAG_RESOURCE_LEDGER, resourceLedger.save(
                key -> !persistedAsInventory.contains(key)));
        return tag;
    }

    private String loadEnergy(CompoundTag tag) {
        for (EnergyType type : EnergyType.values()) {
            if (!tag.contains(type.getId(), Tag.TAG_LONG) || tag.getLong(type.getId()) < 0) {
                return "invalid energy field " + type.getId();
            }
            energy.put(type, tag.getLong(type.getId()));
        }
        return null;
    }

    private void loadDescriptorEntries(ListTag entries) {
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            ResourceLocation descriptorId = ResourceLocation.tryParse(entry.getString(TAG_DESCRIPTOR_ID));
            MachineDescriptor descriptor = descriptorId == null ? null : MachineEnergyTable.get(descriptorId);
            if (descriptor == null || descriptor.category() != MachineEnergyTable.Category.CONSUMABLE
                    || !entry.contains(TAG_AMOUNT, Tag.TAG_LONG)
                    || !entry.contains(TAG_INFINITE, Tag.TAG_BYTE)
                    || entry.getLong(TAG_AMOUNT) < 0
                    || entry.getByte(TAG_INFINITE) != 0 && entry.getByte(TAG_INFINITE) != 1) {
                unresolvedDescriptorEntries.add(entry.copy());
                continue;
            }
            if (entry.getBoolean(TAG_INFINITE)) {
                infiniteDescriptors.add(descriptorId);
                descriptorAmounts.remove(descriptorId);
                continue;
            }
            long amount = entry.getLong(TAG_AMOUNT);
            if (amount <= 0) {
                continue;
            }
            long current = descriptorAmounts.getOrDefault(descriptorId, 0L);
            descriptorAmounts.put(descriptorId, saturatingAdd(current, amount));
        }
    }

    private void loadMachineEntries(ListTag entries, HolderLookup.Provider registries) {
        suppressMachineCallback = true;
        try {
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = entries.getCompound(index);
                if (!entry.contains(TAG_ITEM, Tag.TAG_COMPOUND)) {
                    unresolvedMachineEntries.add(entry.copy());
                    continue;
                }
                ResourceLocation descriptorId = ResourceLocation.tryParse(entry.getString(TAG_DESCRIPTOR_ID));
                MachineDescriptor descriptor = descriptorId == null ? null : MachineEnergyTable.get(descriptorId);
                ItemStack stack = parsePersistedItem(entry.getCompound(TAG_ITEM), registries);
                int slot = descriptorId == null ? -1 : MachineEnergyTable.findSlot(descriptorId);
                if (descriptor == null || descriptor.category() == MachineEnergyTable.Category.CONSUMABLE
                        || stack.isEmpty() || slot < 0 || !descriptor.accepts(stack)) {
                    unresolvedMachineEntries.add(entry.copy());
                    continue;
                }

                ItemStack existing = machines.getItem(slot);
                int room = Math.max(0, descriptor.maxInstalledCount() - existing.getCount());
                int accepted = Math.min(room, stack.getCount());
                if (accepted > 0) {
                    machines.setItem(slot, stack.copyWithCount(existing.getCount() + accepted));
                }
                if (accepted < stack.getCount()) {
                    CompoundTag remainder = entry.copy();
                    remainder.put(TAG_ITEM, stack.copyWithCount(stack.getCount() - accepted).save(registries));
                    unresolvedMachineEntries.add(remainder);
                }
            }
        } finally {
            suppressMachineCallback = false;
        }
    }

    private String loadInventorySegments(ListTag segments, HolderLookup.Provider registries) {
        Set<StorageResourceKey> loadedLegacyKeys = new HashSet<>();
        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            CompoundTag segment = segments.getCompound(segmentIndex);
            if (!segment.contains(TAG_ENTRIES, Tag.TAG_LIST)) {
                return "inventory segment " + segmentIndex + " has no entries list";
            }
            ListTag entries = compoundList(segment, TAG_ENTRIES);
            if (entries == null) {
                return "inventory segment " + segmentIndex + " entries have non-compound elements";
            }
            if (entries.size() > MAX_SEGMENT_TYPES) {
                return "inventory segment " + segmentIndex + " exceeds " + MAX_SEGMENT_TYPES + " types";
            }
            for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                CompoundTag entry = entries.getCompound(entryIndex);
                if (!entry.contains(TAG_ITEM, Tag.TAG_COMPOUND)) {
                    return "inventory entry has no item";
                }
                if (!entry.contains(TAG_COUNT, Tag.TAG_LONG)) {
                    return "inventory entry count is not a long";
                }
                long count = entry.getLong(TAG_COUNT);
                if (count <= 0) {
                    return "inventory entry has non-positive count";
                }
                ItemStack stack = parsePersistedItem(entry.getCompound(TAG_ITEM), registries);
                if (stack.isEmpty()) {
                    unresolvedInventoryEntries.add(entry.copy());
                    continue;
                }
                StorageResourceKey key = StorageResourceBridge.itemKey(
                        ItemKey.of(stack), registries);
                if (resourceLedger.amount(key) > 0 && !loadedLegacyKeys.contains(key)) {
                    return "item exists in both inventory segments and typed resource ledger";
                }
                putItemInternal(key, count);
                loadedLegacyKeys.add(key);
            }
        }
        return null;
    }

    private static ListTag compoundList(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list)) {
            return null;
        }
        return list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND ? list : null;
    }

    private static ItemStack parsePersistedItem(
            CompoundTag itemTag,
            HolderLookup.Provider registries
    ) {
        ResourceLocation itemId = ResourceLocation.tryParse(itemTag.getString("id"));
        if (itemId == null || registries.lookupOrThrow(Registries.ITEM)
                .get(ResourceKey.create(Registries.ITEM, itemId)).isEmpty()) {
            return ItemStack.EMPTY;
        }
        return ItemStack.parse(registries, itemTag).orElse(ItemStack.EMPTY);
    }

    UUID storageId() {
        return storageId;
    }

    UUID networkId() {
        return networkId;
    }

    Map<EnergyType, Long> energy() {
        return energy;
    }

    SimpleContainer machines() {
        return machines;
    }

    Map<ResourceLocation, Long> descriptorAmounts() {
        return descriptorAmounts;
    }

    Set<ResourceLocation> infiniteDescriptors() {
        return infiniteDescriptors;
    }

    List<CompoundTag> unresolvedDescriptorEntries() {
        return unresolvedDescriptorEntries;
    }

    List<CompoundTag> unresolvedMachineEntries() {
        return unresolvedMachineEntries;
    }

    StorageResourceLedger resourceLedger() {
        return resourceLedger;
    }

    List<CompoundTag> unresolvedInventoryEntries() {
        return unresolvedInventoryEntries;
    }

    int typeCount() {
        long count = unresolvedInventoryEntries.size() + (long) resourceLedger.typeCount();
        if (count >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) count;
    }

    long itemCount() {
        long total = 0;
        for (Map.Entry<StorageResourceKey, Long> entry : resourceLedger.snapshot().entrySet()) {
            if (entry.getKey().kindId().equals(StorageResourceBridge.ITEM_KIND)) {
                total = saturatingAdd(total, entry.getValue());
            }
        }
        for (CompoundTag unresolved : unresolvedInventoryEntries) {
            total = saturatingAdd(total, Math.max(0, unresolved.getLong(TAG_COUNT)));
        }
        return total;
    }

    long getItemCount(ItemKey key, HolderLookup.Provider registries) {
        return resourceLedger.amount(StorageResourceBridge.itemKey(key, registries));
    }

    void putItem(ItemKey key, long amount, HolderLookup.Provider registries) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Core storage amount must be positive");
        }
        putItemInternal(StorageResourceBridge.itemKey(key, registries), amount);
        markChanged();
    }

    private void putItemInternal(StorageResourceKey key, long amount) {
        resourceLedger.insert(
                key, amount, StorageTypeCapacity.unlimitedCapacity(), Action.EXECUTE);
    }

    boolean isEmpty() {
        if (!resourceLedger.isEmpty()
                || !machines.isEmpty() || !descriptorAmounts.isEmpty()
                || !infiniteDescriptors.isEmpty() || !unresolvedDescriptorEntries.isEmpty()
                || !unresolvedMachineEntries.isEmpty() || !unresolvedInventoryEntries.isEmpty()) {
            return false;
        }
        for (long amount : energy.values()) {
            if (amount > 0) {
                return false;
            }
        }
        return true;
    }

    void setDirtyCallback(Runnable callback) {
        dirtyCallback = java.util.Objects.requireNonNull(callback, "callback");
    }

    void setMachineMutationCallback(Runnable callback) {
        machineMutationCallback = java.util.Objects.requireNonNull(callback, "callback");
    }

    void clearMachineMutationCallback() {
        machineMutationCallback = this::markChanged;
    }

    void markChanged() {
        dirtyCallback.run();
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) {
            return left;
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    record LoadResult(CoreStorageRecord record, UUID storageId, CompoundTag raw, String error) {
        static LoadResult success(CoreStorageRecord record) {
            return new LoadResult(record, record.storageId(), null, null);
        }

        static LoadResult failure(UUID storageId, CompoundTag raw, String error) {
            return new LoadResult(null, storageId, raw.copy(), error);
        }

        boolean success() {
            return record != null;
        }
    }
}
