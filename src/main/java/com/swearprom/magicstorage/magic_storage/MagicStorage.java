package com.swearprom.magicstorage.magic_storage;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

@Mod(MagicStorage.MODID)
public class MagicStorage {
    public static final String MODID = "magic_storage";
    public static final Logger LOGGER = LogUtils.getLogger();

    static final int NETWORK_SCAN_DEPTH = 64;
    static final int MAX_NETWORK_BLOCKS = 8192;
    private static final Map<Level, Set<BlockPos>> PENDING_NETWORK_GROWTH = new WeakHashMap<>();

    // === Registries ===
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<MachineDescriptor> MACHINE_DESCRIPTORS =
            MachineDescriptorApi.createDeferredRegister(MODID);
    public static final Registry<MachineDescriptor> MACHINE_DESCRIPTOR_REGISTRY =
            MACHINE_DESCRIPTORS.makeRegistry(builder -> builder.maxId(MachineDescriptorApi.MAX_DESCRIPTORS - 1));

    static {
        MachineEnergyTable.registerBuiltIns(MACHINE_DESCRIPTORS);
    }

    // === Core Block ===
    public static final DeferredBlock<Block> STORAGE_CORE = BLOCKS.register("storage_core",
            () -> new StorageCoreBlock(BlockBehaviour.Properties.of()
                    .strength(3.0F, 6.0F)));
    public static final DeferredItem<StorageCoreBlockItem> STORAGE_CORE_ITEM =
            ITEMS.register("storage_core", () -> new StorageCoreBlockItem(
                    STORAGE_CORE.get(), new Item.Properties()));

