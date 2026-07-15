package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.*;
import java.util.function.Predicate;

public class StorageCoreBlockEntity extends BlockEntity {

    private static final String TAG_NETWORK_ID = "networkId";
    private static final String TAG_MACHINES = "machines";
    private static final String TAG_MACHINE_DESCRIPTORS = "machineDescriptors";
    private static final String TAG_DESCRIPTOR_CONSUMABLES = "descriptorConsumables";
    private static final String TAG_AXE_ENERGY = "axeEnergy";
    private static final String TAG_INFINITE_AXE_ENERGY = "infiniteAxeEnergy";
    private static final String LEGACY_BOTTLE_FUEL_ID = "bottle_fuel";

    private final Set<BlockPos> connectedBlocks = new HashSet<>();
    private UUID networkId = UUID.randomUUID();
    private boolean conflicted = false;
    private long topologyRevision;
    private int totalTypeSlots = 0;
    private int typeCount = 0;
    private long machineRevision;
    private final Map<ResourceLocation, Long> descriptorAmounts = new HashMap<>();
    private final Set<ResourceLocation> infiniteDescriptors = new HashSet<>();
    private final List<CompoundTag> unresolvedMachineDescriptors = new ArrayList<>();
    private final List<CompoundTag> unresolvedInventoryEntries = new ArrayList<>();
    private long legacyBottleFuel;
    private UUID preparedRecoveryId;

