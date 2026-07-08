package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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

    public static final Map<BlockPos, CompoundTag> PENDING = new HashMap<>();

    private final Set<BlockPos> connectedBlocks = new HashSet<>();
    private boolean conflicted = false;
    private int totalTypeSlots = 0;
    private int typeCount = 0;

    // Energy
    private final Map<EnergyType, Long> energy = new HashMap<>();

    // Two-tier inventory index
    private final Map<Item, Object2LongOpenHashMap<ItemKey>> inventory = new IdentityHashMap<>();
    private final Map<ItemKey, Long> flatCache = new HashMap<>();
    private boolean cacheDirty = true;

    public StorageCoreBlockEntity(BlockPos pos, BlockState state) {
        super(MagicStorage.STORAGE_CORE_BE.get(), pos, state);
        for (EnergyType type : EnergyType.values()) {
            energy.put(type, 0L);
        }
    }

    public void tick() {
        if (level == null || level.isClientSide()) return;
        boolean changed = false;
        for (EnergyType type : EnergyType.values()) {
            if (type.isAutoFill()) {
                energy.merge(type, (long) type.getTickRate(), Long::sum);
                changed = true;
            }
        }
        if (changed) setChanged();
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
        setChanged();
        return true;
    }

    public boolean addFuel(ItemStack stack, EnergyType targetPool) {
        List<FuelValue> values = FuelTable.getFuelValues(stack);
        for (FuelValue fv : values) {
            if (fv.pool() == targetPool) {
                long amount;
                try {
                    amount = Math.multiplyExact(fv.valuePerItem(), (long) stack.getCount());
                } catch (ArithmeticException e) {
                    return false;
                }
                energy.merge(targetPool, amount, Long::sum);
                stack.setCount(0);
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

    public long insertItem(ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || conflicted) return 0;
        ItemKey key = ItemKey.of(stack);
        Item primary = key.item();

        var variants = inventory.computeIfAbsent(primary, k -> new Object2LongOpenHashMap<>());
        long existing = variants.getLong(key);
        long toInsert = stack.getCount();

        if (existing == 0) {
            if (typeCount >= totalTypeSlots) return 0;
        }

        long inserted = toInsert;
        if (!simulate) {
            if (existing == 0) typeCount++;
            variants.put(key, existing + inserted);
            cacheDirty = true;
            stack.setCount(0);
            setChanged();
        }
        return inserted;
    }

    public long insertItem(ItemStack stack) {
        return insertItem(stack, false);
    }

    public ItemStack extractItem(ItemKey key, long amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        Item primary = key.item();
        var variants = inventory.get(primary);
        if (variants == null) return ItemStack.EMPTY;

        long existing = variants.getLong(key);
        if (existing <= 0) return ItemStack.EMPTY;

        long toExtract = Math.min(amount, existing);
        int extracted = (int) Math.min(toExtract, Integer.MAX_VALUE);
        if (!simulate) {
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
        }

        ItemStack result = key.toStack(extracted);
        result.setCount(extracted);
        return result;
    }

    public ItemStack extractItem(ItemKey key, long amount) {
        return extractItem(key, amount, false);
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
            if (pred.test(entry.getKey().toStack(1))) total += entry.getValue();
        }
        return total;
    }

    public long extractMatching(Predicate<ItemStack> pred, long amount, boolean simulate) {
        if (amount <= 0) return 0;
        rebuildCache();
        List<ItemKey> matches = new ArrayList<>();
        for (var entry : flatCache.entrySet()) {
            if (pred.test(entry.getKey().toStack(1))) matches.add(entry.getKey());
        }
        long extracted = 0;
        for (ItemKey key : matches) {
            if (extracted >= amount) break;
            ItemStack got = extractItem(key, amount - extracted, simulate);
            extracted += got.getCount();
        }
        return extracted;
    }

    private static boolean matchesFilter(ItemKey key, String filterText, Level level) {
        if (filterText == null || filterText.isBlank()) return true;
        ItemStack stack = key.toStack(1);

        for (String token : filterText.toLowerCase().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.startsWith("@")) {
                String modid = token.substring(1);
                if (!stack.getItem().builtInRegistryHolder().key().location().getNamespace().equals(modid))
                    return false;
            } else if (token.startsWith("#")) {
                String tagName = token.substring(1);
                boolean found = false;
                var tags = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ITEM)
                        .getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, net.minecraft.resources.ResourceLocation.parse(tagName)));
                if (tags.isPresent()) {
                    for (var holder : tags.get()) {
                        if (holder.value() == stack.getItem()) { found = true; break; }
                    }
                }
                if (!found) return false;
            } else if (token.startsWith("$")) {
                String keyword = token.substring(1);
                if (!stack.getHoverName().getString().toLowerCase().contains(keyword))
                    return false;
            } else {
                if (!stack.getHoverName().getString().toLowerCase().contains(token))
                    return false;
            }
        }
        return true;
    }

    // ===== NBT =====

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag energyTag = new CompoundTag();
        for (Map.Entry<EnergyType, Long> entry : energy.entrySet()) {
            energyTag.putLong(entry.getKey().getId(), entry.getValue());
        }
        tag.put("energy", energyTag);

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
        CompoundTag energyTag = tag.getCompound("energy");
        for (EnergyType type : EnergyType.values()) {
            if (energyTag.contains(type.getId())) {
                energy.put(type, energyTag.getLong(type.getId()));
            }
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
            variants.put(key, count);
        }
        typeCount = 0;
        for (var variants : inventory.values()) typeCount += variants.size();
        cacheDirty = true;
    }

    // ===== Network =====

    public void rebuildNetwork(Level level) {
        boolean wasConflicted = conflicted;
        connectedBlocks.clear();
        totalTypeSlots = 0;
        conflicted = false;

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(getBlockPos());
        visited.add(getBlockPos());

        while (!queue.isEmpty()) {
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
        if (conflicted && !wasConflicted) {
            MagicStorage.LOGGER.warn("Storage network at {} has multiple cores; multi-core is unsupported, network disabled until extra cores removed.", getBlockPos());
        }
    }

    public boolean tryIncrementalAdd(Level level, BlockPos placedPos) {
        if (conflicted) return false;
        if (placedPos.equals(getBlockPos())) return false;
        if (connectedBlocks.contains(placedPos)) return false;

        BlockState state = level.getBlockState(placedPos);
        if (!(state.getBlock() instanceof IStorageNetworkBlock networkBlock)) return false;
        if (networkBlock.isStorageCore()) return false;

        boolean adjacent = false;
        for (Direction dir : Direction.values()) {
            if (connectedBlocks.contains(placedPos.relative(dir))) {
                adjacent = true;
                break;
            }
        }
        if (!adjacent) return false;

        connectedBlocks.add(placedPos);
        totalTypeSlots += capacityOf(state);
        return true;
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
            // Restore data from previously broken core at this position
            var tag = PENDING.remove(getBlockPos());
            if (tag != null) {
                loadAdditional(tag, level.registryAccess());
            }
            rebuildNetwork(level);
        }
    }

    public void onBreak() {
        connectedBlocks.clear();
        totalTypeSlots = 0;
        // Inventory and energy data preserved via PENDING map
    }

    public Set<BlockPos> getConnectedBlocks() { return connectedBlocks; }
    public int getTotalTypeSlots() { return totalTypeSlots; }
}
