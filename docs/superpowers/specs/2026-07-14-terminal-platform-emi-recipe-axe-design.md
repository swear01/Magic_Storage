# Shared Terminal Platform, EMI-First Recipes, and Axe Energy Design

## Goal

Replace the split Storage/Crafting Terminal presentation with one capability-driven terminal platform, present selected recipes through EMI widgets when EMI is available with an explicit native fallback, correct Craftable-grid quantities and sorting, add a safe craft-output destination, and replace the installed axe with a consumable finite-or-infinite Axe Energy reserve.

## Chosen architecture

Storage Terminal and Crafting Terminal remain distinct menu/screen registrations, but both use one shared terminal shell. The shell owns adaptive geometry, panel rendering, the item grid, search, scrolling, amount overlays, left-rail controls, focus cleanup, hit testing, and EMI exclusion bounds. A terminal profile enables only the capabilities that terminal exposes:

- Storage Terminal: Storage page plus shared search, sorting, order, extraction, and player inventory.
- Crafting Terminal: Storage, Craftable, and Fuel pages plus recipe selection, resource preview, ingredient-source toggle, output-destination toggle, stations, fuel targets, and craft actions.
- Future terminals: compose a profile from the same capabilities instead of copying a screen or creating then hiding inherited widgets.

`TerminalLayout` has one profile-driven entry point. Rendering, widgets, reconstructed slots, mouse regions, tooltips, scrolling, popups, and EMI exclusions consume the same immutable geometry. The old separate Storage layout/control path and Crafting-only replacement rail are removed.

## Shared rail and interaction contract

All rail controls use one 18-pixel button and one 16-pixel icon system. Page controls and view controls are separated by the existing group gap. Semantic Minecraft item icons are preferred for pages and destinations; abstract operations use a single pixel-art sprite atlas.

The common cycle contract is:

- left click selects the next value;
- right click selects the previous value;
- wheel down selects next and wheel up selects previous when the control is hovered;
- the tooltip names only the control and current value, plus the left/right interaction hint;
- it never enumerates every possible value.

Recipe alternatives use two explicit left/right arrow buttons rather than a cycle glyph. Search, sorting, and player-inventory-source icons are redrawn; procedural Unicode and filled-shape rail glyphs are removed.

## Sorting and grid amounts

The shared sort modes are Name, Quantity, Mod, and ID.

- Name sorts by display name with registry ID as a deterministic tie-breaker.
- Quantity sorts by the exact server-owned amount.
- Mod sorts by registry namespace, then display name, then registry path.
- ID sorts by the complete registry identifier.

Storage and Craftable use the same comparator implementation.

Every visible network entry has a logical `ItemKey` and an exact display amount independent of `ItemStack#getCount`. Craftable entries carry the currently stored Core amount, not the number that could be crafted. A craftable output remains listed whenever at least one supported recipe can currently be crafted, including when that output is already stored. Zero stored amount leaves the icon visible but draws no count; positive amounts draw the exact compact representation.

The display amount travels as server-owned metadata on synthetic display stacks so it shares vanilla slot ordering and does not require a client-side storage mirror. One helper creates, reads, and strips this metadata; synthetic stacks remain non-extractable and the metadata must never become part of an `ItemKey` or a real inserted/output stack.

All network counts use one screen-wide font scale derived from the widest permitted compact string and the 16-pixel slot bound. The renderer no longer switches individual values between normal and half size. Exact long values remain available in tooltips.

## Recipe presentation

The server resolves the selected exact recipe and synchronizes a presentation model; the screen never scans `RecipeManager` or Core state. The model contains:

- exact recipe identity and supported presentation kind;
- shaped width/height, shapeless state, and up to nine positioned input representatives;
- exact output stack including per-craft output count;
- station identity;
- aggregated item-resource rows with available and required-for-one amounts;
- process, fuel, or tool-resource rows with available and required-for-one amounts.

The workspace is split vertically:

1. an EMI-style recipe diagram at the top;
2. a compact resource ledger below it;
3. recipe navigation, output destination, and craft-amount actions in the footer.

Crafting uses a positioned 3x3 grid, a processing arrow, a large output slot, and a shapeless marker where applicable. Cooking, stonecutting, smithing transform, and synthetic axe transformations have dedicated presentations matching their real input roles. The output slot displays the exact result count. Empty space is not divided into nine fixed resource cells.

Item-resource rows use the neutral panel palette. Energy and tool-resource rows use a distinct dark-red background and border. Availability still controls the green/red amount text; background color communicates resource kind rather than success.

## EMI-first renderer with explicit fallback

EMI is a required client-only dependency in released metadata with Maven range `[1.1.24,2)`. The exact `1.1.24+1.21.1` coordinate remains only the reproducible minimum compile/development baseline; dedicated servers do not require EMI. CI compiles that minimum and the newest compatible Minecraft 1.21.1 EMI release, while client smoke stages the newest compatible full jar.

The base screen references only a Magic Storage client renderer interface. A guarded EMI compatibility bootstrap is loaded only when NeoForge reports EMI present, keeping dedicated servers free of EMI class references.

For a selected standard recipe:

1. the server-synced exact recipe ID is used to locate the corresponding client EMI recipe;
2. Magic Storage supplies a public-API `WidgetHolder` implementation;
3. `EmiRecipe#addWidgets` populates the top recipe diagram;
4. Magic Storage translates and renders those widgets, forwards bounded mouse/key input, and renders their tooltips;
5. the native server-synced resource ledger and craft controls remain authoritative.

The native renderer is selected only for two approved capability cases: EMI is not installed, or the exact selected recipe has no compatible EMI representation, including internal synthetic axe recipes. Runtime exceptions do not silently switch renderers; they surface through the normal client error path. No EMI internal screen or `WidgetGroup` class is linked.