    // Energy
    private final Map<EnergyType, Long> energy = new HashMap<>();
    private final SimpleContainer machines = new SimpleContainer(MachineDescriptorApi.MAX_DESCRIPTORS) {
        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            MachineDescriptor descriptor = MachineEnergyTable.get(slot);
            return descriptor != null && descriptor.maxInstalledCount() > 0 && descriptor.accepts(stack);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            machineRevision++;
            StorageCoreBlockEntity.this.setChanged();
        }
    };

    // Two-tier inventory index
    private final Map<Item, Object2LongOpenHashMap<ItemKey>> inventory = new IdentityHashMap<>();
    private final Map<ItemKey, Long> flatCache = new HashMap<>();
    private final List<StorageListener> listeners = new ArrayList<>();
    private final List<Runnable> deferredListenerEvents = new ArrayList<>();
    private int mutationBatchDepth;
    private boolean cacheDirty = true;

    public StorageCoreBlockEntity(BlockPos pos, BlockState state) {
        super(MagicStorage.STORAGE_CORE_BE.get(), pos, state);
        for (EnergyType type : EnergyType.values()) {
            energy.put(type, 0L);
        }
    }

    public void tick() {
        if (level == null || level.isClientSide() || conflicted) return;
        boolean changed = false;
        List<MachineDescriptor> descriptors = MachineEnergyTable.entries();
        for (int slot = 0; slot < descriptors.size(); slot++) {
            MachineDescriptor entry = MachineEnergyTable.get(slot);
            ItemStack machineStack = machines.getItem(slot);
            if (entry == null || !entry.generatesEnergy() || !entry.accepts(machineStack)) continue;
            long increment = (long) machineStack.getCount() * entry.energyPerTick();
            long current = getEnergy(entry.energyType());
            long updated = current > Long.MAX_VALUE - increment ? Long.MAX_VALUE : current + increment;
            if (updated != current) {
                energy.put(entry.energyType(), updated);
                fireEnergyChanged(entry.energyType(), updated);
                changed = true;
            }
        }
        if (changed) setChanged();
    }

    Container getMachineContainer() {
        return machines;
    }

    public long getMachineRevision() {
        return machineRevision;
    }

    public long getAxeEnergy() {
        return getDescriptorAmount(MachineEnergyTable.AXE_ID);
    }

    public boolean hasInfiniteAxeEnergy() {
        return hasInfiniteDescriptor(MachineEnergyTable.AXE_ID);
    }

    public boolean hasAxeEnergy(long amount) {
        return hasDescriptorAmount(MachineEnergyTable.AXE_ID, amount);
    }

    public boolean canAddAxeEnergy(ItemStack stack) {
        return canAddDescriptorConsumable(MachineEnergyTable.AXE_ID, stack);
    }

    public boolean addAxeEnergy(ItemStack stack) {
        return addDescriptorConsumable(MachineEnergyTable.AXE_ID, stack);
    }

    public boolean consumeAxeEnergy(long amount) {
        return consumeDescriptor(MachineEnergyTable.AXE_ID, amount);
    }

    public long getDescriptorAmount(ResourceLocation descriptorId) {
        return descriptorAmounts.getOrDefault(descriptorId, 0L);
    }

    public boolean hasInfiniteDescriptor(ResourceLocation descriptorId) {
        return infiniteDescriptors.contains(descriptorId);
    }

    public boolean hasDescriptorAmount(ResourceLocation descriptorId, long amount) {
        return amount > 0 && (hasInfiniteDescriptor(descriptorId)
                || getDescriptorAmount(descriptorId) >= amount);
    }

    public boolean canAddDescriptorConsumable(ResourceLocation descriptorId, ItemStack stack) {
        if (stack.isEmpty() || conflicted || hasInfiniteDescriptor(descriptorId)) return false;
        MachineDescriptor descriptor = MachineEnergyTable.get(descriptorId);
        if (descriptor == null || descriptor.category() != MachineEnergyTable.Category.CONSUMABLE
                || !descriptor.accepts(stack)) return false;
        MachineDescriptor.ConsumableAmount value = descriptor.valueOf(stack);
        return value.infinite() || value.amount() > 0
                && getDescriptorAmount(descriptorId) <= Long.MAX_VALUE - value.amount();
    }

    public boolean addDescriptorConsumable(ResourceLocation descriptorId, ItemStack stack) {
        if (!canAddDescriptorConsumable(descriptorId, stack)) return false;
        MachineDescriptor.ConsumableAmount value = MachineEnergyTable.get(descriptorId).valueOf(stack);
        if (value.infinite()) {
            descriptorAmounts.remove(descriptorId);
            infiniteDescriptors.add(descriptorId);
        } else {
            descriptorAmounts.merge(descriptorId, value.amount(), Math::addExact);
        }
        stack.setCount(0);
        machineRevision++;
        setChanged();
        return true;
    }

    public boolean consumeDescriptor(ResourceLocation descriptorId, long amount) {
        if (!hasDescriptorAmount(descriptorId, amount)) return false;
        if (!hasInfiniteDescriptor(descriptorId)) {
            long remaining = getDescriptorAmount(descriptorId) - amount;
            if (remaining == 0) descriptorAmounts.remove(descriptorId);
            else descriptorAmounts.put(descriptorId, remaining);
        }
        machineRevision++;
        setChanged();
        return true;
    }

    public long getEnergy(EnergyType type) {
        return energy.getOrDefault(type, 0L);
    }

    public boolean consumeEnergy(EnergyCost cost, long multiplier) {
        if (multiplier <= 0) return false;
        long processNeed;
        long fuelNeed;
        try {
            processNeed = Math.multiplyExact(cost.processAmount(), multiplier);
            fuelNeed = Math.multiplyExact(cost.fuelAmount(), multiplier);
        } catch (ArithmeticException e) {
            return false;
        }
        if (getEnergy(cost.processType()) < processNeed) return false;
        if (getEnergy(cost.fuelType()) < fuelNeed) return false;
        energy.merge(cost.processType(), -processNeed, Long::sum);
        energy.merge(cost.fuelType(), -fuelNeed, Long::sum);
        fireEnergyChanged(cost.processType(), getEnergy(cost.processType()));
        if (cost.fuelType() != cost.processType()) {
            fireEnergyChanged(cost.fuelType(), getEnergy(cost.fuelType()));
        }
        setChanged();
        return true;
    }

    public boolean addFuel(ItemStack stack, EnergyType targetPool) {
        if (stack.isEmpty() || conflicted) return false;
        List<FuelValue> values = FuelTable.getFuelValues(stack);
        for (FuelValue fv : values) {
            if (fv.pool() == targetPool) {
                long amount;
                try {
                    amount = Math.multiplyExact(fv.valuePerItem(), (long) stack.getCount());
                } catch (ArithmeticException e) {
                    return false;
                }
                long current = getEnergy(targetPool);
                if (amount <= 0 || current > Long.MAX_VALUE - amount) return false;
                energy.put(targetPool, current + amount);
                stack.setCount(0);
                fireEnergyChanged(targetPool, current + amount);
                setChanged();
                return true;
            }
        }
        return false;
    }

    public boolean isFuel(ItemStack stack) {
        return FuelTable.isFuel(stack);
    }

    public List<FuelValue> getCompatiblePools(ItemStack stack) {
        return FuelTable.getFuelValues(stack);
    }

    public int getTypeCount() {
        return typeCount;
    }

    public boolean isConflicted() {
        return conflicted;
    }

    public UUID getNetworkId() {
        return networkId;
    }

    public long getTotalStoredItemCount() {
        long total = 0;
        for (var variants : inventory.values()) {
            for (var entry : variants.object2LongEntrySet()) {
                long amount = entry.getLongValue();
                if (total > Long.MAX_VALUE - amount) return Long.MAX_VALUE;
                total += amount;
            }
        }
        for (CompoundTag unresolved : unresolvedInventoryEntries) {
            long amount = Math.max(0, unresolved.getLong("count"));
            if (total > Long.MAX_VALUE - amount) return Long.MAX_VALUE;
            total += amount;
        }
        return total;
    }

    public boolean hasRecoverableContents() {
        if (!inventory.isEmpty() || !machines.isEmpty() || !descriptorAmounts.isEmpty()
                || !infiniteDescriptors.isEmpty()
                || !unresolvedMachineDescriptors.isEmpty()
                || !unresolvedInventoryEntries.isEmpty()
                || legacyBottleFuel > 0) return true;
        for (long amount : energy.values()) {
            if (amount > 0) return true;
        }
        return false;
    }

    UUID prepareRecoveryDrop(ServerLevel serverLevel, UUID owner) {
        if (preparedRecoveryId != null) return preparedRecoveryId;
        CompoundTag contents = new CompoundTag();
        saveAdditional(contents, serverLevel.registryAccess());
        preparedRecoveryId = CoreRecoverySavedData.get(serverLevel).create(
                contents,
                owner,
                typeCount,
                getTotalStoredItemCount(),
                serverLevel.getGameTime());
        return preparedRecoveryId;
    }

    Optional<UUID> getPreparedRecoveryId() {
        return Optional.ofNullable(preparedRecoveryId);
    }

    private void rebuildCache() {
        if (!cacheDirty) return;
        flatCache.clear();
        for (var entry : inventory.entrySet()) {
            var variantMap = entry.getValue();
            for (var variantEntry : variantMap.object2LongEntrySet()) {
                flatCache.merge(variantEntry.getKey(), variantEntry.getLongValue(), Long::sum);
            }
        }
        cacheDirty = false;
    }

    public void addListener(StorageListener listener) {
        listeners.add(listener);
    }

    public void removeListener(StorageListener listener) {
        listeners.remove(listener);
    }

    private void fireChanged(ItemKey key, long delta, long newAmount, Actor actor) {
        if (mutationBatchDepth > 0) {
            deferredListenerEvents.add(() -> notifyChanged(key, delta, newAmount, actor));
            return;
        }
        notifyChanged(key, delta, newAmount, actor);
    }

    private void notifyChanged(ItemKey key, long delta, long newAmount, Actor actor) {
        for (StorageListener listener : List.copyOf(listeners)) {
            listener.onChanged(key, delta, newAmount, actor);
        }
    }

    private void fireEnergyChanged(EnergyType type, long newAmount) {
        if (mutationBatchDepth > 0) {
            deferredListenerEvents.add(() -> notifyEnergyChanged(type, newAmount));
            return;
        }
        notifyEnergyChanged(type, newAmount);
    }

    private void notifyEnergyChanged(EnergyType type, long newAmount) {
        for (StorageListener listener : List.copyOf(listeners)) {
            listener.onEnergyChanged(type, newAmount);
        }
    }

    void beginMutationBatch() {
        mutationBatchDepth++;
    }

    void endMutationBatch() {
        if (mutationBatchDepth <= 0) throw new IllegalStateException("No active storage mutation batch");
        if (mutationBatchDepth == 1) recoverLegacyBottleFuel();
        mutationBatchDepth--;
        if (mutationBatchDepth > 0) return;
        List<Runnable> events = List.copyOf(deferredListenerEvents);
        deferredListenerEvents.clear();
        for (Runnable event : events) event.run();
    }

    public long insertItem(ItemStack stack, Action action, Actor actor) {
        if (stack.isEmpty() || conflicted) return 0;
        ItemKey key = ItemKey.of(stack);
        long inserted = insertItemCount(key, stack.getCount(), action, actor);
        if (action == Action.EXECUTE && inserted > 0) stack.shrink((int) inserted);
        return inserted;
    }

    public long insertItemCount(ItemKey key, long amount, Action action, Actor actor) {
        if (amount <= 0 || conflicted) return 0;
        Item primary = key.item();
        var variants = inventory.get(primary);
        long existing = variants != null ? variants.getLong(key) : 0;
        if (existing == 0 && typeCount >= totalTypeSlots) return 0;
        long inserted = Math.min(amount, Long.MAX_VALUE - existing);
        if (inserted <= 0) return 0;
        if (action == Action.EXECUTE) {
            if (variants == null) {
                variants = new Object2LongOpenHashMap<>();
                inventory.put(primary, variants);
            }
            if (existing == 0) typeCount++;
            long newAmount = existing + inserted;
            variants.put(key, newAmount);
            cacheDirty = true;
            setChanged();
            fireChanged(key, inserted, newAmount, actor);
        }
        return inserted;
    }

    public long insertItem(ItemStack stack, boolean simulate) {
        return insertItem(stack, simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
    }

    public long insertItem(ItemStack stack) {
        return insertItem(stack, Action.EXECUTE, Actor.EMPTY);
    }

    public ItemStack extractItem(ItemKey key, long amount, Action action, Actor actor) {
        long requested = Math.min(amount, Integer.MAX_VALUE);
        long extracted = extractItemCount(key, requested, action, actor);
        if (extracted <= 0) return ItemStack.EMPTY;
        return key.toStack((int) extracted);
    }

    public long extractItemCount(ItemKey key, long amount, Action action, Actor actor) {
        if (amount <= 0 || conflicted) return 0;
        Item primary = key.item();
        var variants = inventory.get(primary);
        if (variants == null) return 0;

        long existing = variants.getLong(key);
        if (existing <= 0) return 0;

        long extracted = Math.min(amount, existing);
        if (action == Action.EXECUTE) {
            long remaining = existing - extracted;
            if (remaining <= 0) {
                variants.removeLong(key);
                if (variants.isEmpty()) inventory.remove(primary);
                typeCount--;
            } else {
                variants.put(key, remaining);
            }
            cacheDirty = true;
            setChanged();
            fireChanged(key, -extracted, Math.max(remaining, 0), actor);
            if (mutationBatchDepth == 0 && key.equals(plainGlassBottleKey())) recoverLegacyBottleFuel();
        }
        return extracted;
    }

    public ItemStack extractItem(ItemKey key, long amount, boolean simulate) {
        return extractItem(key, amount, simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
    }

    public ItemStack extractItem(ItemKey key, long amount) {
        return extractItem(key, amount, Action.EXECUTE, Actor.EMPTY);
    }

    public List<ItemStack> getDisplayStacks() {
        return getDisplayStacks("");
    }

    public List<ItemStack> getDisplayStacks(String filter) {
        rebuildCache();
        List<ItemStack> result = new ArrayList<>();
        for (var entry : flatCache.entrySet()) {
            ItemKey key = entry.getKey();
            long count = entry.getValue();
            if (matchesFilter(key, filter, level)) {
                ItemStack stack = key.toStack(1);
                if (!stack.isEmpty()) {
                    result.add(TerminalDisplayStack.create(stack, count));
                }
            }
        }
        return result;
    }

    public List<ItemStack> getDisplayStacks(String filter, SortMode mode, SortOrder order) {
        List<ItemStack> result = getDisplayStacks(filter);
        result.sort(TerminalEntryComparator.forMode(mode, order));
        return result;
    }

    public long getItemCount(ItemKey key) {
        Item primary = key.item();
        var variants = inventory.get(primary);
        if (variants == null) return 0;
        return variants.getLong(key);
    }

    public long countMatching(Predicate<ItemStack> pred) {
        rebuildCache();
        long total = 0;
        for (var entry : flatCache.entrySet()) {
            if (!pred.test(entry.getKey().toStack(1))) continue;
            long amount = entry.getValue();
            if (total > Long.MAX_VALUE - amount) return Long.MAX_VALUE;
            total += amount;
        }
        return total;
    }

    public long extractMatching(Predicate<ItemStack> pred, long amount, Action action, Actor actor) {
        if (amount <= 0 || conflicted) return 0;
        rebuildCache();
        List<ItemKey> matches = new ArrayList<>();
        for (var entry : flatCache.entrySet()) {
            if (pred.test(entry.getKey().toStack(1))) matches.add(entry.getKey());
        }
        long extracted = 0;
        for (ItemKey key : matches) {
            if (extracted >= amount) break;
            extracted += extractItemCount(key, amount - extracted, action, actor);
        }
        return extracted;
    }

    public long extractMatching(Predicate<ItemStack> pred, long amount, boolean simulate) {
        return extractMatching(pred, amount, simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
    }

    static boolean matchesFilter(ItemKey key, String filterText, Level level) {
        if (filterText == null || filterText.isBlank()) return true;
        ItemStack stack = key.toStack(1);

        for (String token : filterText.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.startsWith("@")) {
                String modid = token.substring(1);
                if (!stack.getItem().builtInRegistryHolder().key().location().getNamespace().equals(modid))
                    return false;
            } else if (token.startsWith("#")) {
                String tagName = token.substring(1);
                var tagId = net.minecraft.resources.ResourceLocation.tryParse(tagName);
                if (tagId == null) return false;
                boolean found = false;
                var tags = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ITEM)
                        .getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId));
                if (tags.isPresent()) {
                    for (var holder : tags.get()) {
                        if (holder.value() == stack.getItem()) { found = true; break; }
                    }
                }
                if (!found) return false;
            } else if (token.startsWith("$")) {
                String keyword = token.substring(1);
                if (!stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(keyword))
                    return false;
            } else {
                if (!stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(token))
                    return false;
            }
        }
        return true;
    }

    // ===== NBT =====

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID(TAG_NETWORK_ID, networkId);
        CompoundTag energyTag = new CompoundTag();
        for (Map.Entry<EnergyType, Long> entry : energy.entrySet()) {
            energyTag.putLong(entry.getKey().getId(), entry.getValue());
        }
        if (legacyBottleFuel > 0) energyTag.putLong(LEGACY_BOTTLE_FUEL_ID, legacyBottleFuel);
        tag.put("energy", energyTag);
        ListTag consumablesTag = new ListTag();
        Set<ResourceLocation> consumableIds = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        consumableIds.addAll(descriptorAmounts.keySet());
        consumableIds.addAll(infiniteDescriptors);
        for (ResourceLocation descriptorId : consumableIds) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("descriptorId", descriptorId.toString());
            entryTag.putLong("amount", getDescriptorAmount(descriptorId));
            entryTag.putBoolean("infinite", hasInfiniteDescriptor(descriptorId));
            consumablesTag.add(entryTag);
        }
        tag.put(TAG_DESCRIPTOR_CONSUMABLES, consumablesTag);

        ListTag machineDescriptorsTag = new ListTag();
        List<MachineDescriptor> descriptors = MachineEnergyTable.entries();
        for (int slot = 0; slot < descriptors.size(); slot++) {
            ItemStack stack = machines.getItem(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("descriptorId", descriptors.get(slot).id().toString());
            entryTag.put("item", stack.save(registries));
            machineDescriptorsTag.add(entryTag);
        }
        for (CompoundTag unresolved : unresolvedMachineDescriptors) {
            machineDescriptorsTag.add(unresolved.copy());
        }
        tag.put(TAG_MACHINE_DESCRIPTORS, machineDescriptorsTag);

        ListTag invTag = new ListTag();
        for (var entry : inventory.entrySet()) {
            for (var variantEntry : entry.getValue().object2LongEntrySet()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.put("item", variantEntry.getKey().toStack(1).save(registries));
                entryTag.putLong("count", variantEntry.getLongValue());
                invTag.add(entryTag);
            }
        }
        for (CompoundTag unresolved : unresolvedInventoryEntries) {
            invTag.add(unresolved.copy());
        }
        tag.put("inventory", invTag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID(TAG_NETWORK_ID)) {
            networkId = tag.getUUID(TAG_NETWORK_ID);
        }
        CompoundTag energyTag = tag.getCompound("energy");
        for (EnergyType type : EnergyType.values()) {
            energy.put(type, 0L);
            if (energyTag.contains(type.getId())) {
                energy.put(type, Math.max(0, energyTag.getLong(type.getId())));
            }
        }
        legacyBottleFuel = Math.max(0, energyTag.getLong(LEGACY_BOTTLE_FUEL_ID));
        descriptorAmounts.clear();
        infiniteDescriptors.clear();
        if (tag.contains(TAG_DESCRIPTOR_CONSUMABLES, Tag.TAG_LIST)) {
            ListTag consumablesTag = tag.getList(TAG_DESCRIPTOR_CONSUMABLES, Tag.TAG_COMPOUND);
            for (int i = 0; i < consumablesTag.size(); i++) {
                CompoundTag entryTag = consumablesTag.getCompound(i);
                ResourceLocation descriptorId = ResourceLocation.tryParse(entryTag.getString("descriptorId"));
                if (descriptorId == null) continue;
                if (entryTag.getBoolean("infinite")) infiniteDescriptors.add(descriptorId);
                else {
                    long amount = Math.max(0, entryTag.getLong("amount"));
                    if (amount > 0) descriptorAmounts.put(descriptorId, amount);
                }
            }
        } else {
            long legacyAxeEnergy = Math.max(0, tag.getLong(TAG_AXE_ENERGY));
            if (tag.getBoolean(TAG_INFINITE_AXE_ENERGY)) infiniteDescriptors.add(MachineEnergyTable.AXE_ID);
            else if (legacyAxeEnergy > 0) descriptorAmounts.put(MachineEnergyTable.AXE_ID, legacyAxeEnergy);
        }

        machines.clearContent();
        unresolvedMachineDescriptors.clear();

        inventory.clear();
        unresolvedInventoryEntries.clear();
        ListTag invTag = tag.getList("inventory", Tag.TAG_COMPOUND);
        for (int i = 0; i < invTag.size(); i++) {
            CompoundTag entryTag = invTag.getCompound(i);
            ItemStack stack = parsePersistedItem(entryTag.getCompound("item"), registries);
            if (stack.isEmpty()) {
                unresolvedInventoryEntries.add(entryTag.copy());
                continue;
            }
            long count = entryTag.getLong("count");
            if (count <= 0) continue;
            ItemKey key = ItemKey.of(stack);
            Item primary = key.item();
            var variants = inventory.computeIfAbsent(primary, k -> new Object2LongOpenHashMap<>());
            long existing = variants.getLong(key);
            long merged = existing > Long.MAX_VALUE - count ? Long.MAX_VALUE : existing + count;
            variants.put(key, merged);
        }
        if (tag.contains(TAG_MACHINE_DESCRIPTORS, Tag.TAG_LIST)) {
            loadMachineDescriptors(tag.getList(TAG_MACHINE_DESCRIPTORS, Tag.TAG_COMPOUND), registries);
        } else if (tag.contains(TAG_MACHINES, Tag.TAG_COMPOUND)) {
            loadLegacyMachines(tag.getCompound(TAG_MACHINES), registries);
        }
        typeCount = 0;
        for (var variants : inventory.values()) typeCount += variants.size();
        cacheDirty = true;
        recoverLegacyBottleFuel();
    }

    private void loadMachineDescriptors(ListTag descriptorTags, HolderLookup.Provider registries) {
        for (int i = 0; i < descriptorTags.size(); i++) {
            CompoundTag entryTag = descriptorTags.getCompound(i);
            ItemStack stack = parsePersistedItem(entryTag.getCompound("item"), registries);
            if (stack.isEmpty()) {
                unresolvedMachineDescriptors.add(entryTag.copy());
                continue;
            }
            ResourceLocation descriptorId = ResourceLocation.tryParse(entryTag.getString("descriptorId"));
            int slot = descriptorId != null ? MachineEnergyTable.findSlot(descriptorId) : -1;
            MachineDescriptor descriptor = slot >= 0 ? MachineEnergyTable.get(slot) : null;
            if (descriptor == null || !descriptor.accepts(stack)) {
                recoverUnregisteredMachine(stack);
                continue;
            }
            if (descriptor.category() == MachineEnergyTable.Category.CONSUMABLE) {
                ItemStack consumable = stack.copy();
                if (!addDescriptorConsumable(descriptor.id(), consumable)) {
                    recoverUnregisteredMachine(stack);
                }
                continue;
            }
            ItemStack existing = machines.getItem(slot);
            int room = descriptor.maxInstalledCount() - existing.getCount();
            int installed = Math.min(Math.max(0, room), stack.getCount());
            if (installed > 0) {
                ItemStack merged = stack.copyWithCount(existing.getCount() + installed);
                machines.setItem(slot, merged);
                stack.shrink(installed);
            }
            recoverUnregisteredMachine(stack);
        }
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

    private void loadLegacyMachines(CompoundTag machinesTag, HolderLookup.Provider registries) {
        SimpleContainer legacy = new SimpleContainer(MachineDescriptorApi.MAX_DESCRIPTORS);
        ContainerHelper.loadAllItems(machinesTag, legacy.getItems(), registries);
        int legacySlots = Math.min(9, legacy.getContainerSize());
        for (int slot = 0; slot < legacySlots; slot++) {
            ItemStack stack = legacy.getItem(slot);
            if (stack.isEmpty()) continue;
            MachineDescriptor descriptor = MachineEnergyTable.get(slot);
            if (descriptor == null || !descriptor.accepts(stack)) {
                recoverUnregisteredMachine(stack);
                continue;
            }
            if (descriptor.category() == MachineEnergyTable.Category.CONSUMABLE) {
                ItemStack migration = stack.copy();
                if (!addDescriptorConsumable(descriptor.id(), migration)) {
                    machines.setItem(slot, stack.copy());
                }
                continue;
            }
            int installed = Math.min(stack.getCount(), descriptor.maxInstalledCount());
            machines.setItem(slot, stack.copyWithCount(installed));
            if (stack.getCount() > installed) {
                recoverUnregisteredMachine(stack.copyWithCount(stack.getCount() - installed));
            }
        }
        for (int slot = legacySlots; slot < legacy.getContainerSize(); slot++) {
            recoverUnregisteredMachine(legacy.getItem(slot));
        }
    }

    private void recoverUnregisteredMachine(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemKey key = ItemKey.of(stack);
        Item primary = key.item();
        var variants = inventory.computeIfAbsent(primary, ignored -> new Object2LongOpenHashMap<>());
        long existing = variants.getLong(key);
        long recovered = Math.min((long) stack.getCount(), Long.MAX_VALUE - existing);
        if (recovered <= 0) return;
        variants.put(key, existing + recovered);
        cacheDirty = true;
    }

    private static ItemKey plainGlassBottleKey() {
        return ItemKey.of(new ItemStack(Items.GLASS_BOTTLE));
    }

    private void recoverLegacyBottleFuel() {
        if (legacyBottleFuel <= 0) return;
        ItemKey key = plainGlassBottleKey();
        Item primary = key.item();
        var variants = inventory.get(primary);
        long existing = variants != null ? variants.getLong(key) : 0;
        long recovered = Math.min(legacyBottleFuel, Long.MAX_VALUE - existing);
        if (recovered <= 0) return;
        if (variants == null) {
            variants = new Object2LongOpenHashMap<>();
            inventory.put(primary, variants);
        }
        if (existing == 0) typeCount++;
        long updated = existing + recovered;
        variants.put(key, updated);
        legacyBottleFuel -= recovered;
        cacheDirty = true;
        setChanged();
        fireChanged(key, recovered, updated, Actor.EMPTY);
    }

    // ===== Network =====

    public void rebuildNetwork(Level level) {
        Set<BlockPos> previousConnectedBlocks = Set.copyOf(connectedBlocks);
        int previousTotalTypeSlots = totalTypeSlots;
        boolean wasConflicted = conflicted;
        connectedBlocks.clear();
        totalTypeSlots = 0;
        conflicted = false;

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(getBlockPos());
        visited.add(getBlockPos());

        int depth = 0;
        while (!queue.isEmpty() && depth < MagicStorage.NETWORK_SCAN_DEPTH && visited.size() <= MagicStorage.MAX_NETWORK_BLOCKS) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                BlockPos current = queue.poll();

                BlockState state = level.getBlockState(current);
                if (state.getBlock() instanceof IStorageNetworkBlock networkBlock) {
                    if (networkBlock.isStorageCore() && !current.equals(getBlockPos())) {
                        conflicted = true;
                        continue;
                    }
                    connectedBlocks.add(current);
                    totalTypeSlots += capacityOf(state);
                }

                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (!visited.contains(neighbor) && level.hasChunkAt(neighbor)) {
                        visited.add(neighbor);
                        if (level.getBlockState(neighbor).getBlock() instanceof IStorageNetworkBlock) {
                            queue.add(neighbor);
                        }
                    }
                }
            }
            depth++;
        }
        if (conflicted && !wasConflicted) {
            MagicStorage.LOGGER.warn("Storage network at {} has multiple cores; multi-core is unsupported, network disabled until extra cores removed.", getBlockPos());
        }
        if (wasConflicted != conflicted || previousTotalTypeSlots != totalTypeSlots
                || !previousConnectedBlocks.equals(connectedBlocks)) {
            topologyRevision++;
        }
    }

    public boolean tryIncrementalAdd(Level level, BlockPos placedPos) {
        if (conflicted) return false;
        if (placedPos.equals(getBlockPos())) return false;
        if (connectedBlocks.contains(placedPos)) return false;

        BlockState state = level.getBlockState(placedPos);
        if (!(state.getBlock() instanceof IStorageNetworkBlock networkBlock)) return false;
        if (networkBlock.isStorageCore()) return false;

        if (!isWithinIncrementalBounds(placedPos)) return false;

        connectedBlocks.add(placedPos);
        totalTypeSlots += capacityOf(state);
        topologyRevision++;
        return true;
    }

    private boolean isWithinIncrementalBounds(BlockPos placedPos) {
        if (connectedBlocks.size() >= MagicStorage.MAX_NETWORK_BLOCKS) return false;
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(getBlockPos());
        visited.add(getBlockPos());

        int depth = 0;
        while (!queue.isEmpty() && depth < MagicStorage.NETWORK_SCAN_DEPTH - 1) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                BlockPos current = queue.poll();
                for (Direction dir : Direction.values()) {
                    BlockPos next = current.relative(dir);
                    if (next.equals(placedPos)) return true;
                    if (connectedBlocks.contains(next) && visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            depth++;
        }
        return false;
    }

    private int capacityOf(BlockState state) {
        if (state.getBlock() instanceof StorageUnitBlock unitBlock) {
            return unitBlock.getTypeContribution();
        }
        return 0;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            rebuildNetwork(level);
        }
    }

    public void onBreak() {
        if (!connectedBlocks.isEmpty() || totalTypeSlots != 0) topologyRevision++;
        connectedBlocks.clear();
        totalTypeSlots = 0;
    }

    public Set<BlockPos> getConnectedBlocks() { return connectedBlocks; }
    public int getTotalTypeSlots() { return totalTypeSlots; }
    public long getTopologyRevision() { return topologyRevision; }
}
