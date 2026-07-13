package com.swearprom.magicstorage.magic_storage;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ItemAbilities;

import java.util.List;
import java.util.function.Predicate;

public final class MachineEnergyTable {
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
            Predicate<ItemStack> matcher
    ) {
        public boolean accepts(ItemStack stack) {
            return !stack.isEmpty() && matcher.test(stack);
        }

        public boolean generatesEnergy() {
            return energyType != null && energyPerTick > 0;
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            exact(Items.FURNACE, EnergyType.SMELTING_ENERGY, 1),
            exact(Items.BLAST_FURNACE, EnergyType.BLASTING_ENERGY, 1),
            exact(Items.SMOKER, EnergyType.SMOKING_ENERGY, 1),
            exact(Items.CAMPFIRE, EnergyType.CAMPFIRE_ENERGY, 1),
            exact(Items.BREWING_STAND, EnergyType.BREW_ENERGY, 1),
            exact(Items.CRAFTING_TABLE, null, 0),
            exact(Items.STONECUTTER, null, 0),
            exact(Items.SMITHING_TABLE, null, 0),
            new Entry(Items.IRON_AXE, null, 0,
                    stack -> stack.canPerformAction(ItemAbilities.AXE_STRIP)
                            || stack.canPerformAction(ItemAbilities.AXE_SCRAPE)
                            || stack.canPerformAction(ItemAbilities.AXE_WAX_OFF))
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
        return core != null && entry != null && entry.accepts(core.getMachineContainer().getItem(slot));
    }

    private static Entry exact(Item item, EnergyType energyType, int energyPerTick) {
        return new Entry(item, energyType, energyPerTick, stack -> stack.is(item));
    }
}
