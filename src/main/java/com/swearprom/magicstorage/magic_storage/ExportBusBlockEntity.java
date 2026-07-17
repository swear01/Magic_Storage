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

public class ExportBusBlockEntity extends BlockEntity {

    private static final int COOLDOWN_TICKS = 10;

    private BlockPos corePos;
    private StorageCoreBlockEntity cachedCore;
    private List<BlockPos> cachedPath = List.of();
    private int cooldown = 0;
    private ItemKey filterItem = null;
    private BusConfiguration busConfiguration = BusConfiguration.defaults(BusKind.EXPORT);

    public ExportBusBlockEntity(BlockPos pos, BlockState state) {
        super(MagicStorage.EXPORT_BUS_BE.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide()
                || !busConfiguration.supported()
                || !busConfiguration.automationEnabled()
                || busConfiguration.mode() != BusMode.DIRECTIONAL) return;

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

        Direction facing = getBlockState().getValue(ExportBusBlock.FACING);
        BlockPos targetPos = getBlockPos().relative(facing);

        if (!level.hasChunkAt(targetPos)) return;

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, facing.getOpposite());
        if (handler == null) return;

        BusFilterPolicy filterPolicy = BusFilterPolicy.compile(
                busConfiguration, level.registryAccess());
        List<ItemKey> candidates = core.getDisplayStacks().stream()
                .map(ItemKey::of)
                .toList();
        for (ItemKey candidate : filterPolicy.orderedCandidates(candidates)) {
            if (transferOneStack(core, candidate, handler, Actor.bus(getBlockPos()),
                    stack -> net.minecraft.world.level.block.Block.popResource(
                            level, getBlockPos(), stack))) return;
        }
    }

    static boolean transferOneStack(StorageCoreBlockEntity core, ItemKey filterItem, IItemHandler handler,
                                    Actor actor, Consumer<ItemStack> overflow) {
        ItemStack toExport = core.extractItem(filterItem, 64, Action.SIMULATE, actor);
        if (toExport.isEmpty()) return false;

        ItemStack leftoverSim = toExport.copy();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            leftoverSim = handler.insertItem(slot, leftoverSim, true);
            if (leftoverSim.isEmpty()) break;
        }
        int fits = toExport.getCount() - leftoverSim.getCount();
        if (fits <= 0) return false;

        ItemStack extracted = core.extractItem(filterItem, fits, Action.EXECUTE, actor);
        if (extracted.isEmpty()) return false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            extracted = handler.insertItem(slot, extracted, false);
            if (extracted.isEmpty()) break;
        }
        if (!extracted.isEmpty()) {
            core.insertItem(extracted, Action.EXECUTE, actor);
            if (!extracted.isEmpty()) overflow.accept(extracted.copy());
        }
        return true;
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

    public void setFilter(ItemStack stack) {
        if (!busConfiguration.supported()) return;
        if (stack.isEmpty()) {
            filterItem = null;
        } else {
            filterItem = ItemKey.of(stack);
        }
        busConfiguration = busConfiguration.withSingleExactFilter(stack);
        setChanged();
    }

    public ItemKey getFilter() {
        return filterItem;
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
        if (filterItem != null) {
            tag.put("filter", filterItem.toStack(1).save(registries));
        }
        busConfiguration.save(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        cooldown = 0;
        if (tag.contains("coreX")) {
            corePos = new BlockPos(tag.getInt("coreX"), tag.getInt("coreY"), tag.getInt("coreZ"));
        }
        busConfiguration = BusConfiguration.load(tag, BusKind.EXPORT, registries);
        filterItem = busConfiguration.filterRules().stream()
                .filter(rule -> rule.type() == BusFilterRule.Type.EXACT_STACK)
                .map(BusFilterRule::exactKey)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElse(null);
    }
}
