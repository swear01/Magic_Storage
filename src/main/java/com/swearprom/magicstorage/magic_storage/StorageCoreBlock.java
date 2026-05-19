package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class StorageCoreBlock extends Block implements EntityBlock, IStorageNetworkBlock {

    public StorageCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isStorageCore() { return true; }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StorageCoreBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        if (type != MagicStorage.STORAGE_CORE_BE.get()) return null;
        return (l, p, s, be) -> { if (be instanceof StorageCoreBlockEntity core) core.tick(); };
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !moved) {
            if (level.getBlockEntity(pos) instanceof StorageCoreBlockEntity core) {
                var tag = new CompoundTag();
                core.saveAdditional(tag, level.registryAccess());
                StorageCoreBlockEntity.PENDING.put(pos.immutable(), tag);
                core.onBreak();
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        if (!level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof StorageCoreBlockEntity core) {
                core.rebuildNetwork(level);
            }
        }
    }
}
