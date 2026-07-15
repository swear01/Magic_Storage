package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
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
import java.util.UUID;
import java.util.function.BiConsumer;

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

        if (params.getLevel() instanceof ServerLevel serverLevel
                && core.getPreparedRecoveryId().isPresent()) {
            UUID recoveryId = core.getPreparedRecoveryId().orElseThrow();
            CoreRecoverySavedData.RecoverySummary summary = CoreRecoverySavedData.get(serverLevel)
                    .summary(recoveryId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Prepared Core recovery snapshot is missing: " + recoveryId));
            for (int index = 0; index < drops.size(); index++) {
                if (drops.get(index).is(MagicStorage.STORAGE_CORE_ITEM.get())) {
                    drops.set(index, StorageCoreBlockItem.createRecoveryStack(summary));
                }
            }
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
    public void playerDestroy(
            Level level,
            Player player,
            BlockPos pos,
            BlockState state,
            @Nullable BlockEntity blockEntity,
            ItemStack tool
    ) {
        if (level instanceof ServerLevel serverLevel
                && blockEntity instanceof StorageCoreBlockEntity core
                && core.hasRecoverableContents()) {
            core.prepareRecoveryDrop(serverLevel, player.getUUID());
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel serverLevel
                && player.isCreative()
                && level.getBlockEntity(pos) instanceof StorageCoreBlockEntity core
                && core.hasRecoverableContents()) {
            UUID recoveryId = core.prepareRecoveryDrop(serverLevel, player.getUUID());
            CoreRecoverySavedData.RecoverySummary summary = CoreRecoverySavedData.get(serverLevel)
                    .summary(recoveryId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Prepared creative Core recovery snapshot is missing: " + recoveryId));
            ItemEntity drop = new ItemEntity(
                    level,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    StorageCoreBlockItem.createRecoveryStack(summary));
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onExplosionHit(
            BlockState state,
            Level level,
            BlockPos pos,
            Explosion explosion,
            BiConsumer<ItemStack, BlockPos> dropConsumer
    ) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof StorageCoreBlockEntity core
                && core.hasRecoverableContents()) {
            UUID owner = explosion.getIndirectSourceEntity() instanceof Player player
                    ? player.getUUID() : null;
            core.prepareRecoveryDrop(serverLevel, owner);
        }
        super.onExplosionHit(state, level, pos, explosion, dropConsumer);
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack
    ) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel)) return;
        var recoveryId = StorageCoreBlockItem.getRecoveryId(stack);
        if (recoveryId.isEmpty()) return;
        if (!(level.getBlockEntity(pos) instanceof StorageCoreBlockEntity core)) {
            throw new IllegalStateException("Placed Storage Core block entity is missing at " + pos);
        }
        CompoundTag contents = CoreRecoverySavedData.get(serverLevel)
                .claim(recoveryId.get())
                .orElseThrow(() -> new IllegalStateException(
                        "Core recovery token was consumed before placement: " + recoveryId.get()));
        core.loadAdditional(contents, serverLevel.registryAccess());
        core.setChanged();
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
