package com.swearprom.magicstorage.magic_storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AxeTransformationCatalog {
    private static final List<ItemAbility> ABILITIES = List.of(
            ItemAbilities.AXE_STRIP,
            ItemAbilities.AXE_SCRAPE,
            ItemAbilities.AXE_WAX_OFF
    );

    private ItemKey cachedTool;
    private List<RecipeHolder<?>> cachedRecipes = List.of();
    private Map<ResourceLocation, RecipeHolder<?>> cachedById = Map.of();

    List<RecipeHolder<?>> recipes(Level level, StorageCoreBlockEntity core) {
        ItemStack tool = installedTool(core);
        if (remainingDurability(tool) <= 0) return List.of();
        ItemKey key = toolCacheKey(tool);
        if (!key.equals(cachedTool)) rebuild(level, core.getBlockPos(), tool, key);
        return cachedRecipes;
    }

    RecipeHolder<?> byId(Level level, StorageCoreBlockEntity core, ResourceLocation id) {
        recipes(level, core);
        return cachedById.get(id);
    }

    static ItemStack installedTool(StorageCoreBlockEntity core) {
        if (core == null) return ItemStack.EMPTY;
        return core.getMachineContainer().getItem(MachineEnergyTable.AXE_SLOT);
    }

    static int remainingDurability(ItemStack tool) {
        if (tool.isEmpty() || tool.getMaxDamage() <= 0) return 0;
        return Math.max(0, tool.getMaxDamage() - tool.getDamageValue());
    }

    static boolean isSyntheticDiscoveryAllowed(ResourceLocation blockId) {
        return blockId != null && "minecraft".equals(blockId.getNamespace());
    }

    static ItemKey toolCacheKey(ItemStack tool) {
        ItemStack normalized = tool.copy();
        normalized.remove(DataComponents.DAMAGE);
        return ItemKey.of(normalized);
    }

    private void rebuild(Level level, BlockPos pos, ItemStack tool, ItemKey key) {
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        Map<ResourceLocation, RecipeHolder<?>> byId = new HashMap<>();
        ToolContext context = new ToolContext(level, tool, pos);
        for (var block : BuiltInRegistries.BLOCK) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            if (!isSyntheticDiscoveryAllowed(blockId) || block.asItem() == Items.AIR) continue;
            BlockState inputState = block.defaultBlockState();
            for (ItemAbility ability : ABILITIES) {
                if (!tool.canPerformAction(ability)) continue;
                BlockState outputState = block.getToolModifiedState(inputState, context, ability, true);
                if (outputState == null || outputState.getBlock().asItem() == Items.AIR
                        || outputState.getBlock().asItem() == block.asItem()) continue;
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                        MagicStorage.MODID,
                        "axe/" + safePath(ability.name()) + "/" + blockId.getNamespace() + "/" + blockId.getPath()
                );
                var recipe = new AxeTransformationRecipe(
                        Ingredient.of(block.asItem()),
                        outputState.getBlock().asItem().getDefaultInstance(),
                        ability
                );
                RecipeHolder<?> holder = new RecipeHolder<>(id, recipe);
                recipes.add(holder);
                byId.put(id, holder);
            }
        }
        recipes.sort(Comparator.comparing(holder -> holder.id().toString()));
        cachedTool = key;
        cachedRecipes = List.copyOf(recipes);
        cachedById = Map.copyOf(byId);
    }

    private static String safePath(String value) {
        return value.toLowerCase(java.util.Locale.ROOT)
                .replace(':', '/')
                .replaceAll("[^a-z0-9/._-]", "_");
    }

    private static final class ToolContext extends UseOnContext {
        private ToolContext(Level level, ItemStack tool, BlockPos pos) {
            super(
                    level,
                    null,
                    InteractionHand.MAIN_HAND,
                    tool,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)
            );
        }
    }
}
