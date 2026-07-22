# Recipe Family API

Magic Storage automatically supports recipes from any namespace when their concrete recipe class is one of the built-in exact families. Addons only need this API for a custom `RecipeType` plus custom concrete recipe class.

## Registration

Create a NeoForge deferred register during normal mod construction. One family entry covers every current and future recipe ID whose holder has that exact class and exact `RecipeType`.

```java
private static final DeferredRegister<RecipeFamily> RECIPE_FAMILIES =
        RecipeFamilyApi.createDeferredRegister(MODID);

static {
    RECIPE_FAMILIES.register("grinding", () -> RecipeFamilyFactories.singleItemToItem(
            GrindingRecipe.class,
            GRINDING_RECIPE_TYPE,
            GRINDER_DESCRIPTOR_ID,
            GrindingRecipe::input,
            (recipe, registries) -> recipe.getResultItem(registries),
            recipe -> RecipeFamilyCost.free(),
            RecipePresentationKind.STONECUTTING));
}

public ExampleMod(IEventBus modEventBus) {
    RECIPE_FAMILIES.register(modEventBus);
}
```

`GRINDING_RECIPE_TYPE` may be a `DeferredHolder`, because the factory stores the supplier and resolves it after registry registration. `GRINDER_DESCRIPTOR_ID` must already identify a registered [`MachineDescriptor`](machine-descriptor-api.md). A missing descriptor, duplicate family registry ID, or duplicate exact class/type pair fails startup instead of silently choosing one family.

## Factory contracts

### `singleItemToItem`

The first public factory is intentionally narrow:

- exact recipe class and exact `RecipeType` identity;
- one non-empty `Ingredient`, consumed once per craft;
- one deterministic output stack; item components and count are preserved exactly;
- free cost, an existing Magic Storage `EnergyCost`, descriptor-keyed station work, or station work plus Fuel through `RecipeFamilyCost.energy(...)`, `stationWork(...)`, or `stationWorkAndEnergy(...)`; amounts must be non-negative, required work must be positive, at least one energy amount must be positive, and two positive energy amounts cannot target the same pool (`free()` represents zero cost);
- one existing `RecipePresentationKind`;
- no crafting remainder, chance output, source-dependent output, fluid, player/world/event callback, or arbitrary mutation hook.

The input, output, and cost functions must be deterministic and must not mutate their recipe, arguments, registries, storage, player, or world. Magic Storage owns candidate indexing, current-holder validation, checked count multiplication, station checks, simulate-then-commit, delivery, capacity planning, and rollback. Empty input/output or invalid registration is rejected; there is no generic reflection, serializer-name, `Recipe#getIngredients()`, or EMI-widget fallback.

If a custom family does not fit this complete contract, do not approximate it with this factory. A new reusable factory must first model all inputs, outputs, components, remainders, costs, and side effects server-authoritatively. External-machine send-and-wait processing is outside this mod's installed-station magic-crafting scope.

### `deterministicResources`

Use `RecipeFamilyFactories.deterministicResources(...)` when one exact recipe resolves to a bounded `TypedRecipePlan`. The resolver receives the exact recipe and current server registries. The plan builder requires:

- one to nine exact `TypedRecipeInput` values;
- at least one `TypedRecipeOutput.primary(...)` item so the current item terminal can select the recipe;
- any additional item, fluid, power, chemical, or registered addon outputs;
- a non-empty exact presentation output and a `1..3 × 1..3` layout that fits all inputs.

Input roles are explicit. `consume` removes `amount × crafts`; `catalyst` and `tool` require the declared amount but retain it, so one retained resource may serve the whole batch. Each input may declare ordered exact alternatives with `consumeAny`/`catalystAny`/`toolAny`; consumed alternatives may additionally map each chosen key to its own remainder through `consumeAnyWithRemainders`. Overlapping CONSUME alternatives are jointly allocated by a deterministic bounded max-flow plan, so one resource cannot satisfy two requirements. Retained roles may not overlap another role because no deterministic split is defined. Output roles are `primary` and `remainder`. Primary items follow Player/Storage delivery, item remainders follow the existing remainder policy, and non-item outputs return to Core. Duplicate output keys are rejected. Checked multiplication, retained-resource checks, station work/Fuel, item delivery, type capacity, and all Core item/non-item deltas are validated before one atomic ledger commit.

