package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.function.Predicate;

public class StorageCoreBlockEntity extends BlockEntity {

    private static final String TAG_STORAGE_ID = "storageId";
    private static final String TAG_STORAGE_SCHEMA = "storageSchema";
    private static final int STORAGE_SCHEMA = 1;

    private final Set<BlockPos> connectedBlocks = new HashSet<>();
    private final UUID attachmentToken = UUID.randomUUID();
    private UUID storageId;
    private int storageSchema;
    private CoreStorageRecord storageRecord;
    private StorageAvailability storageAvailability = StorageAvailability.UNINITIALIZED;
    private UUID networkId;
    private boolean conflicted = false;
    private long topologyRevision;
    private StorageTypeCapacity typeCapacity = StorageTypeCapacity.zero();
    private int typeCount = 0;
    private long machineRevision;
    private Map<ResourceLocation, Long> descriptorAmounts = new HashMap<>();
    private Set<ResourceLocation> infiniteDescriptors = new HashSet<>();
    private UUID preparedRecoveryId;

    private Map<EnergyType, Long> energy = new EnumMap<>(EnergyType.class);
    private SimpleContainer machines = new SimpleContainer(MachineDescriptorApi.MAX_DESCRIPTORS);

    private StorageResourceLedger resourceLedger = new StorageResourceLedger();
    private final Map<ItemKey, Long> flatCache = new HashMap<>();
    private final CoreStorageResourceHandler resourceHandler = new CoreStorageResourceHandler(this);
    private final CoreFluidHandler fluidHandler = new CoreFluidHandler(this);
    private final CoreEnergyStorage energyStorage = new CoreEnergyStorage(this);
    private final List<StorageListener> listeners = new ArrayList<>();
    private final List<Runnable> deferredListenerEvents = new ArrayList<>();
    private int mutationBatchDepth;
    private boolean storageChangedInBatch;
    private boolean cacheDirty = true;

    public StorageCoreBlockEntity(BlockPos pos, BlockState state) {
        super(MagicStorage.STORAGE_CORE_BE.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide() || conflicted || !isStorageAvailable()) return;
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
        if (changed) markStorageChanged();
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
        if (!isStorageAvailable()) return 0;
        return descriptorAmounts.getOrDefault(descriptorId, 0L);
    }

    public boolean hasInfiniteDescriptor(ResourceLocation descriptorId) {
        return isStorageAvailable() && infiniteDescriptors.contains(descriptorId);
    }

    public boolean hasDescriptorAmount(ResourceLocation descriptorId, long amount) {
        return amount > 0 && (hasInfiniteDescriptor(descriptorId)
                || getDescriptorAmount(descriptorId) >= amount);
    }

    public boolean canAddDescriptorConsumable(ResourceLocation descriptorId, ItemStack stack) {
        if (stack.isEmpty() || conflicted || !isStorageAvailable()
                || hasInfiniteDescriptor(descriptorId)) return false;
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
        markStorageChanged();
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
        markStorageChanged();
        return true;
    }

    public long getEnergy(EnergyType type) {
        return isStorageAvailable() ? energy.getOrDefault(type, 0L) : 0;
    }

    public boolean consumeEnergy(EnergyCost cost, long multiplier) {
        if (multiplier <= 0 || !isStorageAvailable() || conflicted) return false;
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
        markStorageChanged();
        return true;
    }

    public boolean addFuel(ItemStack stack, EnergyType targetPool) {
        if (stack.isEmpty() || conflicted || !isStorageAvailable()) return false;
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
                markStorageChanged();
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
        return conflicted || !isStorageAvailable();
    }

    public UUID getNetworkId() {
        if (!isStorageAvailable()) {
            throw new IllegalStateException("Core storage data is unavailable at " + getBlockPos());
        }
        return networkId;
    }

    public long getTotalStoredItemCount() {
        return storageRecord == null ? 0 : storageRecord.itemCount();
    }

    public boolean hasRecoverableContents() {
        return storageRecord != null && !storageRecord.isEmpty();
    }

    UUID prepareRecoveryDrop(ServerLevel serverLevel, UUID owner) {
        if (preparedRecoveryId != null) return preparedRecoveryId;
        if (!isStorageAvailable()) {
            throw new IllegalStateException("Cannot pack unavailable Core storage at " + getBlockPos());
        }
        preparedRecoveryId = CoreStorageRepository.get(serverLevel).prepareRecovery(
                storageId,
                location(serverLevel),
                attachmentToken,
                owner,
                serverLevel.getGameTime()).map(CoreStorageRepository.RecoverySummary::id)
                .orElseThrow(() -> new IllegalStateException(
                        "Core storage attachment is unavailable while packing " + storageId));
        return preparedRecoveryId;
    }

