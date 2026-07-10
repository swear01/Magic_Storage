# Plan

> 完整里程碑與設計見 `PLAN.md`(§八 開發里程碑 M1–M12)。狀態以程式碼為準(已有 40 Java 檔 + SelfTest/GameTest)。

M1–M11 大致到位(方塊/網路/六階 Unit/三階 Terminal/能量/即時+爐系合成/Compact/remote/buses,以程式碼為準)。近期已完成**健壯化** + **RS2 慣例採用**(fuzzy 配對、view 同步、選取身分化、合成 preview、增量網路成長、Action/Actor storage contract、change-event foundation)與 GUI/Patchouli 修正,見 `docs/roadmap.md` / `docs/rs2-design-gap.md`。測試:SelfTest 104 + GameTest 102。

## In Progress

- (無進行中;上一批 RS2 P0/P1/P2/P4、GitHub CI/CD、Prism GUI test world 流程已驗證)

## Next Up

- **P3 增量 grid delta**(grid 改收差異、不整份重建)。A2 Actor / A3 storage change-event foundation 已完成;但目前分頁式 grid(≤81 格,vanilla slot sync 已是增量)價值偏低,除非改成整列表 client grid 才值得做。計劃見 `docs/superpowers/plans/2026-06-21-rs2-heuristics-adoption.md`。

## Latest Verification

- 2026-07-11 Prism GUI session automation:`scripts/setup_prism_computer_use_wrapper.py` 可重建 `/tmp/MagicStorageMinecraftCU.app` + wrapper 並 patch Prism dev `instance.cfg`;`scripts/run_prism_gui_session.py` 產生 `build/gui-runs/<timestamp>-<scenario>/` artifacts、只讀本次 log offset、區分 `boot-smoke`(不需目視)與 `terminal-left-rail`/`patchouli-guide`(需要 fullscreen gate + Computer Use 目視)。本機已實測 `boot-smoke` 成功開 Prism dev `MagicStorageGuiTest` 並等到 `SelfTest: 104 passed` + `MS_GUI_TEST_READY`。新增手動 `client-smoke.yml` 作 NeoForge client boot/resource smoke,不列為 GUI layout approval。
- 2026-07-10 P0/P1/P2 RS2 parity + test expansion:`./gradlew compileJava`、`./gradlew runGameTestServer` 已驗證 SelfTest 104 + GameTest 102。新增/守護:Action/Actor storage ops、per-resource listener events、simulate no event、type-slot reuse、bus cached-core disconnect invalidation、import bus type-full no extraction、export respects target capacity、invalid tag filter no throw、incremental placement bridge fallback、multi-core bridge conflicted、terminal access/remote `stillValid`、display pickup stack amounts、craftable-only server filter、server-synced crafting recipe metadata/hidden slots parity、EMI no hidden metadata slots、real crafting/smelting/player-inventory craft paths、duplicate ingredients aggregate before craft、crafting terminal 初始 compact grid 套用。
- 2026-07-10 ClientSetup NeoForge deprecation cleanup:`@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)` 已移除,client-only `RegisterMenuScreensEvent` 改由 `FMLEnvironment.dist == Dist.CLIENT` guard 後用 `modEventBus.addListener(ClientSetup::registerScreens)` 註冊;Python static regression 守護不回退。
- 2026-07-09 GUI test world 固定流程:`python3 scripts/prepare_prism_gui_world.py` 會重建 Prism dev `MagicStorageGuiTest`、安裝無 command block datapack rig、啟用 `/function`/`/tp`、固定座標與 `u` 開啟方塊,後續 Computer Use GUI 測試改採當次需求動態選 hotbar view key + mandatory fullscreen gate + condition polling;已實測 `1`/`2` 對準並在 fullscreen 下開 Storage/Crafting Terminal。
- 2026-07-09 CI/CD 本地驗證目標:`.github/workflows/ci.yml` 跑 build + GameTest + Python unittest + datagen drift check + jar/log/report artifacts;`.github/workflows/release.yml` 僅接受 tag `v<mod_version>`,重跑同一組 gates,產 release notes 並用 GitHub CLI 建 release。
- 2026-07-08 Prism dev 實機驗證:`magic_storage-0.1.3.jar` 已部署到 dev instance,mods 內僅此一個 Magic Storage jar。以 `open -a "Prism Launcher" --args -l dev -w "New World"` 直進世界(現已由 `MagicStorageGuiTest` 固定流程取代),先 windowed 再切 macOS native fullscreen;Computer Use 目視確認 Patchouli guide、Storage Terminal、Crafting Terminal、左側 view buttons、>64 數量顯示,且 log 無 `advanced_container_set_data` / `ERROR` / `FATAL` / `Caused by`。
