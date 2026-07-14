# Void GUI Test Lab Design

## Goal

Replace the mutable terrain-based `MagicStorageGuiTest` world with a deterministic, transactionally regenerated true-void laboratory that makes manual fullscreen GUI checks fast, spatially predictable, and repeatable.

## Chosen approach

The preparer continues to use Prism's `New World/level.dat` only as a current Minecraft/NeoForge metadata template. Before the generated world is swapped into place, it:

1. rewrites the overworld generator to `minecraft:flat`;
2. uses biome `minecraft:the_void`, one `minecraft:air` layer, no features, no lakes, and an explicitly empty structure override set;
3. removes copied chunk, entity, point-of-interest, player, scoreboard, datapack, dimension, and other runtime state, including both the `playerdata/` directory and the singleplayer snapshot embedded at `level.dat/Data/Player`;
4. installs only the generated `magic_storage_gui_test` datapack.

This is preferred over clearing a normal world at high altitude because distant terrain would still exist, and over adding a test world preset to the mod because GUI-test infrastructure must not ship in the production jar.

Malformed or unsupported `WorldGenSettings` fails before the old generated target or Prism options are changed. The existing marker check, open-world check, staging directory, atomic swap, and rollback behavior remain mandatory.

## Laboratory layout

The lab is built at Y=80 on a 37×25 neutral platform (`x=-18..18`, `z=-12..12`) with a dark border and visually separated vanilla-block lanes.

### Active network

| Target | Block position | Purpose |
|---|---:|---|
| Storage Core | `(0,80,0)` | Single authoritative Core |
| Storage Terminal | `(-1,80,0)` | Storage grid checks |
| Crafting Terminal | `(1,80,0)` | Storage/Craftable/Fuel and recipe checks |
| T6 → T1 units | `(0,80,-1)` through `(0,80,-6)` | Connected capacity spine, total 785 types |
| Import Bus | `(-1,80,-1)`, facing west | Functional import bay |
| Import barrel | `(-2,80,-1)` | Bus source |
| Export Bus | `(1,80,-1)`, facing east | Functional export bay |
| Export barrel | `(2,80,-1)` | Bus destination |

All active network blocks are face-connected. There is exactly one connected Core.

### Isolated visual gallery

At `z=-9`, eleven disconnected samples are spaced two blocks apart from `x=-10` through `x=10`: Core, T1–T6, Storage Terminal, Crafting Terminal, Import Bus, and Export Bus. The air gap prevents the gallery Core from joining another sample or the active network, so it cannot create a multi-core conflict.

### View controls

| Hotbar key | View |
|---:|---|
| `1` | Storage Terminal |
| `2` | Crafting Terminal |
| `3` | Storage Core |
| `4` | Six-unit capacity spine |
| `5` | Import Bus and source barrel |
| `6` | Export Bus and destination barrel |
| `7` | Isolated texture gallery |
| `8` | Whole-lab overview/home |
| `9` | Rebuild the ready baseline |

Each key maps to a known `tp ... facing ...` function. A per-slot latch prevents repeated teleports and reset loops. No command block, fixed sleep, mouse automation, or chat typing is required.

## Ready baseline

The Core is created with the same persistent NBT schema used by `StorageCoreBlockEntity`.

### Stored resources

| Item | Count |
|---|---:|
| Cobblestone | 8,192 |
| Oak Log | 192 |
| Iron Ingot | 128 |
| Diamond | 32 |
| Coal | 64 |
| Blaze Rod | 16 |
| Glass Bottle | 16 |
| Netherite Upgrade Smithing Template | 1 |
| Diamond Sword | 1 |
| Netherite Ingot | 4 |

These cover long-count rendering, sorting/searching, player/Core ingredient aggregation, crafting, stonecutting, and Smithing Transform without filling the player inventory.

### Stations and energy

The baseline writes Crafting Table in machine slot 5, Stonecutter in slot 6, Smithing Table in slot 7, and an undamaged Iron Axe in legacy slot 8. On Core load, the first three remain installed max-one instant stations while the legacy axe is atomically converted into finite Axe Energy and slot 8 clears. Furnace, Blast Furnace, Smoker, Campfire, and Brewing Stand slots are empty. Every process/Fuel energy pool starts at zero.

This exposes instant-station and axe recipes immediately while exercising real legacy migration and preserving the invariant that empty process-machine slots generate no energy. The player test kit supplies all process machines and fuels for explicit installation tests.

### Player kit

Hotbar slots contain semantic navigation items in fixed positions. Main-inventory slots contain three Furnaces; one Blast Furnace, Smoker, Campfire, and Brewing Stand; fuels; logs; station replacements; Smithing inputs; Cobblestone; a Remote Terminal; one plain Iron Axe; one damaged Unbreaking II Iron Axe; and one Unbreakable Iron Axe. `item replace` is used instead of `give`, so slot identity is deterministic and finite, enchantment-scaled, and infinite Axe Energy paths are all available without manual commands.

## Bootstrap and reset

Datapack load initializes one timer objective and calls `setup`. `setup` clears the complete lab volume, removes nearby dropped items, rebuilds the platform/network/gallery, clears ready/view tags, and resets the timer. It does not immediately announce readiness.

The tick function waits three server ticks before `player_ready`. This lets scheduled network growth and block-entity loading settle without a fixed wall-clock sleep. `player_ready` applies creative/night-vision state, installs the fixed inventory, primes the current hotbar latch, teleports home, then emits `MS_GUI_TEST_READY`.

Hotbar `9` calls the same setup path. Reset is therefore the same operation as first boot, not a partial cleanup path.

## Manifest contract

The generated manifest records:

- schema version;
- true-void generator summary;
- stripped runtime paths;
- platform bounds and named zones;
- every active/gallery block and view stand/face coordinate;
- hotbar mapping;
- Core stored resources, installed station slots, legacy axe migration, and zero process/Fuel-energy profile;
- fixed player kit;
- bootstrap delay and reset function;
- fullscreen gate and offline launch command.

The Prism session checklist consumes target names from this manifest; it does not duplicate coordinates.

## Testing

Python tests must first fail, then cover:

1. exact flat-void NBT rewrite and a readable summary;
2. fail-closed behavior for missing/malformed worldgen compounds;
3. removal of copied runtime/chunk/datapack state and `level.dat/Data/Player` while preserving the source;
4. exact active-network and gallery coordinates;
5. Core NBT preload, fixed machine slots, and zero energy;
6. deterministic player inventory and all nine hotbar semantics;
7. three-tick readiness, latch initialization, and full reset reuse;
8. no command blocks or fixed sleeps;
9. existing transaction/open-world/options/fullscreen contracts.

After the focused suite passes, run all Python tests, Gradle build, GameTest server, and datagen drift checks. The final real-client gate must prove the current jar, SelfTest, Patchouli resources, and `MS_GUI_TEST_READY`; visual approval remains owned by the user after entering fullscreen.

## Out of scope

- Production mod/world-preset code.
- Command blocks.
- A fixed list of GUI assertions for every future feature.
- Recursive magic-crafting automation.
- Preloading nonzero machine energy or process machines in the ready baseline.
