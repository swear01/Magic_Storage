package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class StorageCoreBlock extends Block implements EntityBlock, IStorageNetworkBlock {

    public StorageCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!oldState.is(state.getBlock()) && !level.isClientSide()) {
            MagicStorage.scheduleNetworkGrowthAfterPlacement(level, pos);
        }
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
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (!(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof StorageCoreBlockEntity core)) {
            return drops;
        }

        var tag = new CompoundTag();
        core.saveAdditional(tag, params.getLevel().registryAccess());
        for (ItemStack drop : drops) {
            if (drop.is(MagicStorage.STORAGE_CORE_ITEM.get())) {
                BlockItem.setBlockEntityData(drop, MagicStorage.STORAGE_CORE_BE.get(), tag.copy());
            }
        }
        return drops;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            if (!moved && level.getBlockEntity(pos) instanceof StorageCoreBlockEntity core) {
                core.onBreak();
            }
            MagicStorage.scheduleNetworkRebuildAfterRemoval(level, pos);
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
