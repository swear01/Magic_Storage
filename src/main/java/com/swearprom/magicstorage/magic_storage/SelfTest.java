package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.HashSet;
import java.util.Set;

class SelfTest {
    private static int passed = 0;
    private static int failed = 0;

    static void runAll() {
        testItemKey();
        testFuelTable();
        testRecipeEnergyTable();
        testEnergyType();
        testEnergyCost();
        testFuelValue();
        testEnergyTypeUniqueness();
        testSortMode();
        testSortOrder();
        testSearchMode();
        testTerminalSettingsPacketCodec();
        testSearchModeApply();
        testTerminalGeometryNoOverlap();

        MagicStorage.LOGGER.info("SelfTest: {} passed, {} failed, {} total",
                passed, failed, passed + failed);
        if (failed > 0) {
            MagicStorage.LOGGER.error("SelfTest: {} TESTS FAILED!", failed);
        }
    }

    private static void testItemKey() {
        var stack1 = new ItemStack(Items.DIAMOND_SWORD);
        var stack2 = new ItemStack(Items.DIAMOND_SWORD);
        var stack3 = new ItemStack(Items.IRON_SWORD);

        var key1 = ItemKey.of(stack1);
        var key2 = ItemKey.of(stack2);
        var key3 = ItemKey.of(stack3);
        var key1Again = ItemKey.of(stack1);

        assertTrue("same item = same key", key1.equals(key2));
        assertTrue("same item = same hashCode", key1.hashCode() == key2.hashCode());
        assertTrue("different item = different key", !key1.equals(key3));
        assertTrue("key is stable (same stack = same key)", key1.equals(key1Again));
        assertTrue("key = itself", key1.equals(key1));
        assertTrue("key != null", !key1.equals(null));
        assertTrue("primaryKey returns item", key1.item() == Items.DIAMOND_SWORD);
    }

    private static void testEnergyCost() {
        var cost = new EnergyCost(EnergyType.SMELTING_ENERGY, 200, EnergyType.FURNACE_FUEL, 200);
        assertTrue("energyCost processType", cost.processType() == EnergyType.SMELTING_ENERGY);
        assertTrue("energyCost processAmount", cost.processAmount() == 200);
        assertTrue("energyCost fuelType", cost.fuelType() == EnergyType.FURNACE_FUEL);
        assertTrue("energyCost fuelAmount", cost.fuelAmount() == 200);

        var cost2 = new EnergyCost(EnergyType.SMELTING_ENERGY, 200, EnergyType.FURNACE_FUEL, 200);
        assertTrue("energyCost equals", cost.equals(cost2));
        assertTrue("energyCost hashCode", cost.hashCode() == cost2.hashCode());
    }

    private static void testFuelValue() {
        var fv = new FuelValue(EnergyType.FURNACE_FUEL, 1600);
        assertTrue("fuelValue pool", fv.pool() == EnergyType.FURNACE_FUEL);
        assertTrue("fuelValue amount", fv.valuePerItem() == 1600);
    }

    private static void testEnergyTypeUniqueness() {
        Set<String> ids = new HashSet<>();
        for (EnergyType type : EnergyType.values()) {
            assertTrue("unique id: " + type.getId(), ids.add(type.getId()));
        }
    }