    // === Storage Unit Blocks (6 tiers: 10/25/50/100/200/400 type slots) ===
    public static final DeferredBlock<Block> STORAGE_UNIT_T1 = BLOCKS.register("storage_unit_t1",
            () -> new StorageUnitBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F), 10));
    public static final DeferredItem<BlockItem> STORAGE_UNIT_T1_ITEM =
            ITEMS.registerSimpleBlockItem("storage_unit_t1", STORAGE_UNIT_T1);

    public static final DeferredBlock<Block> STORAGE_UNIT_T2 = BLOCKS.register("storage_unit_t2",
            () -> new StorageUnitBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F), 25));
    public static final DeferredItem<BlockItem> STORAGE_UNIT_T2_ITEM =
            ITEMS.registerSimpleBlockItem("storage_unit_t2", STORAGE_UNIT_T2);

    public static final DeferredBlock<Block> STORAGE_UNIT_T3 = BLOCKS.register("storage_unit_t3",
            () -> new StorageUnitBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F), 50));
    public static final DeferredItem<BlockItem> STORAGE_UNIT_T3_ITEM =
            ITEMS.registerSimpleBlockItem("storage_unit_t3", STORAGE_UNIT_T3);

    public static final DeferredBlock<Block> STORAGE_UNIT_T4 = BLOCKS.register("storage_unit_t4",
            () -> new StorageUnitBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F), 100));
    public static final DeferredItem<BlockItem> STORAGE_UNIT_T4_ITEM =
            ITEMS.registerSimpleBlockItem("storage_unit_t4", STORAGE_UNIT_T4);

    public static final DeferredBlock<Block> STORAGE_UNIT_T5 = BLOCKS.register("storage_unit_t5",
            () -> new StorageUnitBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F), 200));
    public static final DeferredItem<BlockItem> STORAGE_UNIT_T5_ITEM =
            ITEMS.registerSimpleBlockItem("storage_unit_t5", STORAGE_UNIT_T5);

    public static final DeferredBlock<Block> STORAGE_UNIT_T6 = BLOCKS.register("storage_unit_t6",
            () -> new StorageUnitBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F), 400));
    public static final DeferredItem<BlockItem> STORAGE_UNIT_T6_ITEM =
            ITEMS.registerSimpleBlockItem("storage_unit_t6", STORAGE_UNIT_T6);

    // === Import Bus ===
    public static final DeferredBlock<Block> IMPORT_BUS = BLOCKS.register("import_bus",
            () -> new ImportBusBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F)));
    public static final DeferredItem<BlockItem> IMPORT_BUS_ITEM =
            ITEMS.registerSimpleBlockItem("import_bus", IMPORT_BUS);

    // === Export Bus ===
    public static final DeferredBlock<Block> EXPORT_BUS = BLOCKS.register("export_bus",
            () -> new ExportBusBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F)));
    public static final DeferredItem<BlockItem> EXPORT_BUS_ITEM =
            ITEMS.registerSimpleBlockItem("export_bus", EXPORT_BUS);

    // === Terminals ===
    public static final DeferredBlock<Block> STORAGE_TERMINAL = BLOCKS.register("storage_terminal",
            () -> new TerminalBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F), false));
    public static final DeferredItem<BlockItem> STORAGE_TERMINAL_ITEM =
            ITEMS.registerSimpleBlockItem("storage_terminal", STORAGE_TERMINAL);

    public static final DeferredBlock<Block> CRAFTING_TERMINAL = BLOCKS.register("crafting_terminal",
            () -> new TerminalBlock(BlockBehaviour.Properties.of().strength(2.0F, 4.0F), true));
    public static final DeferredItem<BlockItem> CRAFTING_TERMINAL_ITEM =
            ITEMS.registerSimpleBlockItem("crafting_terminal", CRAFTING_TERMINAL);

    public static final DeferredItem<Item> REMOTE_TERMINAL = ITEMS.register("remote_terminal",
            () -> new RemoteTerminalItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> WRENCH = ITEMS.register("wrench",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> GUIDE_BOOK = ITEMS.register("guide_book",
            () -> new GuideBookItem(new Item.Properties().stacksTo(1)));

    // === Menu Types ===
    public static final DeferredHolder<MenuType<?>, MenuType<StorageTerminalMenu>> STORAGE_TERMINAL_MENU =
            MENUS.register("storage_terminal", () -> IMenuTypeExtension.create(
                    (windowId, inv, buf) -> new StorageTerminalMenu(MagicStorage.STORAGE_TERMINAL_MENU.get(), windowId, inv, buf)));

    public static final DeferredHolder<MenuType<?>, MenuType<CraftingTerminalMenu>> CRAFTING_TERMINAL_MENU =
            MENUS.register("crafting_terminal", () -> IMenuTypeExtension.create(
                    (windowId, inv, buf) -> new CraftingTerminalMenu(windowId, inv, buf)));

    // === Core BlockEntity ===
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StorageCoreBlockEntity>> STORAGE_CORE_BE =
            BLOCK_ENTITIES.register("storage_core",
                    () -> BlockEntityType.Builder.of(StorageCoreBlockEntity::new, STORAGE_CORE.get()).build(null));

    // === Bus BlockEntities ===
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ImportBusBlockEntity>> IMPORT_BUS_BE =
            BLOCK_ENTITIES.register("import_bus",
                    () -> BlockEntityType.Builder.of(ImportBusBlockEntity::new, IMPORT_BUS.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ExportBusBlockEntity>> EXPORT_BUS_BE =
            BLOCK_ENTITIES.register("export_bus",
                    () -> BlockEntityType.Builder.of(ExportBusBlockEntity::new, EXPORT_BUS.get()).build(null));

    // === Creative Tab ===
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAGIC_STORAGE_TAB =
            CREATIVE_MODE_TABS.register("magic_storage",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.magic_storage"))
                            .icon(() -> STORAGE_CORE_ITEM.get().getDefaultInstance())
                            .displayItems((parameters, output) -> {
                                output.accept(STORAGE_CORE_ITEM.get());
                                output.accept(STORAGE_UNIT_T1_ITEM.get());
                                output.accept(STORAGE_UNIT_T2_ITEM.get());
                                output.accept(STORAGE_UNIT_T3_ITEM.get());
                                output.accept(STORAGE_UNIT_T4_ITEM.get());
                                output.accept(STORAGE_UNIT_T5_ITEM.get());
                                output.accept(STORAGE_UNIT_T6_ITEM.get());
                                output.accept(STORAGE_TERMINAL_ITEM.get());
                                output.accept(CRAFTING_TERMINAL_ITEM.get());
                                output.accept(REMOTE_TERMINAL.get());
                                output.accept(WRENCH.get());
                                output.accept(GUIDE_BOOK.get());
                                output.accept(IMPORT_BUS_ITEM.get());
                                output.accept(EXPORT_BUS_ITEM.get());
                            }).build());

    public MagicStorage(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        MENUS.register(modEventBus);
        MACHINE_DESCRIPTORS.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(WrenchActions::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::commonSetup);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.register(modEventBus);
        }
        modEventBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar(MODID).versioned("1.0");
            registrar.playToServer(SearchFilterPacket.TYPE, SearchFilterPacket.STREAM_CODEC, this::handleSearchFilter);
            registrar.playToServer(TerminalSettingsPacket.TYPE, TerminalSettingsPacket.STREAM_CODEC, this::handleTerminalSettings);
            registrar.playToServer(TerminalScrollPacket.TYPE, TerminalScrollPacket.STREAM_CODEC, this::handleTerminalScroll);
            registrar.playToServer(CraftingRecipeSelectionPacket.TYPE, CraftingRecipeSelectionPacket.STREAM_CODEC,
                    this::handleCraftingRecipeSelection);
            registrar.playToClient(MachineDescriptorStatePacket.TYPE, MachineDescriptorStatePacket.STREAM_CODEC,
                    this::handleMachineDescriptorState);
        });
        LOGGER.info("Magic Storage initialized.");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SelfTest::runAll);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                IMPORT_BUS_BE.get(),
                (bus, side) -> bus.passiveItemHandler());
    }

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(MODID)
                .then(Commands.literal("recover_core")
                        .executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            var summary = CoreRecoverySavedData.get(player.serverLevel())
                                    .reissueLatest(player.getUUID());
                            if (summary.isEmpty()) {
                                context.getSource().sendFailure(Component.translatable(
                                        "msg.magic_storage.core_recovery_none"));
                                return 0;
                            }
                            ItemStack stack = StorageCoreBlockItem.createRecoveryStack(summary.get());
                            if (!player.getInventory().add(stack)) {
                                player.drop(stack, false);
                            }
                            player.inventoryMenu.broadcastChanges();
                            context.getSource().sendSuccess(
                                    () -> Component.translatable(
                                            "msg.magic_storage.core_recovery_reissued",
                                            summary.get().typeCount(),
                                            summary.get().itemCount()),
                                    false);
                            return 1;
                        })));
    }

    private void handleSearchFilter(SearchFilterPacket packet, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            if (player == null || !(player.containerMenu instanceof StorageTerminalMenu menu)
                    || menu.containerId != packet.containerId()) return;
            StorageCoreBlockEntity core = menu.getCore(player.level());
            if (core != null && menu.applyFilter(core, packet.filter())) {
                menu.broadcastChanges();
            }
        });
    }

    private void handleMachineDescriptorState(
            MachineDescriptorStatePacket packet,
            net.neoforged.neoforge.network.handling.IPayloadContext ctx
    ) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            if (player != null && player.containerMenu instanceof CraftingTerminalMenu menu
                    && menu.containerId == packet.containerId()) {
                menu.applyDescriptorStates(packet.states());
            }
        });
    }

    private void handleTerminalSettings(TerminalSettingsPacket packet, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            if (player == null || !(player.containerMenu instanceof StorageTerminalMenu menu)
                    || menu.containerId != packet.containerId()) return;
            if (menu.applySettings(packet)) {
                StorageCoreBlockEntity core = menu.getCore(player.level());
                if (core != null) {
                    menu.refreshDisplayItems(core);
                    menu.broadcastChanges();
                }
            }
        });
    }

    private void handleTerminalScroll(TerminalScrollPacket packet,
                                      net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            if (player == null || !(player.containerMenu instanceof StorageTerminalMenu menu)
                    || menu.containerId != packet.containerId()) return;
            StorageCoreBlockEntity core = menu.getCore(player.level());
            if (core != null) {
                menu.scrollTo(packet.offset());
                menu.refreshDisplayItems(core);
                menu.broadcastChanges();
            }
        });
    }

    private void handleCraftingRecipeSelection(CraftingRecipeSelectionPacket packet,
                                                net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            if (player == null || !(player.containerMenu instanceof CraftingTerminalMenu menu)
                    || menu.containerId != packet.containerId()) return;
            menu.handleRecipeRequest(player.level(), packet.recipeId(), packet.amount(), packet.destination(), player);
        });
    }

    static void scheduleNetworkRebuildAfterRemoval(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        var server = level.getServer();
        if (server != null) {
            server.execute(() -> findCoresAndRebuild(level, pos));
        } else {
            findCoresAndRebuild(level, pos);
        }
    }

    static void scheduleNetworkGrowthAfterPlacement(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        var server = level.getServer();
        if (server == null) {
            findCoresAndGrow(level, pos);
            return;
        }
        Set<BlockPos> pending = PENDING_NETWORK_GROWTH.computeIfAbsent(level, ignored -> new HashSet<>());
        boolean schedule = pending.isEmpty();
        pending.add(pos.immutable());
        if (schedule) {
            server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1,
                    () -> flushNetworkGrowth(level)));
        }
    }

    private static void flushNetworkGrowth(Level level) {
        Set<BlockPos> pending = PENDING_NETWORK_GROWTH.remove(level);
        if (pending == null || pending.isEmpty()) return;
        if (pending.size() == 1) {
            findCoresAndGrow(level, pending.iterator().next());
            return;
        }

        Set<BlockPos> corePositions = new HashSet<>();
        for (BlockPos pos : pending) {
            if (level.getBlockEntity(pos) instanceof StorageCoreBlockEntity) {
                corePositions.add(pos);
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = pos.relative(direction);
                if (pending.contains(neighbor) || !level.hasChunkAt(neighbor)) continue;
                if (!(level.getBlockState(neighbor).getBlock() instanceof IStorageNetworkBlock)) continue;
                StorageCoreBlockEntity core = bfsFindCore(level, neighbor);
                if (core != null) corePositions.add(core.getBlockPos());
            }
        }
        for (BlockPos corePos : corePositions) {
            if (level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core) {
                core.rebuildNetwork(level);
            }
        }
    }

    static void findCoresAndRebuild(Level level, BlockPos changedPos) {
        Set<BlockPos> foundCores = new HashSet<>();

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = changedPos.relative(dir);
            if (!level.hasChunkAt(neighbor)) continue;

            var state = level.getBlockState(neighbor);
            if (state.getBlock() instanceof IStorageNetworkBlock) {
                StorageCoreBlockEntity core = bfsFindCore(level, neighbor);
                if (core != null) {
                    foundCores.add(core.getBlockPos());
                }
            }
        }

        if (foundCores.isEmpty()) {
            StorageCoreBlockEntity selfCore = bfsFindCore(level, changedPos);
            if (selfCore != null) {
                foundCores.add(selfCore.getBlockPos());
            }
        }

        for (BlockPos corePos : foundCores) {
            if (level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core) {
                core.rebuildNetwork(level);
            }
        }
    }

    static void findCoresAndGrow(Level level, BlockPos placedPos) {
        Set<BlockPos> foundCores = new HashSet<>();
        boolean placedCore = false;

        var placedState = level.getBlockState(placedPos);
        if (placedState.getBlock() instanceof IStorageNetworkBlock placedNetworkBlock) {
            placedCore = placedNetworkBlock.isStorageCore();
        }

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = placedPos.relative(dir);
            if (!level.hasChunkAt(neighbor)) continue;

            var state = level.getBlockState(neighbor);
            if (state.getBlock() instanceof IStorageNetworkBlock) {
                StorageCoreBlockEntity core = bfsFindCore(level, neighbor);
                if (core != null) {
                    foundCores.add(core.getBlockPos());
                }
            }
        }

        if (foundCores.isEmpty()) {
            StorageCoreBlockEntity selfCore = bfsFindCore(level, placedPos);
            if (selfCore != null) {
                foundCores.add(selfCore.getBlockPos());
            }
        }

        boolean uncertainTopology = placedCore || foundCores.size() > 1;
        for (BlockPos corePos : foundCores) {
            if (level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core
                    && touchesDetachedNetworkSegment(level, core, placedPos)) {
                uncertainTopology = true;
                break;
            }
        }

        if (uncertainTopology) {
            foundCores = bfsFindCorePositions(level, placedPos);
        }

        for (BlockPos corePos : foundCores) {
            if (level.getBlockEntity(corePos) instanceof StorageCoreBlockEntity core) {
                if (uncertainTopology || !core.tryIncrementalAdd(level, placedPos)) {
                    core.rebuildNetwork(level);
                }
            }
        }
    }

    private static boolean touchesDetachedNetworkSegment(Level level, StorageCoreBlockEntity core, BlockPos placedPos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = placedPos.relative(dir);
            if (!level.hasChunkAt(neighbor)) continue;
            if (neighbor.equals(core.getBlockPos()) || core.getConnectedBlocks().contains(neighbor)) continue;
            if (level.getBlockState(neighbor).getBlock() instanceof IStorageNetworkBlock) return true;
        }
        return false;
    }

    private static Set<BlockPos> bfsFindCorePositions(Level level, BlockPos start) {
        Set<BlockPos> cores = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        int depth = 0;
        while (!queue.isEmpty() && depth < NETWORK_SCAN_DEPTH && visited.size() <= MAX_NETWORK_BLOCKS) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                BlockPos current = queue.poll();
                var state = level.getBlockState(current);
                var block = state.getBlock();

                if (block instanceof IStorageNetworkBlock netBlock) {
                    if (netBlock.isStorageCore() && level.getBlockEntity(current) instanceof StorageCoreBlockEntity) {
                        cores.add(current);
                    }

                    for (Direction dir : Direction.values()) {
                        BlockPos next = current.relative(dir);
                        if (!visited.contains(next) && level.hasChunkAt(next)) {
                            visited.add(next);
                            if (level.getBlockState(next).getBlock() instanceof IStorageNetworkBlock) {
                                queue.add(next);
                            }
                        }
                    }
                }
            }
            depth++;
        }
        return cores;
    }

    static StorageCoreBlockEntity bfsFindCore(Level level, BlockPos start) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        int depth = 0;
        while (!queue.isEmpty() && depth < NETWORK_SCAN_DEPTH && visited.size() <= MAX_NETWORK_BLOCKS) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                BlockPos current = queue.poll();
                var state = level.getBlockState(current);
                var block = state.getBlock();

                if (block instanceof IStorageNetworkBlock netBlock) {
                    if (netBlock.isStorageCore()) {
                        if (level.getBlockEntity(current) instanceof StorageCoreBlockEntity core) {
                            return core;
                        }
                    }

                    for (Direction dir : Direction.values()) {
                        BlockPos next = current.relative(dir);
                        if (!visited.contains(next) && level.hasChunkAt(next)) {
                            visited.add(next);
                            if (level.getBlockState(next).getBlock() instanceof IStorageNetworkBlock) {
                                queue.add(next);
                            }
                        }
                    }
                }
            }
            depth++;
        }
        return null;
    }

    static List<BlockPos> findLoadedNetworkPath(Level level, BlockPos start, BlockPos target) {
        if (level == null || start == null || target == null
                || !level.hasChunkAt(start) || !level.hasChunkAt(target)) return List.of();

        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        int depth = 0;
        while (!queue.isEmpty() && depth < NETWORK_SCAN_DEPTH && visited.size() <= MAX_NETWORK_BLOCKS) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                BlockPos current = queue.remove();
                if (!(level.getBlockState(current).getBlock() instanceof IStorageNetworkBlock)) continue;
                if (current.equals(target)) {
                    List<BlockPos> path = new ArrayList<>();
                    for (BlockPos cursor = target; cursor != null; cursor = previous.get(cursor)) {
                        path.add(cursor);
                    }
                    Collections.reverse(path);
                    return List.copyOf(path);
                }
                for (Direction direction : Direction.values()) {
                    BlockPos next = current.relative(direction);
                    if (!visited.contains(next) && level.hasChunkAt(next)) {
                        visited.add(next);
                        if (level.getBlockState(next).getBlock() instanceof IStorageNetworkBlock) {
                            previous.put(next, current);
                            queue.add(next);
                        }
                    }
                }
            }
            depth++;
        }
        return List.of();
    }

    static boolean hasLoadedNetworkPath(Level level, List<BlockPos> path, BlockPos start, BlockPos target) {
        return level != null && isValidNetworkPath(path, start, target,
                pos -> level.hasChunkAt(pos),
                pos -> level.getBlockState(pos).getBlock() instanceof IStorageNetworkBlock);
    }

    static boolean isValidNetworkPath(
            List<BlockPos> path,
            BlockPos start,
            BlockPos target,
            Predicate<BlockPos> loaded,
            Predicate<BlockPos> networkBlock
    ) {
        if (path == null || path.isEmpty() || path.size() > NETWORK_SCAN_DEPTH
                || !path.getFirst().equals(start) || !path.getLast().equals(target)) return false;
        BlockPos previous = null;
        for (BlockPos pos : path) {
            if (!loaded.test(pos) || !networkBlock.test(pos)) return false;
            if (previous != null && previous.distManhattan(pos) != 1) return false;
            previous = pos;
        }
        return true;
    }
}
