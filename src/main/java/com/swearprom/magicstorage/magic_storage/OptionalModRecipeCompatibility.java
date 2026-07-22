package com.swearprom.magicstorage.magic_storage;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class OptionalModRecipeCompatibility {
    private static final String IRON_FURNACES_MOD_ID = "ironfurnaces";
    private static final String IRON_FURNACES_CLASS =
            "com.swearprom.magicstorage.magic_storage.compat.ironfurnaces.IronFurnacesCompat";
    private static final String FARMERS_DELIGHT_MOD_ID = "farmersdelight";
    private static final String FARMERS_DELIGHT_CLASS =
            "com.swearprom.magicstorage.magic_storage.compat.farmersdelight.FarmersDelightCookingPotCompat";
    private static final String MEKANISM_MOD_ID = "mekanism";
    private static final String MEKANISM_CLASS =
            "com.swearprom.magicstorage.magic_storage.MekanismRecipeCompat";

    private OptionalModRecipeCompatibility() {
    }

    static void register() {
        if (ModList.get().isLoaded(IRON_FURNACES_MOD_ID)) {
            MachineVariantContributors.register(
                    MachineEnergyTable.FURNACE_ID,
                    IRON_FURNACES_MOD_ID,
                    () -> invokeVariants(
                            IRON_FURNACES_MOD_ID,
                            "Iron Furnaces station compatibility",
                            IRON_FURNACES_CLASS,
                            "furnaceVariants"));
        }
        if (ModList.get().isLoaded(FARMERS_DELIGHT_MOD_ID)) {
            invokeRegistrar(
                    FARMERS_DELIGHT_MOD_ID,
                    "Farmer's Delight Cooking Pot compatibility",
                    FARMERS_DELIGHT_CLASS,
                    "register");
        }
        if (ModList.get().isLoaded(MEKANISM_MOD_ID)) {
            invokeRegistrar(
                    MEKANISM_MOD_ID,
                    "Mekanism recipe compatibility",
                    MEKANISM_CLASS,
                    "register");
        }
    }

    private static void invokeRegistrar(
            String modId,
            String module,
            String className,
            String methodName
    ) {
        try {
            Class.forName(className)
                    .getDeclaredMethod(methodName, DeferredRegister.class, DeferredRegister.class)
                    .invoke(null, MagicStorage.MACHINE_DESCRIPTORS, MagicStorage.RECIPE_FAMILIES);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Failed to load " + module + " for loaded mod " + modId, exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof LinkageError error) {
                throw new IllegalStateException(
                        module + " is binary-incompatible with loaded mod " + modId, error);
            }
            throw new IllegalStateException(
                    module + " failed for loaded mod " + modId, cause);
        } catch (LinkageError error) {
            throw new IllegalStateException(
                    module + " is binary-incompatible with loaded mod " + modId, error);
        }
    }

    private static List<MachineVariant> invokeVariants(
            String modId,
            String module,
            String className,
            String methodName
    ) {
        try {
            Method method = Class.forName(className).getDeclaredMethod(methodName);
            Object result = method.invoke(null);
            if (!(result instanceof List<?> raw)) {
                throw new IllegalStateException(module + " returned a non-list variant result");
            }
            List<MachineVariant> variants = new ArrayList<>(raw.size());
            for (Object entry : raw) {
                if (!(entry instanceof MachineVariant variant)) {
                    throw new IllegalStateException(
                            module + " returned a non-variant entry for " + modId);
                }
                variants.add(variant);
            }
            return List.copyOf(variants);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Failed to load " + module + " for loaded mod " + modId, exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof LinkageError error) {
                throw new IllegalStateException(
                        module + " is binary-incompatible with loaded mod " + modId, error);
            }
            throw new IllegalStateException(
                    module + " failed for loaded mod " + modId, cause);
        } catch (LinkageError error) {
            throw new IllegalStateException(
                    module + " is binary-incompatible with loaded mod " + modId, error);
        }
    }
}
