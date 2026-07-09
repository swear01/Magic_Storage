# Plan

> 完整里程碑與設計見 `PLAN.md`(§八 開發里程碑 M1–M12)。狀態以程式碼為準(已有 ~37 Java 檔 + SelfTest/GameTest)。

M1–M11 大致到位(方塊/網路/六階 Unit/三階 Terminal/能量/即時+爐系合成/Compact/remote/buses,以程式碼為準)。近期已完成**健壯化** + **RS2 慣例採用**(fuzzy 配對、view 同步、選取身分化、合成 preview、增量網路成長)與 GUI/Patchouli 修正,見 `docs/roadmap.md` / `docs/rs2-design-gap.md`。測試:SelfTest 104 + GameTest 74。

## In Progress

- (無進行中;上一批 RS2 P1/P2/P4、GitHub CI/CD、Prism GUI test world 流程已 commit/驗證)

## Next Up

- **P3 增量 grid delta**(grid 改收差異、不整份重建;前置 A2 Actor / A3 變更事件)。計劃見 `docs/superpowers/plans/2026-06-21-rs2-heuristics-adoption.md`。

## Latest Verification

- 2026-07-09 GUI test world 固定流程:`python3 scripts/prepare_prism_gui_world.py` 會重建 Prism dev `MagicStorageGuiTest`、安裝無 command block datapack rig、啟用 `/function`/`/tp`、固定座標與 `u` 開啟方塊,後續 Computer Use GUI 測試改採當次需求動態選 hotbar view key + condition polling;已實測 `1`/`2` 對準並開 Storage/Crafting Terminal。
- 2026-07-09 CI/CD 本地驗證目標:`.github/workflows/ci.yml` 跑 build + GameTest + Python unittest + datagen drift check + jar/log/report artifacts;`.github/workflows/release.yml` 僅接受 tag `v<mod_version>`,重跑同一組 gates,產 release notes 並用 GitHub CLI 建 release。
- 2026-07-08 Prism dev 實機驗證:`magic_storage-0.1.3.jar` 已部署到 dev instance,mods 內僅此一個 Magic Storage jar。以 `open -a "Prism Launcher" --args -l dev -w "New World"` 直進世界(現已由 `MagicStorageGuiTest` 固定流程取代),先 windowed 再切 macOS native fullscreen;Computer Use 目視確認 Patchouli guide、Storage Terminal、Crafting Terminal、左側 view buttons、>64 數量顯示,且 log 無 `advanced_container_set_data` / `ERROR` / `FATAL` / `Caused by`。
