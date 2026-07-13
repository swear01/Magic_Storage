package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class StorageUnitBlock extends Block implements IStorageNetworkBlock {

    private final int typeContribution;

    public StorageUnitBlock(Properties properties, int typeContribution) {
        super(properties);
        this.typeContribution = typeContribution;
    }

    public int getTypeContribution() {
        return typeContribution;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!oldState.is(state.getBlock()) && !level.isClientSide()) {
            MagicStorage.scheduleNetworkGrowthAfterPlacement(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            MagicStorage.scheduleNetworkRebuildAfterRemoval(level, pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
