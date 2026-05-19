package com.swearprom.magicstorage.magic_storage;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = MagicStorage.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.<StorageTerminalMenu, StorageTerminalScreen<StorageTerminalMenu>>register(
                MagicStorage.STORAGE_TERMINAL_MENU.get(),
                (menu, inv, title) -> new StorageTerminalScreen<>(menu, inv, title));
        event.<CraftingTerminalMenu, CraftingTerminalScreen>register(
                MagicStorage.CRAFTING_TERMINAL_MENU.get(),
                (menu, inv, title) -> new CraftingTerminalScreen(menu, inv, title));
    }
}
