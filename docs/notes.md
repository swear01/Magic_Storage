# Notes

> Tacit knowledge an agent can't infer from reading code.

## Reference Source — Refined Storage 2

實作儲存機制、網路邏輯、grid UI、物品/流體處理卡關時,**先參考 Refined Storage 2 原始碼**。

| Topic | Where to look |
|-------|--------------|
| 儲存網路架構 | `refinedstorage2-platform-common/src/main/java/.../network/` |
| Grid(物品瀏覽 UI) | `refinedstorage2-platform-common/src/main/java/.../grid/` |
| Storage provider / disk API | `refinedstorage2-api/src/main/java/.../storage/` |
| 物品/流體資源處理 | `refinedstorage2-api/src/main/java/.../resource/` |
| 自動合成 | `refinedstorage2-platform-common/src/main/java/.../autocrafting/` |
| NeoForge 平台橋接 | `refinedstorage2-platform-neoforge/` |

Source: https://github.com/refinedmods/refinedstorage2

卡關工作流:1) 在 RS2 repo 搜你要的介面/類別 → 2) 讀實作理解模式 → 3) 改寫(**勿整段照抄**,授權不同)→ 4) 仍不確定就問使用者。

## Gotchas

- **網路邏輯一律伺服器端**;客戶端靠封包同步,絕不在客戶端保存儲存狀態。
- Capability 註冊遵循 NeoForge 慣例。
- 實作進度以**程式碼為準**:已有 ~36 個 Java 檔 + SelfTest/GameTest;設計細節見 `PLAN.md`,即時狀態見 `docs/plan.md`。
- 建置前需設 `JAVA_HOME`(JDK 21,見根 repo `docs/notes.md`)。

## Decisions

- **統一能量池模型**:所有能量池同為 `Map<EnergyType, Long>` 無上限,差異只在來源,降低特例。
- **參考 RS2 而非依賴**:借設計模式,不加為依賴;加任何依賴前先問使用者(minimize deps)。
- **絕不整段照抄 RS2**:授權不同;只取模式自行實作。
