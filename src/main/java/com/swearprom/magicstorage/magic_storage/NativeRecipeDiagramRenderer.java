package com.swearprom.magicstorage.magic_storage;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class NativeRecipeDiagramRenderer implements RecipeDiagramRenderer {
    @Override
    public boolean supports(RecipePresentation presentation, Geometry geometry) {
        if (presentation.isEmpty()) return false;
        return true;
    }

    @Override
    public void render(
            GuiGraphics graphics,
            Font font,
            RecipePresentation presentation,
            Geometry geometry,
            int left,
            int top,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        renderInputs(graphics, presentation, geometry, left, top);
        drawArrow(graphics, geometry.arrow(), left, top, 0xFF5A5A5A);
        renderStation(graphics, presentation.station(), geometry.station(), left, top);
        if (presentation.shapeless()) {
            renderShapelessMarker(graphics, geometry.shapelessMarker(), left, top);
        }

        Rect outputBounds = geometry.output();
        drawLargeSlotFrame(graphics, outputBounds, left, top);
        ItemStack output = presentation.output();
        int outputX = left + outputBounds.x() + (outputBounds.width() - 16) / 2;
        int outputY = top + outputBounds.y() + (outputBounds.height() - 16) / 2;
        graphics.renderItem(output, outputX, outputY);
        graphics.renderItemDecorations(font, output, outputX, outputY);
    }

    @Override
    public boolean renderTooltip(
            GuiGraphics graphics,
            Font font,
            RecipePresentation presentation,
            Geometry geometry,
            int left,
            int top,
            int mouseX,
            int mouseY
    ) {
        double localX = mouseX - left;
        double localY = mouseY - top;
        if (geometry.output().contains(localX, localY)) {
            graphics.renderTooltip(font, presentation.output(), mouseX, mouseY);
            return true;
        }
        ItemStack input = inputAt(presentation, geometry, localX, localY);
        if (!input.isEmpty()) {
            graphics.renderTooltip(font, input, mouseX, mouseY);
            return true;
        }
        if (geometry.station().contains(localX, localY)) {
            graphics.renderTooltip(font, presentation.station(), mouseX, mouseY);
            return true;
        }
        return false;
    }

    private static void renderInputs(
            GuiGraphics graphics,
            RecipePresentation presentation,
            Geometry geometry,
            int left,
            int top
    ) {
        List<ItemStack> inputs = presentation.inputs();
        if (presentation.kind() == RecipePresentationKind.CRAFTING) {
            for (Rect slot : geometry.inputSlots()) {
                drawInputSlot(graphics, slot, ItemStack.EMPTY, left, top);
            }
        }
        int positions = presentation.width() * presentation.height();
        for (int input = 0; input < positions; input++) {
            drawInputSlot(
                    graphics,
                    inputSlot(presentation, geometry, input),
                    inputs.get(input),
                    left,
                    top);
        }
    }

    private static Rect inputSlot(
            RecipePresentation presentation,
            Geometry geometry,
            int input
    ) {
        int column = input % presentation.width();
        int row = input / presentation.width();
        int columnOffset = (3 - presentation.width()) / 2;
        int rowOffset = (3 - presentation.height()) / 2;
        return geometry.inputSlots().get((row + rowOffset) * 3 + column + columnOffset);
    }

    private static ItemStack inputAt(
            RecipePresentation presentation,
            Geometry geometry,
            double mouseX,
            double mouseY
    ) {
        List<ItemStack> inputs = presentation.inputs();
        int positions = presentation.width() * presentation.height();
        for (int input = 0; input < positions; input++) {
            if (inputSlot(presentation, geometry, input).contains(mouseX, mouseY)) {
                return inputs.get(input);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void drawInputSlot(
            GuiGraphics graphics,
            Rect slot,
            ItemStack stack,
            int left,
            int top
    ) {
        int itemX = left + slot.x() + 1;
        int itemY = top + slot.y() + 1;
        drawSlotFrame(graphics, itemX, itemY);
        if (!stack.isEmpty()) graphics.renderItem(stack, itemX, itemY);
    }

    private static void renderStation(
            GuiGraphics graphics,
            ItemStack station,
            Rect bounds,
            int left,
            int top
    ) {
        int itemX = left + bounds.x() + 1;
        int itemY = top + bounds.y() + 1;
        drawSlotFrame(graphics, itemX, itemY);
        graphics.renderItem(station, itemX, itemY);
    }

    private static void drawSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
        graphics.fill(x, y, x + 17, y + 17, 0xFF8B8B8B);
        graphics.fill(x + 16, y, x + 17, y + 17, 0xFFFFFFFF);
        graphics.fill(x, y + 16, x + 17, y + 17, 0xFFFFFFFF);
    }

    private static void drawLargeSlotFrame(
            GuiGraphics graphics,
            Rect bounds,
            int left,
            int top
    ) {
        int x = left + bounds.x();
        int y = top + bounds.y();
        graphics.fill(x, y, x + bounds.width(), y + bounds.height(), 0xFF373737);
        graphics.fill(x + 1, y + 1, x + bounds.width() - 1, y + bounds.height() - 1, 0xFF8B8B8B);
        graphics.fill(x + bounds.width() - 2, y + 1,
                x + bounds.width() - 1, y + bounds.height() - 1, 0xFFFFFFFF);
        graphics.fill(x + 1, y + bounds.height() - 2,
                x + bounds.width() - 1, y + bounds.height() - 1, 0xFFFFFFFF);
    }

    private static void renderShapelessMarker(
            GuiGraphics graphics,
            Rect bounds,
            int left,
            int top
    ) {
        int x = left + bounds.x();
        int y = top + bounds.y();
        int color = 0xFF5A5A5A;
        graphics.fill(x, y + 2, x + 7, y + 3, color);
        graphics.fill(x + 6, y + 1, x + 8, y + 4, color);
        graphics.fill(x + 2, y + 7, x + 9, y + 8, color);
        graphics.fill(x + 1, y + 6, x + 3, y + 9, color);
    }

    private static void drawArrow(
            GuiGraphics graphics,
            Rect bounds,
            int left,
            int top,
            int color
    ) {
        int iconX = left + bounds.x() + (bounds.width() - TerminalLayout.ICON_CANVAS_SIZE) / 2;
        int iconY = top + bounds.y() + (bounds.height() - TerminalLayout.ICON_CANVAS_SIZE) / 2;
        StorageTerminalScreen.blitControlIcon(
                graphics, iconX, iconY, StorageTerminalScreen.TerminalControlIcon.NEXT, color);
    }
}
