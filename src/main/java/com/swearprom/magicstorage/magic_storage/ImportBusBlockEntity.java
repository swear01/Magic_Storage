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

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class ImportBusBlockEntity extends BlockEntity {

    private static final int COOLDOWN_TICKS = 10;

    private BlockPos corePos;
    private StorageCoreBlockEntity cachedCore;
    private List<BlockPos> cachedPath = List.of();
    private int cooldown = 0;
    private long nextCoreSearchTick = Long.MIN_VALUE;
    private BusConfiguration busConfiguration = BusConfiguration.defaults(BusKind.IMPORT);
    private final IItemHandler passiveItemHandler = new IItemHandler() {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 0 || stack.isEmpty() || level == null || level.isClientSide()
                    || !busConfiguration.supported()) return stack.copy();
            StorageCoreBlockEntity core = resolveCore();
            if (core == null || core.isConflicted()) return stack.copy();
            Actor actor = Actor.bus(getBlockPos());
            ItemStack probe = stack.copy();
            long accepted = core.insertItem(probe, Action.SIMULATE, actor);
            if (accepted <= 0) return stack.copy();
            if (!simulate) {
                ItemStack committed = stack.copyWithCount((int) Math.min(accepted, stack.getCount()));
                accepted = core.insertItem(committed, Action.EXECUTE, actor);
            }
            if (accepted >= stack.getCount()) return ItemStack.EMPTY;
            return stack.copyWithCount(stack.getCount() - (int) accepted);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && !stack.isEmpty();
        }
    };

    public ImportBusBlockEntity(BlockPos pos, BlockState state) {
        super(MagicStorage.IMPORT_BUS_BE.get(), pos, state);
    }

    IItemHandler passiveItemHandler() {
        return passiveItemHandler;
    }

    public void tick() {
        if (level == null || level.isClientSide() || !busConfiguration.supported()) return;

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
        cachedCore = null;
        cachedPath = List.of();
        corePos = null;
        long gameTime = level.getGameTime();
        if (gameTime < nextCoreSearchTick) return null;
        cachedCore = MagicStorage.bfsFindCore(level, getBlockPos());
        if (cachedCore != null && !cachedCore.getConnectedBlocks().contains(getBlockPos())) {
            cachedCore = null;
        }
        cachedPath = cachedCore != null
                ? MagicStorage.findLoadedNetworkPath(level, getBlockPos(), cachedCore.getBlockPos())
                : List.of();
        if (cachedCore != null && cachedPath.isEmpty()) cachedCore = null;
        corePos = cachedCore != null ? cachedCore.getBlockPos() : null;
        nextCoreSearchTick = cachedCore == null ? gameTime + COOLDOWN_TICKS : Long.MIN_VALUE;
        return cachedCore;
    }

    public void assignOwnerOnPlacement(UUID owner) {
        BusConfiguration assigned = busConfiguration.assignInitialOwner(owner);
        if (assigned.equals(busConfiguration)) return;
        busConfiguration = assigned;
        setChanged();
    }

    public BusConfiguration getBusConfiguration() {
        return busConfiguration;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (corePos != null) {
            tag.putInt("coreX", corePos.getX());
            tag.putInt("coreY", corePos.getY());
            tag.putInt("coreZ", corePos.getZ());
        }
        busConfiguration.save(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("coreX")) {
            corePos = new BlockPos(tag.getInt("coreX"), tag.getInt("coreY"), tag.getInt("coreZ"));
        }
        busConfiguration = BusConfiguration.load(tag, BusKind.IMPORT, registries);
    }
}
