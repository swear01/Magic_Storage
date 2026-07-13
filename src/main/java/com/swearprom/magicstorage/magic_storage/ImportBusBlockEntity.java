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

import java.util.function.Consumer;
import java.util.List;

public class ImportBusBlockEntity extends BlockEntity {

    private static final int COOLDOWN_TICKS = 10;

    private BlockPos corePos;
    private StorageCoreBlockEntity cachedCore;
    private List<BlockPos> cachedPath = List.of();
    private int cooldown = 0;

    public ImportBusBlockEntity(BlockPos pos, BlockState state) {
        super(MagicStorage.IMPORT_BUS_BE.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide()) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = COOLDOWN_TICKS;

        StorageCoreBlockEntity core = resolveCore();
        if (core == null) {
            setChanged();
            return;
        }

        Direction facing = getBlockState().getValue(ImportBusBlock.FACING);
        BlockPos targetPos = getBlockPos().relative(facing);

        if (!level.hasChunkAt(targetPos)) return;

        BlockState targetState = level.getBlockState(targetPos);
        if (targetState.getBlock() instanceof IStorageNetworkBlock) return;

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, facing.getOpposite());
        if (handler == null) return;

        transferOneStack(core, handler, Actor.bus(getBlockPos()),
                stack -> net.minecraft.world.level.block.Block.popResource(level, getBlockPos(), stack));
    }

    static boolean transferOneStack(StorageCoreBlockEntity core, IItemHandler handler, Actor actor,
                                    Consumer<ItemStack> overflow) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack peek = handler.extractItem(slot, 64, true);
            if (peek.isEmpty()) continue;
            long accepted = core.insertItem(peek, Action.SIMULATE, actor);
            if (accepted <= 0) continue;
            ItemStack real = handler.extractItem(slot, (int) accepted, false);
            if (real.isEmpty()) continue;
            core.insertItem(real, Action.EXECUTE, actor);
            if (!real.isEmpty()) {
                ItemStack unreturned = handler.insertItem(slot, real.copy(), false);
                if (!unreturned.isEmpty()) overflow.accept(unreturned);
            }
            return true;
        }
        return false;
    }

    private StorageCoreBlockEntity resolveCore() {
        if (level == null) return null;
        if (cachedCore != null && !cachedCore.isRemoved()
                && cachedCore.getConnectedBlocks().contains(getBlockPos())) {
            if (MagicStorage.hasLoadedNetworkPath(level, cachedPath, getBlockPos(), cachedCore.getBlockPos())) {
                return cachedCore;
            }
            cachedPath = MagicStorage.findLoadedNetworkPath(level, getBlockPos(), cachedCore.getBlockPos());
            if (!cachedPath.isEmpty()) return cachedCore;
        }
        cachedCore = MagicStorage.bfsFindCore(level, getBlockPos());
        if (cachedCore != null && !cachedCore.getConnectedBlocks().contains(getBlockPos())) {
            cachedCore = null;
        }
        cachedPath = cachedCore != null
                ? MagicStorage.findLoadedNetworkPath(level, getBlockPos(), cachedCore.getBlockPos())
                : List.of();
        if (cachedCore != null && cachedPath.isEmpty()) cachedCore = null;
        corePos = cachedCore != null ? cachedCore.getBlockPos() : null;
        return cachedCore;
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
