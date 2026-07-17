package com.swearprom.magicstorage.magic_storage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

record RecipeAdapterMatch(
        RecipeAdapter adapter,
        RecipeHolder<?> holder,
        RecipeCandidateIndex candidateIndex,
        Contract contract
) {
    RecipeAdapterMatch(RecipeAdapter adapter, RecipeHolder<?> holder, RecipeCandidateIndex candidateIndex) {
        this(adapter, holder, candidateIndex, adapter.contract(holder));
    }

    RecipeAdapterMatch {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(candidateIndex, "candidateIndex");
        Objects.requireNonNull(contract, "contract");
    }

    ResourceLocation adapterId() {
        return adapter.id();
    }

    List<Input> orderedInputs() {
        return contract.orderedInputs();
    }

    ResourceLocation stationDescriptorId() {
        return contract.stationDescriptorId();
    }

    Cost cost() {
        return contract.cost();
    }

    Presentation presentation() {
        return contract.presentation();
    }

    boolean isCurrentHolder(RecipeHolder<?> currentHolder) {
        return holder == currentHolder;
    }

    boolean validatesSimulation(RecipeHolder<?> currentHolder) {
        return isCurrentHolder(currentHolder) && contract.simulationValidator().validate(currentHolder);
    }

    boolean validatesCommit(RecipeHolder<?> currentHolder) {
        return isCurrentHolder(currentHolder) && contract.commitValidator().validate(currentHolder);
    }

    Optional<CheckedOutput> checkedOutput(
            List<Map<ItemKey, Long>> allocations,
            long crafts,
            Level level
    ) {
        Objects.requireNonNull(allocations, "allocations");
        if (crafts <= 0 || !validatesSimulation(holder)) return Optional.empty();
        List<Input> requiredInputs = orderedInputs().stream()
                .filter(input -> !input.isEmpty())
                .toList();
        if (allocations.size() != requiredInputs.size()) return Optional.empty();
        for (int index = 0; index < requiredInputs.size(); index++) {
            Input input = requiredInputs.get(index);
            Map<ItemKey, Long> allocation = allocations.get(index);
            long required;
            try {
                required = Math.multiplyExact(crafts, input.multiplicity());
            } catch (ArithmeticException exception) {
                return Optional.empty();
            }
            long allocated = 0;
            try {
                for (Map.Entry<ItemKey, Long> entry : allocation.entrySet()) {
                    if (entry.getValue() <= 0 || !input.test(entry.getKey().toStack(1))) {
                        return Optional.empty();
                    }
                    allocated = Math.addExact(allocated, entry.getValue());
                }
            } catch (ArithmeticException exception) {
                return Optional.empty();
            }
            if (allocated != required) return Optional.empty();
        }
        return contract.outputResolver().resolve(allocations, crafts, level);
    }

    ItemStack presentationOutput(List<ItemStack> orderedInputs, Level level) {
        Objects.requireNonNull(orderedInputs, "orderedInputs");
        return presentation().outputResolver().resolve(orderedInputs, level);
    }

    List<RecipeAdapterMatch> resolveVariants(List<ItemStack> availableStacks, Level level) {
        Objects.requireNonNull(availableStacks, "availableStacks");
        List<ItemStack> snapshot = availableStacks.stream()
                .filter(stack -> !stack.isEmpty())
                .map(stack -> stack.copyWithCount(1))
                .toList();
        return adapter.resolveVariants(holder, snapshot, level).stream()
                .map(variantContract -> {
                    Objects.requireNonNull(variantContract, "variantContract");
                    return new RecipeAdapterMatch(adapter, holder, candidateIndex, variantContract);
                })
                .toList();
    }

    boolean matchesLookupOutput(ItemStack requestedOutput, Level level) {
        Objects.requireNonNull(requestedOutput, "requestedOutput");
        return adapter.matchesLookupOutput(holder, contract, requestedOutput, level);
    }

    record Contract(
            List<Input> orderedInputs,
            ResourceLocation stationDescriptorId,
            Cost cost,
            OutputResolver outputResolver,
            Presentation presentation,
            HolderValidator simulationValidator,
            HolderValidator commitValidator
    ) {
        Contract {
            Objects.requireNonNull(orderedInputs, "orderedInputs");
            orderedInputs = List.copyOf(orderedInputs);
            if (orderedInputs.isEmpty()
                    || orderedInputs.stream().noneMatch(input -> !input.isEmpty())) {
                throw new IllegalArgumentException("Recipe adapter contract requires an input");
            }
            if (orderedInputs.size() > RecipePresentation.MAX_INPUTS) {
                throw new IllegalArgumentException("Recipe adapter contract has more than nine inputs");
            }
            Objects.requireNonNull(stationDescriptorId, "stationDescriptorId");
            Objects.requireNonNull(cost, "cost");
            Objects.requireNonNull(outputResolver, "outputResolver");
            Objects.requireNonNull(presentation, "presentation");
            Objects.requireNonNull(simulationValidator, "simulationValidator");
            Objects.requireNonNull(commitValidator, "commitValidator");
        }
    }

    static final class Input {
        private final Object identity;
        private final Predicate<ItemStack> matcher;
        private final List<ItemStack> representatives;
        private final int multiplicity;
        private final boolean empty;

        private Input(
                Object identity,
                Predicate<ItemStack> matcher,
                List<ItemStack> representatives,
                int multiplicity,
                boolean empty
        ) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.matcher = Objects.requireNonNull(matcher, "matcher");
            Objects.requireNonNull(representatives, "representatives");
            this.representatives = representatives.stream()
                    .filter(stack -> !stack.isEmpty())
                    .map(stack -> stack.copyWithCount(1))
                    .toList();
            if (empty != (multiplicity == 0)) {
                throw new IllegalArgumentException("Empty inputs require zero multiplicity");
            }
            if (!empty && multiplicity <= 0) {
                throw new IllegalArgumentException("Input multiplicity must be positive");
            }
            this.multiplicity = multiplicity;
            this.empty = empty;
        }

        static Input of(
                Object identity,
                Predicate<ItemStack> matcher,
                List<ItemStack> representatives,
                int multiplicity
        ) {
            return new Input(identity, matcher, representatives, multiplicity, false);
        }

        static Input empty(Object identity) {
            return new Input(identity, stack -> false, List.of(), 0, true);
        }

        boolean test(ItemStack stack) {
            return !empty && matcher.test(stack);
        }

        List<ItemStack> representatives() {
            return representatives.stream().map(ItemStack::copy).toList();
        }

        int multiplicity() {
            return multiplicity;
        }

        boolean isEmpty() {
            return empty;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Input input && identity.equals(input.identity);
        }

        @Override
        public int hashCode() {
            return identity.hashCode();
        }
    }

    record Cost(Optional<EnergyCost> energyCost, Optional<ToolCost> toolCost) {
        Cost {
            Objects.requireNonNull(energyCost, "energyCost");
            Objects.requireNonNull(toolCost, "toolCost");
        }

        static Cost free() {
            return new Cost(Optional.empty(), Optional.empty());
        }

        static Cost energy(EnergyCost energyCost) {
            return new Cost(Optional.of(Objects.requireNonNull(energyCost, "energyCost")), Optional.empty());
        }

        static Cost tool(ToolCost toolCost) {
            return new Cost(Optional.empty(), Optional.of(Objects.requireNonNull(toolCost, "toolCost")));
        }
    }

    record ToolCost(ResourceLocation descriptorId, long amountPerCraft) {
        ToolCost {
            Objects.requireNonNull(descriptorId, "descriptorId");
            if (amountPerCraft <= 0) throw new IllegalArgumentException("Tool cost must be positive");
        }
    }

    record Presentation(
            RecipePresentationKind kind,
            int width,
            int height,
            boolean shapeless,
            PresentationOutputResolver outputResolver
    ) {
        Presentation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(outputResolver, "outputResolver");
            if (kind == RecipePresentationKind.NONE
                    || width < 1 || width > 3 || height < 1 || height > 3) {
                throw new IllegalArgumentException("Invalid adapter presentation semantics");
            }
        }
    }

    record CheckedOutput(Map<ItemKey, Long> primaryOutputs, Map<ItemKey, Long> remainders) {
        CheckedOutput {
            primaryOutputs = checkedAmounts(primaryOutputs, "primaryOutputs");
            remainders = checkedAmounts(remainders, "remainders");
            if (primaryOutputs.isEmpty()) {
                throw new IllegalArgumentException("Checked output requires a primary output");
            }
        }

        private static Map<ItemKey, Long> checkedAmounts(Map<ItemKey, Long> amounts, String name) {
            Objects.requireNonNull(amounts, name);
            for (Map.Entry<ItemKey, Long> entry : amounts.entrySet()) {
                Objects.requireNonNull(entry.getKey(), name + ".key");
                if (entry.getValue() == null || entry.getValue() <= 0) {
                    throw new IllegalArgumentException(name + " amounts must be positive");
                }
            }
            return Map.copyOf(amounts);
        }
    }

    @FunctionalInterface
    interface OutputResolver {
        Optional<CheckedOutput> resolve(
                List<Map<ItemKey, Long>> allocations,
                long crafts,
                Level level
        );
    }

    @FunctionalInterface
    interface PresentationOutputResolver {
        ItemStack resolve(List<ItemStack> orderedInputs, Level level);
    }

    @FunctionalInterface
    interface HolderValidator {
        boolean validate(RecipeHolder<?> holder);
    }
}
