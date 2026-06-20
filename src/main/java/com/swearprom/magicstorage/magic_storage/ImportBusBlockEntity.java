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

public class ImportBusBlockEntity extends BlockEntity {

    private static final int COOLDOWN_TICKS = 10;

    private BlockPos corePos;
    private StorageCoreBlockEntity cachedCore;
    private int cooldown = 0;

    public ImportBusBlockEntity(BlockPos pos, BlockState state) {
        super(MagicStorage.IMPORT_BUS_BE.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide()) return;

        if (cachedCore == null || cachedCore.isRemoved()) {
            cachedCore = MagicStorage.bfsFindCore(level, getBlockPos());
            corePos = cachedCore != null ? cachedCore.getBlockPos() : null;
            if (cachedCore == null) {
                setChanged();
                return;
            }
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = COOLDOWN_TICKS;

        Direction facing = getBlockState().getValue(ImportBusBlock.FACING);
        BlockPos targetPos = getBlockPos().relative(facing);

        if (!level.hasChunkAt(targetPos)) return;

        BlockState targetState = level.getBlockState(targetPos);
        if (targetState.getBlock() instanceof IStorageNetworkBlock) return;

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, facing.getOpposite());
        if (handler == null) return;

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack peek = handler.extractItem(slot, 1, true);
            if (peek.isEmpty()) continue;
            if (cachedCore.insertItem(peek, true) <= 0) continue;
            ItemStack real = handler.extractItem(slot, 1, false);
            if (real.isEmpty()) continue;
            long inserted = cachedCore.insertItem(real);
            if (inserted < real.getCount()) {
                real.setCount((int) (real.getCount() - inserted));
                handler.insertItem(slot, real, false);
            }
            break;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (corePos != null) {
            tag.putInt("coreX", corePos.getX());
            tag.putInt("coreY", corePos.getY());
            tag.putInt("coreZ", corePos.getZ());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("coreX")) {
            corePos = new BlockPos(tag.getInt("coreX"), tag.getInt("coreY"), tag.getInt("coreZ"));
        }
    }
}
