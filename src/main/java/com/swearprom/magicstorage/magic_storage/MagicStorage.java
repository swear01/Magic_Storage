package com.swearprom.magicstorage.magic_storage;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

@Mod(MagicStorage.MODID)
public class MagicStorage {
    public static final String MODID = "magic_storage";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final int NETWORK_SCAN_DEPTH = 64;

    // === Registries ===
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);

    // === Core Block ===
    public static final DeferredBlock<Block> STORAGE_CORE = BLOCKS.register("storage_core",
            () -> new StorageCoreBlock(BlockBehaviour.Properties.of()
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> STORAGE_CORE_ITEM =
            ITEMS.registerSimpleBlockItem("storage_core", STORAGE_CORE);

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
                                output.accept(IMPORT_BUS_ITEM.get());
                                output.accept(EXPORT_BUS_ITEM.get());
                            }).build());

    public MagicStorage(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        MENUS.register(modEventBus);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onBlockPlaced);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onBlockBroken);
        modEventBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar(MODID).versioned("1.0");
            registrar.playToServer(SearchFilterPacket.TYPE, SearchFilterPacket.STREAM_CODEC, this::handleSearchFilter);
        });
        SelfTest.runAll();
        LOGGER.info("Magic Storage initialized.");
    }

    private void handleSearchFilter(SearchFilterPacket packet, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        var player = ctx.player();
        if (player == null || !(player.containerMenu instanceof StorageTerminalMenu menu)
                || menu.containerId != packet.containerId()) return;
        if (player.level().getBlockEntity(menu.getCorePos()) instanceof StorageCoreBlockEntity core) {
            menu.refreshDisplayItemsFiltered(core, packet.filter());
        }
    }

    private void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) return;
        Level level = (Level) event.getLevel();
        BlockPos pos = event.getPos();
        if (level.getBlockState(pos).getBlock() instanceof IStorageNetworkBlock) {
            findCoresAndRebuild(level, pos);
        }
    }

    private void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        Level level = (Level) event.getLevel();
        if (event.getState().getBlock() instanceof IStorageNetworkBlock) {
            findCoresAndRebuild(level, event.getPos());
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

    static StorageCoreBlockEntity bfsFindCore(Level level, BlockPos start) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        int depth = 0;
        while (!queue.isEmpty() && depth < NETWORK_SCAN_DEPTH) {
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
}