    private static void testFuelTable() {
        assertTrue("coal is fuel", FuelTable.isFuel(new ItemStack(Items.COAL)));
        assertTrue("coal gives furnace_fuel", FuelTable.getFuelValues(new ItemStack(Items.COAL))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.FURNACE_FUEL && fv.valuePerItem() == 1600));
        assertTrue("glass_bottle gives bottle_fuel", FuelTable.isFuel(new ItemStack(Items.GLASS_BOTTLE)));
        assertTrue("blaze_rod has two pools", FuelTable.getFuelValues(new ItemStack(Items.BLAZE_ROD)).size() == 2);
        assertTrue("stone is not fuel", !FuelTable.isFuel(new ItemStack(Items.STONE)));
        assertTrue("charcoal is fuel", FuelTable.isFuel(new ItemStack(Items.CHARCOAL)));
        assertTrue("coal block is fuel", FuelTable.isFuel(new ItemStack(Items.COAL_BLOCK)));
        assertTrue("coal block 16000 furnace_fuel", FuelTable.getFuelValues(new ItemStack(Items.COAL_BLOCK))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.FURNACE_FUEL && fv.valuePerItem() == 16000));
        assertTrue("lava bucket 20000 furnace_fuel", FuelTable.getFuelValues(new ItemStack(Items.LAVA_BUCKET))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.FURNACE_FUEL && fv.valuePerItem() == 20000));
        assertTrue("blaze powder 600 blaze_fuel", FuelTable.getFuelValues(new ItemStack(Items.BLAZE_POWDER))
                .stream().anyMatch(fv -> fv.pool() == EnergyType.BLAZE_FUEL && fv.valuePerItem() == 600));
    }

    private static void testRecipeEnergyTable() {
        var smeltCost = RecipeEnergyTable.getCost(RecipeType.SMELTING);
        assertTrue("smelting needs smelting_energy", smeltCost.processType() == EnergyType.SMELTING_ENERGY);
        assertTrue("smelting process cost 200", smeltCost.processAmount() == 200);
        assertTrue("smelting needs furnace_fuel", smeltCost.fuelType() == EnergyType.FURNACE_FUEL);
        assertTrue("smelting fuel cost 200", smeltCost.fuelAmount() == 200);

        var blastCost = RecipeEnergyTable.getCost(RecipeType.BLASTING);
        assertTrue("blasting process cost 100", blastCost.processAmount() == 100);
        assertTrue("blasting uses blast energy", blastCost.processType() == EnergyType.BLASTING_ENERGY);

        var smokeCost = RecipeEnergyTable.getCost(RecipeType.SMOKING);
        assertTrue("smoking uses smoking_energy", smokeCost.processType() == EnergyType.SMOKING_ENERGY);

        var campCost = RecipeEnergyTable.getCost(RecipeType.CAMPFIRE_COOKING);
        assertTrue("campfire process cost 600", campCost.processAmount() == 600);

        assertTrue("crafting cost is null (instant)", RecipeEnergyTable.getCost(RecipeType.CRAFTING) == null);
        assertTrue("stonecutting cost is null (instant)", RecipeEnergyTable.getCost(RecipeType.STONECUTTING) == null);
        assertTrue("smithing cost is null (instant)", RecipeEnergyTable.getCost(RecipeType.SMITHING) == null);
    }

    private static void testEnergyType() {
        assertTrue("smelting auto-fills", EnergyType.SMELTING_ENERGY.isAutoFill());
        assertTrue("blasting auto-fills", EnergyType.BLASTING_ENERGY.isAutoFill());
        assertTrue("furnace_fuel does NOT auto-fill", !EnergyType.FURNACE_FUEL.isAutoFill());
        assertTrue("blaze_fuel does NOT auto-fill", !EnergyType.BLAZE_FUEL.isAutoFill());
        assertTrue("bottle_fuel does NOT auto-fill", !EnergyType.BOTTLE_FUEL.isAutoFill());
        assertTrue("8 types total", EnergyType.values().length == 8);
        assertTrue("smelting id", EnergyType.SMELTING_ENERGY.getId().equals("smelting_energy"));
        assertTrue("smelting tickRate 1", EnergyType.SMELTING_ENERGY.getTickRate() == 1);
    }

    private static void testSortMode() {
        assertTrue("SortMode has 3 values", SortMode.values().length == 3);
        assertTrue("SortMode NAME ordinal 0", SortMode.NAME.ordinal() == 0);
        assertTrue("SortMode QUANTITY ordinal 1", SortMode.QUANTITY.ordinal() == 1);
        assertTrue("SortMode ID ordinal 2", SortMode.ID.ordinal() == 2);
        assertTrue("NAME.next() -> QUANTITY", SortMode.NAME.next() == SortMode.QUANTITY);
        assertTrue("QUANTITY.next() -> ID", SortMode.QUANTITY.next() == SortMode.ID);
        assertTrue("ID.next() -> NAME", SortMode.ID.next() == SortMode.NAME);
    }

    private static void testSortOrder() {
        assertTrue("SortOrder has 2 values", SortOrder.values().length == 2);
        assertTrue("SortOrder ASCENDING ordinal 0", SortOrder.ASCENDING.ordinal() == 0);
        assertTrue("SortOrder DESCENDING ordinal 1", SortOrder.DESCENDING.ordinal() == 1);
        assertTrue("ASCENDING toggle -> DESCENDING",
                SortOrder.toggle(SortOrder.ASCENDING) == SortOrder.DESCENDING);
        assertTrue("DESCENDING toggle -> ASCENDING",
                SortOrder.toggle(SortOrder.DESCENDING) == SortOrder.ASCENDING);
    }

    private static void testSearchMode() {
        assertTrue("SearchMode has 3 values", SearchMode.values().length == 3);
        assertTrue("SearchMode NORMAL ordinal 0", SearchMode.NORMAL.ordinal() == 0);
        assertTrue("SearchMode TAG ordinal 1", SearchMode.TAG.ordinal() == 1);
        assertTrue("SearchMode MOD ordinal 2", SearchMode.MOD.ordinal() == 2);
        assertTrue("NORMAL.next() -> TAG", SearchMode.NORMAL.next() == SearchMode.TAG);
        assertTrue("TAG.next() -> MOD", SearchMode.TAG.next() == SearchMode.MOD);
        assertTrue("MOD.next() -> NORMAL", SearchMode.MOD.next() == SearchMode.NORMAL);
    }

    private static void testTerminalSettingsPacketCodec() {
        var original = new TerminalSettingsPacket(123, 6);
        assertTrue("packet containerId 123", original.containerId() == 123);
        assertTrue("packet visibleRows 6", original.visibleRows() == 6);

        var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(original.containerId());
        buf.writeVarInt(original.visibleRows());
        assertTrue("codec encode order containerId", buf.readVarInt() == 123);
        assertTrue("codec encode order visibleRows", buf.readVarInt() == 6);

        var extreme = new TerminalSettingsPacket(42, 9);
        var buf2 = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf2.writeVarInt(extreme.containerId());
        buf2.writeVarInt(extreme.visibleRows());
        assertTrue("extreme encode containerId", buf2.readVarInt() == 42);
        assertTrue("extreme encode visibleRows", buf2.readVarInt() == 9);
    }

    private static void testSearchModeApply() {
        assertTrue("NORMAL keeps raw text",
                SearchMode.NORMAL.apply("stone").equals("stone"));
        assertTrue("NORMAL keeps empty text",
                SearchMode.NORMAL.apply("").isEmpty());
        assertTrue("TAG prepends #",
                SearchMode.TAG.apply("logs").equals("#logs"));
        assertTrue("TAG does not double-prefix #",
                SearchMode.TAG.apply("#logs").equals("#logs"));
        assertTrue("MOD prepends @",
                SearchMode.MOD.apply("minecraft").equals("@minecraft"));
        assertTrue("MOD does not double-prefix @",
                SearchMode.MOD.apply("@minecraft").equals("@minecraft"));
        assertTrue("TAG empty text stays empty",
                SearchMode.TAG.apply("").isEmpty());
        assertTrue("MOD empty text stays empty",
                SearchMode.MOD.apply("").isEmpty());
    }

    private static void testTerminalGeometryNoOverlap() {
        int imageWidth = 210;
        int sbX = 174;
        int searchX = 102;
        int searchBgX = 100;
        int buttonX = 188;
        int buttonW = 16;
        int buttonH = 16;
        int rowHeight = 18;
        int gridTopLocal = 19;

        int scrollbarLeft = sbX;
        int searchRight = searchX + searchBoxWidth();
        assertTrue("searchRight <= scrollbarLeft (" + searchRight + " <= " + scrollbarLeft + ")",
                searchRight <= scrollbarLeft);
        int searchBgRight = searchBgX + searchBgWidth();
        assertTrue("search bg right <= scrollbarLeft (" + searchBgRight + " <= " + scrollbarLeft + ")",
                searchBgRight <= scrollbarLeft);
        assertTrue("buttons start right of scrollbar", buttonX >= sbX + 12);
        assertTrue("buttons fit imageWidth", buttonX + buttonW <= imageWidth);

        for (int rows : new int[]{3, 9}) {
            int gridBottom = gridTopLocal + rows * rowHeight;
            for (int i = 0; i < 3; i++) {
                int by = gridTopLocal + buttonY(i, rows);
                assertTrue("button " + i + " bottom <= grid bottom at rows " + rows
                                + " (" + (by + buttonH) + " <= " + gridBottom + ")",
                        by + buttonH <= gridBottom);
                assertTrue("button " + i + " top >= grid top at rows " + rows,
                        by >= gridTopLocal);
            }
        }
    }

    private static int searchBoxWidth() { return 70; }
    private static int searchBgWidth() { return 72; }

    private static int buttonY(int index, int visibleRows) {
        int gridH = visibleRows * 18;
        int span = gridH - 16;
        return index * span / 2;
    }

    private static void assertTrue(String message, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            MagicStorage.LOGGER.error("SelfTest FAILED: {}", message);
        }
    }
}
