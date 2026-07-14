package com.swearprom.magicstorage.magic_storage;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Predicate;

public final class MachineEnergyTable {
    public enum Category {
        PROCESS,
        INSTANT,
        CONSUMABLE
    }

    public static final int FURNACE_SLOT = 0;
    public static final int BLAST_FURNACE_SLOT = 1;
    public static final int SMOKER_SLOT = 2;
    public static final int CAMPFIRE_SLOT = 3;
    public static final int BREWING_STAND_SLOT = 4;
    public static final int CRAFTING_TABLE_SLOT = 5;
    public static final int STONECUTTER_SLOT = 6;
    public static final int SMITHING_TABLE_SLOT = 7;
    public static final int AXE_SLOT = 8;

    public record Entry(
            Item machine,
            EnergyType energyType,
            int energyPerTick,
            Category category,
            Predicate<ItemStack> matcher
    ) {
        public boolean accepts(ItemStack stack) {
            return !stack.isEmpty() && matcher.test(stack);
        }

        public boolean generatesEnergy() {
            return energyType != null && energyPerTick > 0;
        }

        public int maxInstalledCount() {
            return switch (category) {
                case PROCESS -> 64;
                case INSTANT -> 1;
                case CONSUMABLE -> 0;
            };
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            exact(Items.FURNACE, EnergyType.SMELTING_ENERGY, 1, Category.PROCESS),
            exact(Items.BLAST_FURNACE, EnergyType.BLASTING_ENERGY, 1, Category.PROCESS),
            exact(Items.SMOKER, EnergyType.SMOKING_ENERGY, 1, Category.PROCESS),
            exact(Items.CAMPFIRE, EnergyType.CAMPFIRE_ENERGY, 1, Category.PROCESS),
            exact(Items.BREWING_STAND, EnergyType.BREW_ENERGY, 1, Category.PROCESS),
            exact(Items.CRAFTING_TABLE, null, 0, Category.INSTANT),
            exact(Items.STONECUTTER, null, 0, Category.INSTANT),
            exact(Items.SMITHING_TABLE, null, 0, Category.INSTANT),
            new Entry(Items.IRON_AXE, null, 0, Category.CONSUMABLE, AxeEnergy::accepts)
    );

    private MachineEnergyTable() {
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Entry get(int slot) {
        return slot >= 0 && slot < ENTRIES.size() ? ENTRIES.get(slot) : null;
    }

    public static int findSlot(ItemStack stack) {
        for (int slot = 0; slot < ENTRIES.size(); slot++) {
            if (ENTRIES.get(slot).accepts(stack)) return slot;
        }
        return -1;
    }

    public static boolean isInstalled(StorageCoreBlockEntity core, int slot) {
        Entry entry = get(slot);
        return core != null && entry != null && entry.maxInstalledCount() > 0
                && entry.accepts(core.getMachineContainer().getItem(slot));
    }

    private static Entry exact(
            Item item,
            EnergyType energyType,
            int energyPerTick,
            Category category
    ) {
        return new Entry(item, energyType, energyPerTick, category, stack -> stack.is(item));
    }
}