    Optional<UUID> getPreparedRecoveryId() {
        return Optional.ofNullable(preparedRecoveryId);
    }

    public boolean isStorageAvailable() {
        return storageAvailability == StorageAvailability.AVAILABLE && storageRecord != null;
    }

    Optional<UUID> getStorageId() {
        return Optional.ofNullable(storageId);
    }

    CoreStorageRecord storageRecordForTesting() {
        if (storageRecord == null) throw new IllegalStateException("Core storage record is unavailable");
        return storageRecord;
    }

    void initializeFreshStorage(ServerLevel serverLevel) {
        if (isStorageAvailable()) return;
        if (storageId != null) {
            attachExistingStorage(serverLevel);
            return;
        }
        Optional<CoreStorageRecord> created = CoreStorageRepository.get(serverLevel)
                .tryCreateFresh(location(serverLevel), attachmentToken);
        if (created.isEmpty()) {
            updateStorageAvailability(StorageAvailability.UNSUPPORTED_REPOSITORY);
            return;
        }
        CoreStorageRecord fresh = created.get();
        storageId = fresh.storageId();
        storageSchema = STORAGE_SCHEMA;
        attachStorage(fresh);
        super.setChanged();
    }

    boolean claimRecovery(ServerLevel serverLevel, UUID recoveryId) {
        if (!isStorageAvailable() || storageId == null || !storageRecord.isEmpty()) return false;
        CoreStorageRecord temporary = storageRecord;
        CoreStorageRepository repository = CoreStorageRepository.get(serverLevel);
        CoreStorageRepository.ClaimResult result = repository.claimIntoFresh(
                recoveryId, storageId, location(serverLevel), attachmentToken);
        if (!result.success()) {
            temporary.clearMachineMutationCallback();
            if (!repository.removeIfEmpty(storageId, location(serverLevel), attachmentToken)) {
                repository.release(storageId, location(serverLevel), attachmentToken);
            }
            storageRecord = null;
            networkId = null;
            energy = new EnumMap<>(EnergyType.class);
            machines = new SimpleContainer(MachineDescriptorApi.MAX_DESCRIPTORS);
            descriptorAmounts = new HashMap<>();
            infiniteDescriptors = new HashSet<>();
            resourceLedger = new StorageResourceLedger();
            typeCount = 0;
            cacheDirty = true;
            updateStorageAvailability(StorageAvailability.from(result.reason()));
            super.setChanged();
            return false;
        }
        temporary.clearMachineMutationCallback();
        storageId = result.record().storageId();
        storageSchema = STORAGE_SCHEMA;
        attachStorage(result.record());
        super.setChanged();
        return true;
    }

    void removeStorageForBlockRemoval(ServerLevel serverLevel) {
        if (storageId == null || storageRecord == null) return;
        CoreStorageRepository repository = CoreStorageRepository.get(serverLevel);
        if (!repository.removeIfEmpty(storageId, location(serverLevel), attachmentToken)) {
            repository.release(storageId, location(serverLevel), attachmentToken);
        }
        storageRecord.clearMachineMutationCallback();
        storageRecord = null;
        storageAvailability = StorageAvailability.UNINITIALIZED;
    }

    private void attachExistingStorage(ServerLevel serverLevel) {
        if (storageSchema != STORAGE_SCHEMA) {
            updateStorageAvailability(StorageAvailability.UNSUPPORTED_REFERENCE);
            return;
        }
        CoreStorageRepository.AttachResult result = CoreStorageRepository.get(serverLevel)
                .attachExisting(storageId, location(serverLevel), attachmentToken);
        if (!result.success()) {
            updateStorageAvailability(StorageAvailability.from(result.reason()));
            return;
        }
        attachStorage(result.record());
    }

    private void attachStorage(CoreStorageRecord record) {
        if (storageRecord != null && storageRecord != record) {
            storageRecord.clearMachineMutationCallback();
        }
        storageRecord = record;
        networkId = record.networkId();
        energy = record.energy();
        machines = record.machines();
        descriptorAmounts = record.descriptorAmounts();
        infiniteDescriptors = record.infiniteDescriptors();
        resourceLedger = record.resourceLedger();
        typeCount = record.typeCount();
        cacheDirty = true;
        record.setMachineMutationCallback(this::onMachineChanged);
        updateStorageAvailability(StorageAvailability.AVAILABLE);
    }

    private void onMachineChanged() {
        machineRevision++;
        markStorageChanged();
    }

