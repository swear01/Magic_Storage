package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;

public class StorageCoreBlockEntity extends BlockEntity {

    private static final String TAG_NETWORK_ID = "networkId";
    private static final String TAG_MACHINES = "machines";

    private final Set<BlockPos> connectedBlocks = new HashSet<>();
    private UUID networkId = UUID.randomUUID();
    private boolean conflicted = false;
    private long topologyRevision;
    private int totalTypeSlots = 0;
    private int typeCount = 0;
    private long machineRevision;

    // Energy
    private final Map<EnergyType, Long> energy = new HashMap<>();
    private final SimpleContainer machines = new SimpleContainer(MachineEnergyTable.size()) {
        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            MachineEnergyTable.Entry entry = MachineEnergyTable.get(slot);
            return entry != null && entry.accepts(stack);
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
        for (int slot = 0; slot < machines.getContainerSize(); slot++) {
            MachineEnergyTable.Entry entry = MachineEnergyTable.get(slot);
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
        mutationBatchDepth--;
        if (mutationBatchDepth > 0) return;
        List<Runnable> events = List.copyOf(deferredListenerEvents);
        deferredListenerEvents.clear();
        for (Runnable event : events) event.run();
    }

    public long insertItem(ItemStack stack, Action action, Actor actor) {
        if (stack.isEmpty() || conflicted) return 0;
        ItemKey key = ItemKey.of(stack);
        Item primary = key.item();

        var variants = inventory.get(primary);
        long existing = variants != null ? variants.getLong(key) : 0;
        long toInsert = stack.getCount();

        if (existing == 0) {
            if (typeCount >= totalTypeSlots) return 0;
        }

        long inserted = Math.min(toInsert, Long.MAX_VALUE - existing);
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
            stack.shrink((int) inserted);
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
        if (amount <= 0 || conflicted) return ItemStack.EMPTY;
        Item primary = key.item();
        var variants = inventory.get(primary);
        if (variants == null) return ItemStack.EMPTY;

        long existing = variants.getLong(key);
        if (existing <= 0) return ItemStack.EMPTY;

        long toExtract = Math.min(amount, existing);
        int extracted = (int) Math.min(toExtract, Integer.MAX_VALUE);
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
        }

        ItemStack result = key.toStack(extracted);
        result.setCount(extracted);
        return result;
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
                    stack.setCount((int) Math.min(count, Integer.MAX_VALUE));
                    result.add(stack);
                }
            }
        }
        return result;
    }

    public List<ItemStack> getDisplayStacks(String filter, SortMode mode, SortOrder order) {
        List<ItemStack> result = getDisplayStacks(filter);
        if (mode == SortMode.QUANTITY) {
            record Entry(ItemStack stack, long count) {}
            List<Entry> entries = new ArrayList<>(result.size());
            for (ItemStack stack : result) {
                Long count = flatCache.get(ItemKey.of(stack));
                entries.add(new Entry(stack, count != null ? count : 0L));
            }
            Comparator<Entry> cmp = Comparator.comparingLong(Entry::count);
            entries.sort(order == SortOrder.DESCENDING ? cmp.reversed() : cmp);
            result = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                result.add(entry.stack());
            }
            return result;
        }
        Comparator<ItemStack> cmp = switch (mode) {
            case NAME -> Comparator.comparing(s -> s.getHoverName().getString());
            case QUANTITY -> throw new IllegalStateException();
            case ID -> Comparator.comparing(s ->
                    BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        };
        result.sort(order == SortOrder.DESCENDING ? cmp.reversed() : cmp);
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
            ItemStack got = extractItem(key, amount - extracted, action, actor);
            extracted += got.getCount();
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
        tag.put("energy", energyTag);

        CompoundTag machinesTag = new CompoundTag();
        ContainerHelper.saveAllItems(machinesTag, machines.getItems(), registries);
        tag.put(TAG_MACHINES, machinesTag);

        ListTag invTag = new ListTag();
        for (var entry : inventory.entrySet()) {
            for (var variantEntry : entry.getValue().object2LongEntrySet()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.put("item", variantEntry.getKey().toStack(1).save(registries));
                entryTag.putLong("count", variantEntry.getLongValue());
                invTag.add(entryTag);
            }
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
            if (energyTag.contains(type.getId())) {
                energy.put(type, Math.max(0, energyTag.getLong(type.getId())));
            }
        }

        machines.clearContent();
        if (tag.contains(TAG_MACHINES, Tag.TAG_COMPOUND)) {
            ContainerHelper.loadAllItems(tag.getCompound(TAG_MACHINES), machines.getItems(), registries);
        }

        inventory.clear();
        ListTag invTag = tag.getList("inventory", Tag.TAG_COMPOUND);
        for (int i = 0; i < invTag.size(); i++) {
            CompoundTag entryTag = invTag.getCompound(i);
            ItemStack stack = ItemStack.parse(registries, entryTag.getCompound("item")).orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) continue;
            long count = entryTag.getLong("count");
            if (count <= 0) continue;
            ItemKey key = ItemKey.of(stack);
            Item primary = key.item();
            var variants = inventory.computeIfAbsent(primary, k -> new Object2LongOpenHashMap<>());
            long existing = variants.getLong(key);
            long merged = existing > Long.MAX_VALUE - count ? Long.MAX_VALUE : existing + count;
            variants.put(key, merged);
        }
        typeCount = 0;
        for (var variants : inventory.values()) typeCount += variants.size();
        cacheDirty = true;
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