Use `StorageResourceKey.item(stack, registries)`, `fluid(stack, registries)`, and `neoforgeEnergy()` for canonical built-in keys. `itemStack(...)` and `fluidStack(...)` decode exact built-in keys without dropping components. Custom kinds use their registered kind ID, stable resource ID, and canonical variant compound.

```java
RECIPE_FAMILIES.register("infusion", () -> RecipeFamilyFactories.deterministicResources(
        InfusionRecipe.class,
        INFUSION_RECIPE_TYPE,
        INFUSER_DESCRIPTOR_ID,
        (recipe, registries) -> TypedRecipePlan.builder()
                .input(TypedRecipeInput.consume(
                        StorageResourceKey.item(recipe.input(), registries), 2))
                .input(TypedRecipeInput.consume(MANA_KEY, 100))
                .input(TypedRecipeInput.catalyst(CATALYST_KEY, 1))
                .output(TypedRecipeOutput.primary(
                        StorageResourceKey.item(recipe.output(), registries), 3))
                .output(TypedRecipeOutput.remainder(MANA_KEY, 25))
                .presentationOutput(recipe.output().copyWithCount(3))
                .layout(3, 1, false)
                .build(),
        recipe -> RecipeFamilyCost.free(),
        RecipePresentationKind.STONECUTTING));
```

The resolver must be deterministic and side-effect free. Chance outputs, runtime machine polling, arbitrary Core/player/world callbacks, and external-machine send-and-wait are rejected by design. Multi-step planning remains a separate future server graph; EMI is presentation and selection only.

The overload without an eligibility predicate retains the global fail-closed rule for `Recipe#isSpecial()`. Some third-party public exact recipe classes report `isSpecial()` despite exposing a complete deterministic contract. Only the overload with an explicit `Predicate<R> eligibility` may opt that exact class/type into special-recipe support; the predicate must reject unsupported output shapes, overflow, chance/per-tick semantics, or any other non-deterministic member. This does not relax special handling for any other family.

## Current optional integrations

- **Iron Furnaces:** contributes concrete Furnace station variants; recipes remain the existing exact smelting family while rate comes from live configured cook time.
- **Farmer's Delight:** Cooking Pot plans include 1–6 ingredients, exact serving container, alternative-specific container remainders, `cookTime` station work, matching Furnace Fuel, and one fixed item output.
- **Mekanism:** Crushing, Enriching, and Smelting use sized one-item inputs; Combining uses two sized item inputs; item-output Pressurized Reaction uses sized item/fluid/chemical inputs, checked `energyRequired × duration` FE, duration station work, and optional chemical co-output. Chemical-only output, chance, or unsupported per-tick-use families remain fail closed.

All three compatibility classes load only after `ModList` confirms the target mod. One representative artifact per mod is used in CI without restricting player metadata to that exact version.

## Lifecycle and sides

- Register on the mod event bus before NeoForge registries freeze. Magic Storage freezes and validates its immutable adapter snapshot at common setup; runtime hot registration is unsupported.
- The recipe-family registry is server policy. Dedicated servers never load EMI or screen classes through this API.
- External families are ordered after built-ins and then by full registry ID. Addons cannot supply a priority or shadow a built-in exact class/type pair.
- A CI-tested addon version is representative evidence only. Addon metadata should use the compatibility range it actually supports rather than copying a single CI artifact version as an exact player-facing pin.

## Repository verification fixture

`src/apiTest/` proves both factories, the explicit-eligibility overload, and the resource kind/handler/block/container APIs compile from a different Java package. `src/recipeAddonFixture/` is a separately loaded NeoForge mod with 17 GameTests covering the legacy single-item family, polymorphic/fractional station work and installed-first preview cycling, typed overlapping alternatives and alternative-specific remainders, retained roles, exact counts, mixed item/addon/fluid commit, terminal listing and held-container transfer, generic+native Bus discovery, persistent typed rollback escrow, Creative removal conservation, destination capacity rejection, and rollback. Public handler/strategy simulation and amount rules are authoritative in [`typed-resource-storage.md`](typed-resource-storage.md#public-handler-and-strategy-contract).

```bash
./gradlew compileApiTestJava
./gradlew runRecipeAddonGameTestServer
./gradlew runMekanismGameTestServer
./gradlew runIronFurnacesGameTestServer
./gradlew runFarmersDelightGameTestServer
```
