package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

public class ExportBusBlockEntity extends BlockEntity {

    private static final int COOLDOWN_TICKS = 10;

    private BlockPos corePos;
    private StorageCoreBlockEntity cachedCore;
    private int cooldown = 0;
    private ItemKey filterItem = null;

    public ExportBusBlockEntity(BlockPos pos, BlockState state) {
        super(MagicStorage.EXPORT_BUS_BE.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide()) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = COOLDOWN_TICKS;

        if (cachedCore == null || cachedCore.isRemoved()) {
            cachedCore = MagicStorage.bfsFindCore(level, getBlockPos());
            corePos = cachedCore != null ? cachedCore.getBlockPos() : null;
            if (cachedCore == null) {
                setChanged();
                return;
            }
        }

        if (filterItem == null) return;

        Direction facing = getBlockState().getValue(ExportBusBlock.FACING);
        BlockPos targetPos = getBlockPos().relative(facing);

        if (!level.hasChunkAt(targetPos)) return;

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, facing.getOpposite());
        if (handler == null) return;

        ItemStack toExport = cachedCore.extractItem(filterItem, 1, true);
        if (toExport.isEmpty()) return;

        ItemStack leftoverSim = toExport.copy();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            leftoverSim = handler.insertItem(slot, leftoverSim, true);
            if (leftoverSim.isEmpty()) break;
        }
        if (!leftoverSim.isEmpty()) return;

        ItemStack extracted = cachedCore.extractItem(filterItem, 1, false);
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            extracted = handler.insertItem(slot, extracted, false);
            if (extracted.isEmpty()) break;
        }
        if (!extracted.isEmpty()) {
            cachedCore.insertItem(extracted);
        }
    }

    public void setFilter(ItemStack stack) {
        if (stack.isEmpty()) {
            filterItem = null;
        } else {
            filterItem = ItemKey.of(stack);
        }
        setChanged();
    }

    public ItemKey getFilter() {
        return filterItem;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (corePos != null) {
            tag.putInt("coreX", corePos.getX());
            tag.putInt("coreY", corePos.getY());
            tag.putInt("coreZ", corePos.getZ());
        }
        if (filterItem != null) {
            tag.put("filter", filterItem.toStack(1).save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("coreX")) {
            corePos = new BlockPos(tag.getInt("coreX"), tag.getInt("coreY"), tag.getInt("coreZ"));
        }
        if (tag.contains("filter")) {
            ItemStack.parse(registries, tag.getCompound("filter"))
                .ifPresent(stack -> filterItem = ItemKey.of(stack));
        }
    }
}