## Craft output destination

Crafting Terminal has a server-synced session toggle independent of `Use Player Inventory`:

- Player: use the post-ingredient player inventory first and plan any remainder into Core, preserving the existing no-drop safety contract.
- Storage: put all primary output and crafting remainders into Core only.

If the selected destination cannot accept the complete batch, the entire operation is a no-op. Direct buttons, Max, and exact EMI requests continue to use simulate-then-commit and exact recipe identity. EMI's explicit Cursor/Inventory destination remains authoritative for an EMI-initiated action; the terminal toggle governs the terminal's own craft buttons.

## Station categories

Station descriptors are classified by behavior rather than represented by one undifferentiated machine slot table.

### Process machine

Furnace, Blast Furnace, Smoker, Campfire, and Brewing Stand are stackable installed machines. Each installed block contributes its declared process energy per tick. They remain removable.

### Instant station

Crafting Table, Stonecutter, and Smithing Table unlock recipes, generate no energy, allow exactly one installed block of each kind, and remain removable.

### Consumable tool energy

Axes are no longer installed equipment. An accepted axe is converted atomically into Axe Energy and the item is consumed. The system can therefore accept many axes over time. An accepted axe never remains in a retrievable station slot; a rejected insertion remains with the player.

Finite axe value is:

`remaining durability * (vanilla Unbreaking level + 1)`

Mending adds no value because the stored reserve has no XP repair process. Other enchantments do not change axe uses. Addition is checked before consumption; overflow rejects the axe instead of saturating and losing value.

An item carrying the Unbreakable component sets an explicit infinite Axe Energy flag. Infinite is not encoded as `Long.MAX_VALUE`: axe recipes do not decrement it, it persists in Core NBT, and recipe metadata carries a separate infinity bit so the UI displays `∞` without confusing a finite `Long.MAX_VALUE` reserve. Once infinite, further axes are rejected without consumption.

The old persisted axe station slot is migrated once. A valid finite or Unbreakable axe is converted with the same rules and cleared only after the new reserve is stored. If conversion fails, the original item remains persisted and the Axe Energy slot exposes it for recovery. Existing process stations retain their identities and contents; legacy stacked instant stations keep one installed and recover representable extras into Core storage.

## Fuel target selector and station hit boxes

The current target cycle remains available and gains a separate list button. The anchored popup is generated from the ordered server-approved target descriptors and shows representative item, localized name, and selected state. It supports bounded scrolling, outside-click close, Escape close, page-leave focus cleanup, and an EMI exclusion rectangle. While open, its rectangle consumes pointer/scroll input and suppresses covered container-slot tooltips instead of clicking or describing the UI underneath it.

Station tooltips trigger only over the actual station slot or representative icon bounds. Hovering unused padding in a flow cell produces no station tooltip. Paging and future descriptor growth continue to use panel-local flow geometry.

## Texture and icon system

Runtime block/item textures remain native 16x16 pixels. The next asset pass uses family generation rather than independent prompts:

1. select one shared dark deepslate/blackstone chassis and one cyan/amethyst palette;
2. reuse the selected family image as the Replicate img2img reference with fixed seed/settings;
3. apply role-specific semantic motifs and a final pixel cleanup;
4. review one nearest-neighbor contact sheet before selecting runtime files;
5. retain generation metadata/previews only under `art/texture-generation/`.

The semantic families are Core rune crystal; Storage Unit cell plus tier bars; Storage Terminal item-grid display; Crafting Terminal derived display plus crafting mark; blue inward Import arrow; orange outward Export arrow; and handheld Remote display. Referenced faces must be visually distinct while retaining the common casing. Obsolete unreferenced runtime textures and all non-16x16 model-referenced textures are removed.

## Testing and delivery

Every production behavior starts with a focused failing SelfTest, GameTest, or Python static test and is implemented only after the expected RED result is observed.

Required coverage includes:

- shared Storage/Crafting profile geometry, rail construction, click direction, focus, tooltip, and EMI exclusion contracts;
- Craftable zero/positive/long stored amounts, stored-and-craftable union, synthetic no-extraction, and metadata stripping;
- shared Name/Quantity/Mod/ID ordering and deterministic ties;
- shaped, shapeless, cooking, stonecutting, smithing, and axe presentation metadata plus exact output count;
- EMI-present public-widget path, no-EMI native path, known unsupported-recipe native path, and no broad exception fallback;
- Player/Core destination capacity, remainders, direct craft, Max, exact EMI requests, rollback, single-mutation long-count commits, and `Long.MAX_VALUE` reservations;
- process stack rates, instant station max-one plus legacy-overstack recovery, finite/multiple/Unbreaking/overflow axes, explicit finite-vs-infinite presentation, save/load, and recoverable old-slot migration;
- exact station hover bounds, scalable popup geometry/input, and no rail overlap;
- one count scale, slot-local text bounds, 16x16 referenced textures, semantic asset families, and no generation artifacts in runtime resources.

Final gates are compileJava, build, dedicated GameTest server, all Python tests, runData plus datagen drift, JSON/model/texture checks, automatic patch-version bump, transactional Prism deployment, and a current-run fullscreen visual checklist owned by the user.

## Out of scope

- RS2 recursive pattern trees, crafting jobs, or crafting monitors.
- Bundling EMI in the Magic Storage jar or accepting EMI 2.x without an explicit compatibility review.
- Client-side storage authority or a complete client mirror of the network inventory.
- Arbitrary modded durability-effect valuation without a future server-owned descriptor API.
- A public third-party station/fuel registration API in this revision.
