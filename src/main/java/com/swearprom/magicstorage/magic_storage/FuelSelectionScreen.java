package com.swearprom.magicstorage.magic_storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

public class FuelSelectionScreen extends Screen {

    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;

    private final ItemStack fuelStack;
    private final Set<EnergyType> compatiblePools;

    public FuelSelectionScreen(Component title, ItemStack fuelStack, Set<EnergyType> compatiblePools) {
        super(title);
        this.fuelStack = fuelStack;
        this.compatiblePools = compatiblePools;
    }

    @Override
    protected void init() {
        super.init();
        int x = (this.width - WIDTH) / 2;
        int y = (this.height - HEIGHT) / 2;

        EnergyType[] pools = compatiblePools.toArray(new EnergyType[0]);
        for (int i = 0; i < pools.length; i++) {
            EnergyType pool = pools[i];
            this.addRenderableWidget(Button.builder(
                    Component.literal(pool.getId()),
                    btn -> onPoolSelected(pool)
            ).bounds(x + 10, y + 30 + i * 24, WIDTH - 20, 20).build());
        }
    }

    private void onPoolSelected(EnergyType pool) {
        onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - WIDTH) / 2;
        int y = (this.height - HEIGHT) / 2;

        guiGraphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xCC_C6C6C6);
        guiGraphics.fill(x + 2, y + 2, x + WIDTH - 2, y + HEIGHT - 2, 0xFF_8B8B8B);
        guiGraphics.fill(x + 3, y + 3, x + WIDTH - 3, y + HEIGHT - 3, 0xFF_C6C6C6);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, y + 10, 0x404040);

        guiGraphics.renderFakeItem(fuelStack, x + 10, y + 8);
        guiGraphics.drawString(this.font, fuelStack.getHoverName(), x + 28, y + 12, 0x404040);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
