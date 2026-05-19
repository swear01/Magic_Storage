package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class RemoteTerminalItem extends Item {
    private static final String TAG_CORE_X = "coreX";
    private static final String TAG_CORE_Y = "coreY";
    private static final String TAG_CORE_Z = "coreZ";
    private static final String TAG_DIMENSION = "dimension";

    public RemoteTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            if (!isBound(stack)) {
                player.displayClientMessage(Component.translatable("msg.magic_storage.remote_unbound"), true);
                return InteractionResultHolder.fail(stack);
            }
            BlockPos corePos = getBoundCorePos(stack);
            ResourceKey<Level> dimension = getBoundDimension(stack);

            if (!player.level().dimension().equals(dimension)) {
                player.displayClientMessage(Component.translatable("msg.magic_storage.remote_wrong_dimension"), true);
                return InteractionResultHolder.fail(stack);
            }
            if (!level.hasChunkAt(corePos)) {
                player.displayClientMessage(Component.translatable("msg.magic_storage.remote_core_unloaded"), true);
                return InteractionResultHolder.fail(stack);
            }

            StorageCoreBlockEntity core = MagicStorage.bfsFindCore(level, corePos);
            if (core == null) {
                player.displayClientMessage(Component.translatable("msg.magic_storage.remote_unbound"), true);
                return InteractionResultHolder.fail(stack);
            }

            player.openMenu(new SimpleMenuProvider(
                    (containerId, inv, p) -> new CraftingTerminalMenu(containerId, inv, core),
                    Component.translatable("container.magic_storage.crafting_terminal")
            ), buf -> buf.writeBlockPos(core.getBlockPos()));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        if (player != null && player.isShiftKeyDown()) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof IStorageNetworkBlock netBlock && netBlock.isStorageCore()) {
                if (!level.isClientSide()) {
                    bindToCore(stack, pos, level.dimension());
                    player.displayClientMessage(Component.translatable("msg.magic_storage.remote_bound"), true);
                }
                return InteractionResult.sidedSuccess(level.isClientSide());
            }
        }
        return InteractionResult.PASS;
    }

    private static boolean isBound(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return false;
        CompoundTag tag = customData.copyTag();
        return tag.contains(TAG_CORE_X) && tag.contains(TAG_DIMENSION);
    }

    private static BlockPos getBoundCorePos(ItemStack stack) {
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();
        return new BlockPos(tag.getInt(TAG_CORE_X), tag.getInt(TAG_CORE_Y), tag.getInt(TAG_CORE_Z));
    }

    private static ResourceKey<Level> getBoundDimension(ItemStack stack) {
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();
        ResourceLocation id = ResourceLocation.parse(tag.getString(TAG_DIMENSION));
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    private static void bindToCore(ItemStack stack, BlockPos pos, ResourceKey<Level> dimension) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_CORE_X, pos.getX());
        tag.putInt(TAG_CORE_Y, pos.getY());
        tag.putInt(TAG_CORE_Z, pos.getZ());
        tag.putString(TAG_DIMENSION, dimension.location().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (isBound(stack)) {
            tooltip.add(Component.translatable("tooltip.magic_storage.remote_bound",
                    getBoundCorePos(stack).toShortString()));
        } else {
            tooltip.add(Component.translatable("tooltip.magic_storage.remote_unbound"));
        }
    }
}
