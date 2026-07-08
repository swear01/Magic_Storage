# Structure

| Path | Purpose |
|------|---------|
| `src/main/java/com/swearprom/magicstorage/magic_storage/` | 全部 Java 原始碼(~37 檔,單一扁平 package):方塊、BlockEntity、GUI Screen、封包、能量表、`MagicStorage.java` 註冊入口。 |
| `src/main/resources/assets/magic_storage/` | 客戶端資源(模型、材質、lang、blockstates,以及 Patchouli `guide/en_us` localized categories/entries)。 |
| `src/main/resources/data/magic_storage/` | 資料包(配方、loot、tags,以及 Patchouli `guide/book.json`)。 |
| `src/main/resources/data/minecraft/` | 對 vanilla 的資料覆寫/擴充。 |
| `src/main/templates/META-INF/` | `neoforge.mods.toml` 模板(Gradle 變數注入)。 |
| `scripts/` | 本機維運腳本與 Python unittest:版本 patch bump、Prism dev 部署。 |
| `docs/specs/` | 進行中的設計規格(目前空);實作完成的規格依規則 archive 至 `archive/docs/specs/`。 |
| `PLAN.md` | 完整設計計劃書(里程碑、能量系統、GUI)。 |

## Module Boundaries

- 計算集中在 **Storage Core**(BlockEntity);Storage Unit 只是空間,不自行計算。
- 網路狀態屬伺服器端;客戶端 GUI 一律靠封包同步,**不要**在客戶端保存儲存狀態。
- 本 repo 是根工作區 `diy_minecraft_mods` 內的獨立 git repo,版控自成一套。
