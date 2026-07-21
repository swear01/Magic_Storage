package com.swearprom.magicstorage.magic_storage;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TypedRecipePlan {
    private final List<TypedRecipeInput> inputs;
    private final List<TypedRecipeOutput> outputs;
    private final ItemStack presentationOutput;
    private final int width;
    private final int height;
    private final boolean shapeless;

    private TypedRecipePlan(Builder builder) {
        inputs = List.copyOf(builder.inputs);
        outputs = List.copyOf(builder.outputs);
        presentationOutput = builder.presentationOutput.copy();
        width = builder.width;
        height = builder.height;
        shapeless = builder.shapeless;
        if (inputs.isEmpty() || inputs.size() > RecipePresentation.MAX_INPUTS) {
            throw new IllegalArgumentException("Typed recipe plan requires one to nine inputs");
        }
        if (outputs.isEmpty()
                || outputs.stream().noneMatch(output -> output.role() == TypedRecipeOutput.Role.PRIMARY)) {
            throw new IllegalArgumentException("Typed recipe plan requires a primary output");
        }
        if (outputs.stream().noneMatch(output ->
                output.role() == TypedRecipeOutput.Role.PRIMARY
                        && output.key().kindId().equals(StorageResourceKindApi.ITEM_KIND))) {
            throw new IllegalArgumentException(
                    "Typed recipe plan requires an item primary output for terminal selection");
        }
        if (presentationOutput.isEmpty()) {
            throw new IllegalArgumentException("Typed recipe plan requires a presentation output");
        }
        if (width < 1 || width > 3 || height < 1 || height > 3 || width * height < inputs.size()) {
            throw new IllegalArgumentException("Typed recipe layout must fit one to nine inputs");
        }
        requireUniqueKeys(inputs.stream().map(TypedRecipeInput::key).toList(), "input");
        requireUniqueKeys(outputs.stream().map(TypedRecipeOutput::key).toList(), "output");
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<TypedRecipeInput> inputs() {
        return inputs;
    }

    public List<TypedRecipeOutput> outputs() {
        return outputs;
    }

    public ItemStack presentationOutput() {
        return presentationOutput.copy();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean shapeless() {
        return shapeless;
    }

    private static void requireUniqueKeys(List<StorageResourceKey> keys, String name) {
        Set<StorageResourceKey> unique = new HashSet<>();
        for (StorageResourceKey key : keys) {
            if (!unique.add(key)) {
                throw new IllegalArgumentException("Typed recipe " + name + " keys must be unique");
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TypedRecipePlan plan
                && inputs.equals(plan.inputs)
                && outputs.equals(plan.outputs)
                && ItemStack.isSameItemSameComponents(presentationOutput, plan.presentationOutput)
                && presentationOutput.getCount() == plan.presentationOutput.getCount()
                && width == plan.width
                && height == plan.height
                && shapeless == plan.shapeless;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                inputs,
                outputs,
                ItemStack.hashItemAndComponents(presentationOutput),
                presentationOutput.getCount(),
                width,
                height,
                shapeless);
    }

    public static final class Builder {
        private final List<TypedRecipeInput> inputs = new ArrayList<>();
        private final List<TypedRecipeOutput> outputs = new ArrayList<>();
        private ItemStack presentationOutput = ItemStack.EMPTY;
        private int width;
        private int height;
        private boolean shapeless;

        private Builder() {
        }

        public Builder input(TypedRecipeInput input) {
            inputs.add(Objects.requireNonNull(input, "input"));
            return this;
        }

        public Builder output(TypedRecipeOutput output) {
            outputs.add(Objects.requireNonNull(output, "output"));
            return this;
        }

        public Builder presentationOutput(ItemStack output) {
            presentationOutput = Objects.requireNonNull(output, "output").copy();
            return this;
        }

        public Builder layout(int width, int height, boolean shapeless) {
            this.width = width;
            this.height = height;
            this.shapeless = shapeless;
            return this;
        }

        public TypedRecipePlan build() {
            return new TypedRecipePlan(this);
        }
    }
}
