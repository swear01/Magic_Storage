package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class TerminalBlock extends Block implements IStorageNetworkBlock {

    private final boolean crafting;

    public TerminalBlock(Properties properties, boolean crafting) {
        super(properties);
        this.crafting = crafting;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            StorageCoreBlockEntity core = MagicStorage.bfsFindCore(level, pos);
            if (core != null) {
                if (crafting) {
                    player.openMenu(new SimpleMenuProvider(
                            (containerId, inv, p) -> new CraftingTerminalMenu(containerId, inv, core),
                            Component.translatable("container.magic_storage.crafting_terminal")
                    ), buf -> buf.writeBlockPos(core.getBlockPos()));
                } else {
                    player.openMenu(new SimpleMenuProvider(
                            (containerId, inv, p) -> new StorageTerminalMenu(containerId, inv, core),
                            Component.translatable("container.magic_storage.storage_terminal")
                    ), buf -> buf.writeBlockPos(core.getBlockPos()));
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
