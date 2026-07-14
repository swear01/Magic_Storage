package com.swearprom.magicstorage.magic_storage;

import com.swearprom.magicstorage.magic_storage.compat.EmiRecipeDiagramBootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public class ClientSetup {

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::registerScreens);
    }

    static RecipeDiagramRenderer createRecipeDiagramRenderer() {
        if (ModList.get().isLoaded("emi")) {
            return EmiRecipeDiagramBootstrap.create();
        }
        return new NativeRecipeDiagramRenderer();
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.<StorageTerminalMenu, StorageTerminalScreen<StorageTerminalMenu>>register(
                MagicStorage.STORAGE_TERMINAL_MENU.get(),
                (menu, inv, title) -> new StorageTerminalScreen<>(menu, inv, title));
        event.<CraftingTerminalMenu, CraftingTerminalScreen>register(
                MagicStorage.CRAFTING_TERMINAL_MENU.get(),
                (menu, inv, title) -> new CraftingTerminalScreen(menu, inv, title));
    }
}
