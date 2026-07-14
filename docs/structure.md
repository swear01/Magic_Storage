# Structure

| Path | Purpose |
|------|---------|
| `.github/workflows/` | GitHub Actions CI/CD:push/PR build + GameTest + Python unittest + datagen drift check + jar/log/report artifacts,`v<mod_version>` tag release + release notes,以及手動 `client-smoke.yml` NeoForge client boot/resource smoke。 |
| `art/texture-generation/` | 貼圖生成 metadata 與 preview 原稿；保留供美術追溯，但刻意放在 runtime resource tree 外，不打進 mod jar。 |
| `README.md` | 公開 GitHub repo 首頁:專案簡介、build/test、CI/CD、GUI 驗證入口、授權狀態。 |
| `src/main/java/com/swearprom/magicstorage/magic_storage/` | 全部 Java 原始碼(56 檔,主 package + `compat`/`gametest`)。終端共用平台由 capability-based `TerminalProfile`、唯一 `TerminalLayout.forProfile(...)`、immutable `Geometry`/page-aware `FlowGrid`、dist-neutral `TerminalCycleDirection`/`TerminalAmountFormatter` 與 `StorageTerminalScreen` shared shell 組成；Crafting 只加 pages、source、recipe/Fuel controls。左 rail 統一 18×18 hitbox、16×16 item/atlas icon，cycle 一律 left-next/right-previous/wheel-down-next/wheel-up-previous；Fuel rail 只保留三頁籤，header target selector 也走 shared cycle input。`CraftingTerminalMenu` 以 149 slots/94 data slots 同步三頁 state、9 machine/station/tool slots，以及 22 個 hidden presentation slots：exact output、9 個 item ledger representatives、station、9 個 positioned inputs、tool、typed metadata carrier；`RecipePresentation`/`RecipePresentationKind` 將 exact ID/layout/output/components/count/typed resources 以 immutable client view 暴露。另含 `TerminalDisplayStack` exact-long metadata、共用 `TerminalEntryComparator`、`CraftableRecipeCatalog`、`CraftingDestination`、`CraftingStationTable`、`AxeTransformationCatalog`/recipe、`MachineEnergyTable`/runtime Fuel/recipe-time resolver及所有 storage/network classes；Fuel/geometry/wire tests 在 `gametest/FuelPageTests.java`，terminal cycle/data integration 在 `gametest/TerminalFlowTests.java`。 |
| `src/main/resources/assets/magic_storage/` | 客戶端資源(模型、材質、lang、blockstates,以及 Patchouli `guide/en_us` localized categories/entries)。 |
| `src/main/resources/data/magic_storage/` | 資料包(配方、loot、tags,以及 Patchouli `guide/book.json`)。 |
| `src/main/resources/data/minecraft/` | 對 vanilla 的資料覆寫/擴充。 |
| `src/main/templates/META-INF/` | `neoforge.mods.toml` 模板(Gradle 變數注入)。 |
| `scripts/` | 本機維運腳本與 Python unittest:transactional 版本 patch bump/Prism dev jar 部署、選用 Prism wrapper 診斷工具、native offline GUI session runner(`-o MagicStorageBot`,清 wrapper/關 error-console pop-up、READY 後交給使用者目視)，以及 transactionally 從 1.21.1 metadata template 重寫 true-void generator、strip runtime state、安裝固定 lab/datapack 的 GUI world preparer。 |
| `docs/superpowers/plans/` | Active implementation/TDD contracts。目前 shared terminal profile、exact recipe presentation、EMI-first renderer、output routing、station/Axe Energy、Fuel popup 與 texture family 主線見 `2026-07-14-terminal-platform-emi-recipe-axe.md`；較早 Fuel/recipe/station 與 true-void lab contracts 保留在同目錄供完成狀態追溯。 |
| `docs/superpowers/specs/` | Active design specs；目前 terminal/EMI/station/Axe revision 見 `2026-07-14-terminal-platform-emi-recipe-axe-design.md`，true-void lab generator/座標/預載/hotbar-reset 契約見 `2026-07-13-void-gui-test-lab-design.md`。 |
| `PLAN.md` | 完整設計計劃書(里程碑、能量系統、GUI)。 |

## Module Boundaries

- 計算集中在 **Storage Core**(BlockEntity);Storage Unit 只是空間,不自行計算。
- 網路狀態屬伺服器端;客戶端 GUI 一律靠封包同步,**不要**在客戶端保存儲存狀態。
- 本 repo 是根工作區 `diy_minecraft_mods` 內的獨立 git repo,版控自成一套。