    private void markStorageChanged() {
        if (storageRecord == null) return;
        if (mutationBatchDepth > 0) {
            storageChangedInBatch = true;
            return;
        }
        storageRecord.markChanged();
    }

    private CoreStorageRepository.CoreLocation location(ServerLevel serverLevel) {
        return new CoreStorageRepository.CoreLocation(serverLevel.dimension(), getBlockPos());
    }

    private void updateStorageAvailability(StorageAvailability availability) {
        if (storageAvailability == availability) return;
        storageAvailability = availability;
        if (availability != StorageAvailability.AVAILABLE
                && availability != StorageAvailability.UNINITIALIZED) {
            MagicStorage.LOGGER.error(
                    "Core storage data unavailable: reason={}, storageId={}, dimension={}, pos={}",
                    availability,
                    storageId,
                    level == null ? "unassigned" : level.dimension().location(),
                    getBlockPos());
        }
    }

    private void rebuildCache() {
        if (!cacheDirty) return;
        flatCache.clear();
        if (level == null) return;
        for (StorageResourceKey resourceKey : resourceLedger.keys(StorageResourceBridge.ITEM_KIND)) {
            StorageResourceBridge.itemKey(resourceKey, level.registryAccess())
                    .ifPresent(key -> flatCache.merge(
                            key, resourceLedger.amount(resourceKey), Long::sum));
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

    private void fireResourceChanged(
            StorageResourceKey key,
            long delta,
            long newAmount,
            Actor actor
    ) {
        if (mutationBatchDepth > 0) {
            deferredListenerEvents.add(() -> notifyResourceChanged(key, delta, newAmount, actor));
            return;
        }
        notifyResourceChanged(key, delta, newAmount, actor);
    }

    private void notifyResourceChanged(
            StorageResourceKey key,
            long delta,
            long newAmount,
            Actor actor
    ) {
        for (StorageListener listener : List.copyOf(listeners)) {
            listener.onResourceChanged(key, delta, newAmount, actor);
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
        mutationBatchDepth--;
        if (mutationBatchDepth > 0) return;
        if (storageChangedInBatch && storageRecord != null) {
            storageChangedInBatch = false;
            storageRecord.markChanged();
        }
        List<Runnable> events = List.copyOf(deferredListenerEvents);
        deferredListenerEvents.clear();
        for (Runnable event : events) event.run();
    }

    public long insertItem(ItemStack stack, Action action, Actor actor) {
        if (stack.isEmpty() || conflicted || !isStorageAvailable()) return 0;
        ItemKey key = ItemKey.of(stack);
        long inserted = insertItemCount(key, stack.getCount(), action, actor);
        if (action == Action.EXECUTE && inserted > 0) stack.shrink((int) inserted);
        return inserted;
    }

    public long insertResource(StorageResourceKey key, long amount, Action action) {
        if (amount <= 0 || conflicted || !isStorageAvailable()) return 0;
        long existing = resourceLedger.amount(key);
        if (existing == 0 && !ledgerCapacity().canAcceptNewType(resourceLedger.typeCount())) return 0;
        long inserted = Math.min(amount, Long.MAX_VALUE - existing);
        if (inserted <= 0 || !applyResourceTransaction(
                Map.of(key, inserted), action, Actor.EMPTY)) return 0;
        return inserted;
    }

    public long extractResource(StorageResourceKey key, long amount, Action action) {
        if (amount <= 0 || conflicted || !isStorageAvailable()) return 0;
        long extracted = Math.min(amount, resourceLedger.amount(key));
        if (extracted <= 0 || !applyResourceTransaction(
                Map.of(key, -extracted), action, Actor.EMPTY)) return 0;
        return extracted;
    }

    boolean applyResourceTransaction(
            Map<StorageResourceKey, Long> deltas,
            Action action,
            Actor actor
    ) {
        Objects.requireNonNull(deltas, "deltas");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        if (deltas.isEmpty() || conflicted || !isStorageAvailable() || level == null) return false;
        Map<StorageResourceKey, ItemKey> itemKeys = new HashMap<>();
        for (StorageResourceKey key : deltas.keySet()) {
            if (!StorageResourceKinds.accepts(key)) return false;
            if (!key.kindId().equals(StorageResourceBridge.ITEM_KIND)) continue;
            var itemKey = StorageResourceBridge.itemKey(key, level.registryAccess());
            if (itemKey.isEmpty()) return false;
            itemKeys.put(key, itemKey.get());
        }
        if (!resourceLedger.applyExact(deltas, ledgerCapacity(), action)) return false;
        if (action == Action.EXECUTE) {
            refreshTypeCount();
            if (!itemKeys.isEmpty()) cacheDirty = true;
            markStorageChanged();
            for (Map.Entry<StorageResourceKey, ItemKey> entry : itemKeys.entrySet()) {
                fireChanged(
                        entry.getValue(),
                        deltas.get(entry.getKey()),
                        resourceLedger.amount(entry.getKey()),
                        actor);
            }
            for (Map.Entry<StorageResourceKey, Long> entry : deltas.entrySet()) {
                if (itemKeys.containsKey(entry.getKey())) continue;
                fireResourceChanged(
                        entry.getKey(),
                        entry.getValue(),
                        resourceLedger.amount(entry.getKey()),
                        actor);
            }
        }
        return true;
    }

    public boolean applyResourceTransaction(
            StorageResourceTransaction transaction,
            Action action,
            Actor actor
    ) {
        Objects.requireNonNull(transaction, "transaction");
        return applyResourceTransaction(transaction.deltas(), action, actor);
    }

    public long getResourceAmount(StorageResourceKey key) {
        return isStorageAvailable() ? resourceLedger.amount(key) : 0;
    }

    public List<StorageResourceKey> getResourceKeys(ResourceLocation kindId) {
        return isStorageAvailable() ? resourceLedger.keys(kindId) : List.of();
    }

    public List<StorageResourceKey> getResourceKeys() {
        return isStorageAvailable() ? resourceLedger.snapshot().keySet().stream().toList() : List.of();
    }

    StorageResourceHandler resourceHandler() {
        return resourceHandler;
    }

    CoreFluidHandler fluidHandler() {
        return fluidHandler;
    }

    CoreEnergyStorage energyStorage() {
        return energyStorage;
    }

    private StorageTypeCapacity ledgerCapacity() {
        if (typeCapacity.unlimited()) return StorageTypeCapacity.unlimitedCapacity();
        int unresolvedTypes = storageRecord == null
                ? 0 : storageRecord.unresolvedInventoryEntries().size();
        return StorageTypeCapacity.finite(Math.max(
                0, typeCapacity.finiteTypeSlots() - unresolvedTypes));
    }

    private void refreshTypeCount() {
        typeCount = storageRecord == null
                ? resourceLedger.typeCount() : storageRecord.typeCount();
    }

    public long insertItemCount(ItemKey key, long amount, Action action, Actor actor) {
        if (amount <= 0 || conflicted || !isStorageAvailable()) return 0;
        if (level == null) return 0;
        StorageResourceKey resourceKey = StorageResourceBridge.itemKey(key, level.registryAccess());
        long existing = resourceLedger.amount(resourceKey);
        if (existing == 0 && !ledgerCapacity().canAcceptNewType(resourceLedger.typeCount())) return 0;
        long inserted = Math.min(amount, Long.MAX_VALUE - existing);
        if (inserted <= 0) return 0;
        return applyResourceTransaction(Map.of(resourceKey, inserted), action, actor)
                ? inserted : 0;
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
        if (amount <= 0 || conflicted || !isStorageAvailable()) return 0;
        if (level == null) return 0;
        StorageResourceKey resourceKey = StorageResourceBridge.itemKey(key, level.registryAccess());
        long existing = resourceLedger.amount(resourceKey);
        if (existing <= 0) return 0;

        long extracted = Math.min(amount, existing);
        return applyResourceTransaction(Map.of(resourceKey, -extracted), action, actor)
                ? extracted : 0;
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
        if (!isStorageAvailable()) return List.of();
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

    public List<ItemStack> getTerminalDisplayStacks(
            String filter,
            SortMode mode,
            SortOrder order,
            TerminalResourceView resourceView
    ) {
        if (resourceView == TerminalResourceView.ITEM) {
            return getDisplayStacks(filter, mode, order);
        }
        List<ItemStack> result = new ArrayList<>();
        if (level == null) return result;
        for (Map.Entry<StorageResourceKey, Long> entry : resourceLedger.snapshot().entrySet()) {
            StorageResourceKey key = entry.getKey();
            if (!resourceView.matches(key)) continue;
            if (!StorageResourceKinds.isRegistered(key)) continue;
            ItemStack representative = StorageResourceKinds.representative(
                    key, level.registryAccess());
            if (!matchesResourceFilter(key, representative, filter)) continue;
            result.add(TerminalResourceDisplay.create(representative, key, entry.getValue()));
        }
        result.sort(TerminalEntryComparator.forMode(mode, order));
        return result;
    }

    private static boolean matchesResourceFilter(
            StorageResourceKey key,
            ItemStack representative,
            String filter
    ) {
        if (filter == null || filter.isBlank()) return true;
        String identity = (key.kindId() + " " + key.resourceId() + " "
                + representative.getHoverName().getString()).toLowerCase(Locale.ROOT);
        for (String token : filter.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isEmpty() && !identity.contains(token)) return false;
        }
        return true;
    }

    public long getItemCount(ItemKey key) {
        if (!isStorageAvailable() || level == null) return 0;
        return resourceLedger.amount(StorageResourceBridge.itemKey(key, level.registryAccess()));
    }

    public long countMatching(Predicate<ItemStack> pred) {
        if (!isStorageAvailable()) return 0;
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
        if (amount <= 0 || conflicted || !isStorageAvailable()) return 0;
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
        if (storageId != null) {
            tag.putUUID(TAG_STORAGE_ID, storageId);
        }
        tag.putInt(TAG_STORAGE_SCHEMA, storageSchema);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        storageId = tag.hasUUID(TAG_STORAGE_ID) ? tag.getUUID(TAG_STORAGE_ID) : null;
        storageSchema = tag.getInt(TAG_STORAGE_SCHEMA);
        storageRecord = null;
        networkId = null;
        energy = new EnumMap<>(EnergyType.class);
        machines = new SimpleContainer(MachineDescriptorApi.MAX_DESCRIPTORS);
        descriptorAmounts = new HashMap<>();
        infiniteDescriptors = new HashSet<>();
        resourceLedger = new StorageResourceLedger();
        typeCount = 0;
        cacheDirty = true;
        storageAvailability = storageId == null
                ? StorageAvailability.MISSING_REFERENCE
                : StorageAvailability.UNINITIALIZED;
    }

    // ===== Network =====

    public void rebuildNetwork(Level level) {
        Set<BlockPos> previousConnectedBlocks = Set.copyOf(connectedBlocks);
        StorageTypeCapacity previousTypeCapacity = typeCapacity;
        boolean wasConflicted = conflicted;
        connectedBlocks.clear();
        typeCapacity = StorageTypeCapacity.zero();
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
                    typeCapacity = typeCapacity.plus(capacityOf(state));
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
        if (wasConflicted != conflicted || !previousTypeCapacity.equals(typeCapacity)
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
        typeCapacity = typeCapacity.plus(capacityOf(state));
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

    private StorageTypeCapacity capacityOf(BlockState state) {
        if (state.getBlock() instanceof StorageUnitBlock unitBlock) {
            return unitBlock.getTypeCapacityContribution();
        }
        return StorageTypeCapacity.zero();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            if (storageId != null && !isStorageAvailable()) {
                attachExistingStorage(serverLevel);
            }
            rebuildNetwork(serverLevel);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel && storageId != null && storageRecord != null) {
            CoreStorageRepository.get(serverLevel).release(storageId, location(serverLevel), attachmentToken);
            storageRecord.clearMachineMutationCallback();
            storageRecord = null;
            storageAvailability = StorageAvailability.UNINITIALIZED;
        }
        super.setRemoved();
    }

    public void onBreak() {
        if (!connectedBlocks.isEmpty() || !typeCapacity.equals(StorageTypeCapacity.zero())) topologyRevision++;
        connectedBlocks.clear();
        typeCapacity = StorageTypeCapacity.zero();
    }

    public Set<BlockPos> getConnectedBlocks() { return connectedBlocks; }
    public int getTotalTypeSlots() { return typeCapacity.finiteTypeSlots(); }
    public StorageTypeCapacity getTypeCapacity() { return typeCapacity; }
    public long getTopologyRevision() { return topologyRevision; }

    private enum StorageAvailability {
        UNINITIALIZED,
        AVAILABLE,
        MISSING_REFERENCE,
        UNSUPPORTED_REFERENCE,
        MISSING_RECORD,
        CORRUPT_RECORD,
        UNSUPPORTED_REPOSITORY,
        DUPLICATE_ATTACHMENT,
        PACKED,
        RECOVERY_MISSING,
        INVALID_FRESH_RECORD;

        static StorageAvailability from(CoreStorageRepository.AttachFailure failure) {
            return switch (failure) {
                case MISSING_RECORD -> MISSING_RECORD;
                case CORRUPT_RECORD -> CORRUPT_RECORD;
                case UNSUPPORTED_REPOSITORY -> UNSUPPORTED_REPOSITORY;
                case DUPLICATE_ATTACHMENT -> DUPLICATE_ATTACHMENT;
                case PACKED -> PACKED;
                case RECOVERY_MISSING -> RECOVERY_MISSING;
                case INVALID_FRESH_RECORD -> INVALID_FRESH_RECORD;
            };
        }
    }
}
