package com.swearprom.magicstorage.magic_storage;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;

public record TerminalPreferences(
        SortMode sortMode,
        SortOrder sortOrder,
        SearchMode searchMode,
        TerminalResourceView resourceView,
        CraftingTerminalPage page,
        boolean usePlayerInventory,
        TerminalOutputDestination outputDestination,
        EnergyType fuelTarget
) {
    public TerminalPreferences {
        Objects.requireNonNull(sortMode);
        Objects.requireNonNull(sortOrder);
        Objects.requireNonNull(searchMode);
        Objects.requireNonNull(resourceView);
        Objects.requireNonNull(page);
        Objects.requireNonNull(outputDestination);
        if (fuelTarget != null && fuelTarget.isMachineGenerated()) {
            throw new IllegalArgumentException("Not a selectable fuel target: " + fuelTarget);
        }
    }

    public static TerminalPreferences defaults() {
        return new TerminalPreferences(
                SortMode.NAME,
                SortOrder.ASCENDING,
                SearchMode.OFF,
                TerminalResourceView.ITEM,
                CraftingTerminalPage.STORAGE,
                false,
                TerminalOutputDestination.PLAYER,
                null);
    }

    public boolean matchesCommon(TerminalPreferences other) {
        return sortMode == other.sortMode
                && sortOrder == other.sortOrder
                && searchMode == other.searchMode
                && resourceView == other.resourceView;
    }

    public TerminalPreferences mergeCommon(TerminalPreferences common) {
        return new TerminalPreferences(
                common.sortMode,
                common.sortOrder,
                common.searchMode,
                common.resourceView,
                page,
                usePlayerInventory,
                outputDestination,
                fuelTarget);
    }

    void write(FriendlyByteBuf buf) {
        buf.writeVarInt(sortMode.ordinal());
        buf.writeVarInt(sortOrder.ordinal());
        buf.writeVarInt(searchMode.ordinal());
        buf.writeVarInt(resourceView.ordinal());
        buf.writeVarInt(page.ordinal());
        buf.writeBoolean(usePlayerInventory);
        buf.writeVarInt(outputDestination.ordinal());
        buf.writeVarInt(fuelTarget == null ? 0 : fuelTarget.ordinal() + 1);
    }

    static TerminalPreferences read(FriendlyByteBuf buf) {
        SortMode sortMode = readEnum(buf, SortMode.values(), "sort mode");
        SortOrder sortOrder = readEnum(buf, SortOrder.values(), "sort order");
        SearchMode searchMode = readEnum(buf, SearchMode.values(), "search mode");
        TerminalResourceView resourceView = readEnum(
                buf, TerminalResourceView.values(), "resource view");
        CraftingTerminalPage page = readEnum(buf, CraftingTerminalPage.values(), "page");
        boolean usePlayerInventory = buf.readBoolean();
        TerminalOutputDestination outputDestination = readEnum(
                buf, TerminalOutputDestination.values(), "output destination");
        int fuelTargetId = buf.readVarInt();
        if (fuelTargetId < 0 || fuelTargetId > EnergyType.values().length) {
            throw new IllegalArgumentException("Unknown fuel target " + fuelTargetId);
        }
        EnergyType fuelTarget = fuelTargetId == 0 ? null : EnergyType.values()[fuelTargetId - 1];
        return new TerminalPreferences(
                sortMode,
                sortOrder,
                searchMode,
                resourceView,
                page,
                usePlayerInventory,
                outputDestination,
                fuelTarget);
    }

    private static <T> T readEnum(FriendlyByteBuf buf, T[] values, String name) {
        int id = buf.readVarInt();
        if (id < 0 || id >= values.length) {
            throw new IllegalArgumentException("Unknown terminal " + name + " " + id);
        }
        return values[id];
    }
}
