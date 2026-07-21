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

## `singleItemToItem` contract

The first public factory is intentionally narrow:

- exact recipe class and exact `RecipeType` identity;
- one non-empty `Ingredient`, consumed once per craft;
- one deterministic output stack; item components and count are preserved exactly;
- free cost or an existing Magic Storage `EnergyCost` through `RecipeFamilyCost.energy(...)`; amounts must be non-negative, at least one amount must be positive, and the two positive amounts cannot target the same pool (`free()` represents zero cost);
- one existing `RecipePresentationKind`;
- no crafting remainder, chance output, source-dependent output, fluid, player/world/event callback, or arbitrary mutation hook.

The input, output, and cost functions must be deterministic and must not mutate their recipe, arguments, registries, storage, player, or world. Magic Storage owns candidate indexing, current-holder validation, checked count multiplication, station checks, simulate-then-commit, delivery, capacity planning, and rollback. Empty input/output or invalid registration is rejected; there is no generic reflection, serializer-name, `Recipe#getIngredients()`, or EMI-widget fallback.

If a custom family does not fit this complete contract, do not approximate it with this factory. A new reusable factory must first model all inputs, outputs, components, remainders, costs, and side effects server-authoritatively. External-machine send-and-wait processing is outside this mod's installed-station magic-crafting scope.

This is a current compatibility baseline, not the final deterministic-family limit. GitHub #9 first supplies the typed-resource transaction boundary; the next public factory revision must model bounded deterministic N-input/N-output contracts across items, fluids, power, chemicals, catalysts, tools, and remainders. Until that engine exists, this API must not claim those resources are craftable merely because the Core can store them.

## Lifecycle and sides

- Register on the mod event bus before NeoForge registries freeze. Magic Storage freezes and validates its immutable adapter snapshot at common setup; runtime hot registration is unsupported.
- The recipe-family registry is server policy. Dedicated servers never load EMI or screen classes through this API.
- External families are ordered after built-ins and then by full registry ID. Addons cannot supply a priority or shadow a built-in exact class/type pair.
- A CI-tested addon version is representative evidence only. Addon metadata should use the compatibility range it actually supports rather than copying a single CI artifact version as an exact player-facing pin.

## Repository verification fixture

`src/apiTest/` proves the API compiles from a different Java package. `src/recipeAddonFixture/` is a separately loaded NeoForge mod that registers a custom recipe type and family, then proves station gating, discovery, exact preview/components/count, commit, and full-destination rollback.

```bash
./gradlew compileApiTestJava
./gradlew runRecipeAddonGameTestServer
```
