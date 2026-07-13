# Structure

| Path | Purpose |
|------|---------|
| `.github/workflows/` | GitHub Actions CI/CD:push/PR build + GameTest + Python unittest + datagen drift check + jar/log/report artifacts,`v<mod_version>` tag release + release notes,以及手動 `client-smoke.yml` NeoForge client boot/resource smoke。 |
| `art/texture-generation/` | 貼圖生成 metadata 與 preview 原稿；保留供美術追溯，但刻意放在 runtime resource tree 外，不打進 mod jar。 |
| `README.md` | 公開 GitHub repo 首頁:專案簡介、build/test、CI/CD、GUI 驗證入口、授權狀態。 |
| `src/main/java/com/swearprom/magicstorage/magic_storage/` | 全部 Java 原始碼(50 檔,主 package + `compat`/`gametest`):方塊、BlockEntity、GUI Screen、封包、`CraftableRecipeCatalog`、`CraftingDestination`、`CraftingStationTable`、`AxeTransformationCatalog`/`AxeTransformationRecipe`、`TerminalLayout.Geometry`/page-aware `FlowGrid`、`TerminalAmountFormatter`、`MachineEnergyTable`/runtime Fuel/recipe-time resolver、`MagicStorage.java` 註冊入口。`CraftingTerminalScreen` 的 Fuel rail 只保留三頁籤，Energy Reserves header 用 vanilla `CycleButton` 對應 server Auto/exact-target IDs；Installed Stations 使用兩個 flow rows，右下 `fuelControlPanel` 容納 target/input。`CraftingTerminalMenu` 以 137 slots/94 data slots 同步三頁 state、9 machine/station/tool slots、recipe output、九個 ingredient representatives/available/required 與 process/Fuel requirements；Fuel/machine/geometry 整合測試在 `gametest/FuelPageTests.java`，recipe/output/station/tool 行為在 `gametest/CraftingTests.java`。 |
| `src/main/resources/assets/magic_storage/` | 客戶端資源(模型、材質、lang、blockstates,以及 Patchouli `guide/en_us` localized categories/entries)。 |
| `src/main/resources/data/magic_storage/` | 資料包(配方、loot、tags,以及 Patchouli `guide/book.json`)。 |
| `src/main/resources/data/minecraft/` | 對 vanilla 的資料覆寫/擴充。 |
| `src/main/templates/META-INF/` | `neoforge.mods.toml` 模板(Gradle 變數注入)。 |
| `scripts/` | 本機維運腳本與 Python unittest:transactional 版本 patch bump/Prism dev jar 部署、選用 Prism wrapper 診斷工具、native offline GUI session runner(`-o MagicStorageBot`,清 wrapper/關 error-console pop-up、READY 後交給使用者目視)，以及 transactionally 從 1.21.1 metadata template 重寫 true-void generator、strip runtime state、安裝固定 lab/datapack 的 GUI world preparer。 |
| `docs/superpowers/plans/` | Active implementation/TDD contracts。Fuel runtime value、recipe timing、zero-storage Craftable、EMI one-level magic crafting 與 adaptive GUI 見 `2026-07-12-fuel-craftable-emi-adaptive-ui.md`；station/tool gates、Smithing/Axe recipe scope、safe output、two-row Fuel layout 與 16×16 textures 見 `2026-07-13-stations-recipes-output-textures.md`；true-void GUI lab 見 `2026-07-13-void-gui-test-lab.md`。 |
| `docs/superpowers/specs/` | Active design specs；true-void lab 的 generator、座標、預載、hotbar/reset 契約見 `2026-07-13-void-gui-test-lab-design.md`。 |
| `PLAN.md` | 完整設計計劃書(里程碑、能量系統、GUI)。 |

## Module Boundaries

- 計算集中在 **Storage Core**(BlockEntity);Storage Unit 只是空間,不自行計算。
- 網路狀態屬伺服器端;客戶端 GUI 一律靠封包同步,**不要**在客戶端保存儲存狀態。
- 本 repo 是根工作區 `diy_minecraft_mods` 內的獨立 git repo,版控自成一套。
