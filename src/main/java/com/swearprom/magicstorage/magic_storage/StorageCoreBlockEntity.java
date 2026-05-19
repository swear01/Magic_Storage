package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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

public class StorageCoreBlockEntity extends BlockEntity {

    public static final Map<BlockPos, CompoundTag> PENDING = new HashMap<>();

    private final Set<BlockPos> connectedBlocks = new HashSet<>();
    private int totalTypeSlots = 0;

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
        for (EnergyType type : EnergyType.values()) {
            if (type.isAutoFill()) {
                energy.merge(type, (long) type.getTickRate(), Long::sum);
            }
        }
    }

    public long getEnergy(EnergyType type) {
        return energy.getOrDefault(type, 0L);
    }

    public boolean consumeEnergy(EnergyCost cost, long multiplier) {
        long processNeed = cost.processAmount() * multiplier;
        long fuelNeed = cost.fuelAmount() * multiplier;
        if (getEnergy(cost.processType()) < processNeed) return false;
        if (getEnergy(cost.fuelType()) < fuelNeed) return false;
        energy.merge(cost.processType(), -processNeed, Long::sum);
        energy.merge(cost.fuelType(), -fuelNeed, Long::sum);
        return true;
    }

    public void addFuel(ItemStack stack, EnergyType targetPool) {
        List<FuelValue> values = FuelTable.getFuelValues(stack);
        for (FuelValue fv : values) {
            if (fv.pool() == targetPool) {
                long amount = fv.valuePerItem() * stack.getCount();
                energy.merge(targetPool, amount, Long::sum);
                stack.setCount(0);
                return;
            }
        }
    }

    public boolean isFuel(ItemStack stack) {
        return FuelTable.isFuel(stack);
    }

    public List<FuelValue> getCompatiblePools(ItemStack stack) {
        return FuelTable.getFuelValues(stack);
    }

    public int getTypeCount() {
        int count = 0;
        for (var variantMap : inventory.values()) {
            count += variantMap.size();
        }
        return count;
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
        if (stack.isEmpty()) return 0;
        ItemKey key = ItemKey.of(stack);
        Item primary = key.item();

        var variants = inventory.computeIfAbsent(primary, k -> new Object2LongOpenHashMap<>());
        long existing = variants.getLong(key);
        long toInsert = stack.getCount();

        if (existing == 0) {
            if (getTypeCount() >= totalTypeSlots) return 0;
        }

        long inserted = toInsert;
        if (!simulate) {
            variants.put(key, existing + inserted);
            cacheDirty = true;
            stack.setCount(0);
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
        if (!simulate) {
            long remaining = existing - toExtract;
            if (remaining <= 0) {
                variants.removeLong(key);
                if (variants.isEmpty()) inventory.remove(primary);
            } else {
                variants.put(key, remaining);
            }
            cacheDirty = true;
        }

        ItemStack result = key.toStack((int) toExtract);
        result.setCount((int) toExtract);
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
                    stack.setCount((int) Math.min(count, stack.getMaxStackSize()));
                    result.add(stack);
                }
            }
        }
        return result;
    }

    public long getItemCount(ItemKey key) {
        Item primary = key.item();
        var variants = inventory.get(primary);
        if (variants == null) return 0;
        return variants.getLong(key);
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
                entryTag.putString("id", variantEntry.getKey().item().builtInRegistryHolder().key().location().toString());
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
            var itemId = net.minecraft.resources.ResourceLocation.parse(entryTag.getString("id"));
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
            if (item == null) continue;
            ItemStack stack = new ItemStack(item);
            long count = entryTag.getLong("count");
            ItemKey key = ItemKey.of(stack);
            Item primary = key.item();
            var variants = inventory.computeIfAbsent(primary, k -> new Object2LongOpenHashMap<>());
            variants.put(key, count);
        }
        cacheDirty = true;
    }

    // ===== Network =====

    public void rebuildNetwork(Level level) {
        connectedBlocks.clear();
        totalTypeSlots = 0;

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(getBlockPos());
        visited.add(getBlockPos());

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            BlockState state = level.getBlockState(current);
            if (state.getBlock() instanceof IStorageNetworkBlock networkBlock) {
                if (networkBlock.isStorageCore() && !current.equals(getBlockPos())) {
                    continue;
                }
                connectedBlocks.add(current);
                if (state.getBlock() instanceof StorageUnitBlock unitBlock) {
                    totalTypeSlots += unitBlock.getTypeContribution();
                }
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
