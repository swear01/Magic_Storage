package com.swearprom.magicstorage.magic_storage;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class TerminalClientPreferences {
    private static final String AUTO_FUEL_TARGET = "auto";
    private static final Values VALUES;
    static final ModConfigSpec SPEC;

    static {
        Pair<Values, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Values::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    private TerminalClientPreferences() {
    }

    static TerminalPreferences load() {
        return new TerminalPreferences(
                VALUES.sortMode.get(),
                VALUES.sortOrder.get(),
                VALUES.searchMode.get(),
                VALUES.resourceView.get(),
                VALUES.page.get(),
                VALUES.usePlayerInventory.get(),
                VALUES.outputDestination.get(),
                fuelTarget(VALUES.fuelTarget.get()));
    }

    static void save(TerminalPreferences preferences) {
        boolean changed = setIfChanged(VALUES.sortMode, preferences.sortMode());
        changed |= setIfChanged(VALUES.sortOrder, preferences.sortOrder());
        changed |= setIfChanged(VALUES.searchMode, preferences.searchMode());
        changed |= setIfChanged(VALUES.resourceView, preferences.resourceView());
        changed |= setIfChanged(VALUES.page, preferences.page());
        changed |= setIfChanged(VALUES.usePlayerInventory, preferences.usePlayerInventory());
        changed |= setIfChanged(VALUES.outputDestination, preferences.outputDestination());
        changed |= setIfChanged(VALUES.fuelTarget,
                preferences.fuelTarget() == null
                        ? AUTO_FUEL_TARGET
                        : preferences.fuelTarget().getId());
        if (changed) SPEC.save();
    }

    private static EnergyType fuelTarget(String id) {
        if (AUTO_FUEL_TARGET.equals(id)) return null;
        for (EnergyType type : EnergyType.values()) {
            if (!type.isMachineGenerated() && type.getId().equals(id)) return type;
        }
        throw new IllegalStateException("Unknown configured fuel target " + id);
    }

    private static <T> boolean setIfChanged(ModConfigSpec.ConfigValue<T> value, T next) {
        if (Objects.equals(value.get(), next)) return false;
        value.set(next);
        return true;
    }

    private static List<String> fuelTargetIds() {
        List<String> result = new ArrayList<>();
        result.add(AUTO_FUEL_TARGET);
        for (EnergyType type : EnergyType.values()) {
            if (!type.isMachineGenerated()) result.add(type.getId());
        }
        return result;
    }

    private static final class Values {
        private final ModConfigSpec.EnumValue<SortMode> sortMode;
        private final ModConfigSpec.EnumValue<SortOrder> sortOrder;
        private final ModConfigSpec.EnumValue<SearchMode> searchMode;
        private final ModConfigSpec.EnumValue<TerminalResourceView> resourceView;
        private final ModConfigSpec.EnumValue<CraftingTerminalPage> page;
        private final ModConfigSpec.BooleanValue usePlayerInventory;
        private final ModConfigSpec.EnumValue<TerminalOutputDestination> outputDestination;
        private final ModConfigSpec.ConfigValue<String> fuelTarget;

        private Values(ModConfigSpec.Builder builder) {
            builder.push("terminal");
            sortMode = builder.defineEnum("sortMode", SortMode.NAME);
            sortOrder = builder.defineEnum("sortOrder", SortOrder.ASCENDING);
            searchMode = builder.defineEnum("searchMode", SearchMode.NORMAL);
            resourceView = builder.defineEnum("resourceView", TerminalResourceView.ITEM);
            page = builder.defineEnum("craftingPage", CraftingTerminalPage.STORAGE);
            usePlayerInventory = builder.define("usePlayerInventory", false);
            outputDestination = builder.defineEnum(
                    "outputDestination", TerminalOutputDestination.PLAYER);
            fuelTarget = builder.defineInList(
                    "fuelTarget", AUTO_FUEL_TARGET, fuelTargetIds());
            builder.pop();
        }
    }
}
